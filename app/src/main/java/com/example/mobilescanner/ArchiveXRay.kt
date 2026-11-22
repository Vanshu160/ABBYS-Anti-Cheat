package com.example.mobilescanner

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Total number of decompressed bytes one scan may read; protects against archive bombs. */
internal class InflateBudget(var left: Long) {
    fun take(n: Int): Boolean {
        if (left <= 0) return false
        left -= n
        return true
    }
}

/** Cheat-client names and Minecraft combat/automation indicators, and how they are matched. */
internal object Terms {
    /** Distinctive names, matched inside the letters-and-digits-only form of a string. Value = family key. */
    private val STRONG_LONG = mapOf(
        "automace" to "automace", "maceswap" to "maceswap", "breachswap" to "breachswap",
        "vapev4" to "vape", "vapelite" to "vape", "vapeclient" to "vape",
        "killaura" to "killaura", "triggerbot" to "triggerbot", "aimassist" to "aimassist",
        "autoclicker" to "autoclicker", "autototem" to "autototem", "crystalaura" to "crystalaura",
        "anchoraura" to "anchoraura", "liquidbounce" to "liquidbounce", "bleachhack" to "bleachhack",
        "thunderhack" to "thunderhack", "aristois" to "aristois", "doomsday" to "doomsday",
        "baritone" to "baritone", "meteorclient" to "meteor",
        "wurstclient" to "wurst", "rusherhack" to "rusherhack", "salhack" to "salhack",
        "kamiblue" to "kamiblue", "impactclient" to "impact", "futureclient" to "future",
        // Distinctive client names: long and unusual enough to match inside the letters-and-digits form.
        "3arthh4ck" to "3arthh4ck", "nursultan" to "nursultan", "zamorozka" to "zamorozka", "melonity" to "melonity",
        "wexside" to "wexside", "troxill" to "troxill", "catlean" to "catlean", "wweclient" to "wwe client",
        "wildclient" to "wild client", "excellentclient" to "excellent client", "onixclient" to "onix client",
        "flarial" to "flarial", "ambrosial" to "ambrosial", "liquidbounceplus" to "liquidbounce", "gamesense" to "gamesense",
        // More long, unusual client names and module names that only cheats have.
        "forgehax" to "forgehax", "novoline" to "novoline", "huzuni" to "huzuni", "mahanware" to "mahanware",
        "starhack" to "starhack", "esohack" to "esohack", "kinodupe" to "kinodupe", "g4dmode" to "g4dmode", "etikahack" to "etikahack",
        "aimbot" to "aimbot", "autocrystal" to "autocrystal", "autoanchor" to "autoanchor"
    )

    /**
     * Client names that are short or look like ordinary words. They only count as whole words (or whole word groups),
     * never inside a longer word, so "Horion" is caught but "horizon" is not. Ordinary English words ("Future", "Delta",
     * "Vector", "Impact", "Venus"...) are only accepted together with "client", for example SigmaClient or Delta Client.
     */
    private val DISTINCT_WORDS = listOf(
        "horion", "latite", "cornos", "akrien", "relake", "nightx", "nitr0", "verist",
        "konas", "seppuku", "xulu", "hanabi", "astolfo", "borion", "nodus", "backdoored", "xdolf", "nhack", "nhack2", "nhack3", "dotgod"
    )
    private val ORDINARY_WORDS = listOf(
        "future", "impact", "sigma", "venus", "delta", "eternity", "expensive", "rockstar", "ares", "vector",
        "apollo", "celestial", "meteor", "inertia", "phobos", "zephyr", "solstice", "primordial", "prax", "kodiak",
        "kion", "wolfram", "minced", "deadcode",
        // Not "lambda", "raven", "flux" or "cosmos": AWS LambdaClient, Sentry Raven and others are ordinary Java classes.
        "pyro", "tenacity", "exhibition", "augustus", "akira", "drip", "zeroday", "vestige", "rise", "moon",
        "azura", "entropy", "opai", "myau", "slinky", "ferox", "hypnotic"
    )
    /** phrase (letters and digits only, lower case) -> family key */
    private val PHRASES: Map<String, String> = buildMap {
        for (w in DISTINCT_WORDS) put(w, w)
        for (w in ORDINARY_WORDS) put(w + "client", w)
        put("apollowurst", "wurst")
    }

    /** Short names: only matched as a whole word, so "vape" never hits inside another word. */
    private val STRONG_SHORT = setOf("vape", "wurst", "manthe")

    /** Ordinary words that are also client or module names: reported as context, never as proof. */
    private val WEAK = setOf("reach", "velocity", "hitbox", "scaffold", "freecam", "xray", "meteor", "shoreline")

    val COMBAT = listOf(
        "attackEntity", "interactItem", "selectedSlot",
        "ClientPlayerInteractionManager", "PlayerInteractEntityC2SPacket", "GLFW_MOUSE_BUTTON"
    )

    /** Config/text keywords: HARD ones are specific to cheats, SOFT ones are common in ordinary settings. */
    val HARD = setOf("mace", "breach", "vape", "manthe", "aura", "trigger", "aim", "cps", "hitbox", "reach", "xray", "esp")
    val SOFT = setOf("swap", "velocity", "totem", "crystal", "anchor", "click")
    private val CONTEXT_WORDS = setOf("keybind", "toggle")
    private val ENABLED_TRUE = Regex("\"?enabled\"?\\s*[:=]\\s*true", RegexOption.IGNORE_CASE)

    private val CAMEL = Regex("(?<=[a-z0-9])(?=[A-Z])")
    private val SEP = Regex("[^A-Za-z0-9]+")

    fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        for (part in s.split(SEP)) {
            if (part.isEmpty()) continue
            for (t in part.split(CAMEL)) if (t.isNotEmpty()) out.add(t.lowercase())
        }
        return out
    }

    /** Returns the cheat-client family key if the string names one, otherwise null. */
    fun matchStrong(s: String): String? {
        val low = s.lowercase()
        val flat = low.filter { it.isLetterOrDigit() }
        for ((term, key) in STRONG_LONG) {
            if (flat.contains(term)) return key
        }
        if (low.contains("vape") || low.contains("wurst") || low.contains("manthe")) {
            for (t in tokens(s)) if (t in STRONG_SHORT) return t
        }
        return matchPhrase(s, flat)
    }

    /** Whole-word(-group) match of PHRASES, with a cheap pre-check because this runs on many strings. */
    private fun matchPhrase(s: String, flat: String): String? {
        if (PHRASES.keys.none { flat.contains(it) }) return null
        val toks = tokens(s)
        for (i in toks.indices) {
            var joined = ""
            for (n in 0 until 3) {
                if (i + n >= toks.size) break
                joined += toks[i + n]
                PHRASES[joined]?.let { return it }
            }
        }
        return null
    }

    fun weakTerms(s: String): List<String> {
        val low = s.lowercase()
        if (WEAK.none { low.contains(it) }) return emptyList()
        val toks = tokens(s).toSet()
        return WEAK.filter { it in toks }
    }

    /** Number of cheat-client signatures the scanner knows by name. */
    val signatureCount: Int get() = STRONG_LONG.size + STRONG_SHORT.size + PHRASES.size

    fun hasEnabledTrue(line: String) = ENABLED_TRUE.containsMatchIn(line)
    fun isContextWord(t: String) = t in CONTEXT_WORDS
}

/** Everything read from inside one archive. */
internal class XRay(val fileName: String) {
    val ids = LinkedHashSet<String>()
    val names = LinkedHashSet<String>()
    val versions = LinkedHashSet<String>()
    val authors = LinkedHashSet<String>()
    val entrypoints = LinkedHashSet<String>()
    val mixins = LinkedHashSet<String>()
    var description = ""
    val packages = HashMap<String, Int>()
    val strong = LinkedHashSet<String>()
    val tags = LinkedHashSet<String>()
    val weak = LinkedHashSet<String>()
    val combat = LinkedHashSet<String>()
    var classCount = 0
    /** A server plugin (plugin.yml and friends), e.g. a Bukkit anti-cheat: its class names mention cheats without being one. */
    var serverPlugin = false
    var hasMeta = false
    var agent: String? = null
    var nested = 0
    var partial = false
}

internal class TextHit {
    val strong = LinkedHashSet<String>()
    val hard = LinkedHashSet<String>()
    val soft = LinkedHashSet<String>()
    val context = LinkedHashSet<String>()
    val lines = ArrayList<String>()
}

/**
 * Looks inside archives and text files by content, whatever their names are. Archives are opened
 * through their central directory; only capped amounts of each entry are ever decompressed, with a
 * global budget, so zip bombs cannot hurt. Nested jars are counted, not opened.
 */
internal object ArchiveXRay {
    private const val MAX_ENTRIES = 30_000
    private const val MAX_CLASSES = 600
    private const val CLASS_BYTES = 49_152
    private const val META_BYTES = 262_144

    private val STANDARD_PREFIXES = listOf(
        "net/minecraft/", "com/mojang/", "org/spongepowered/", "net/fabricmc/", "org/slf4j/", "com/google/", "io/netty/",
        "net/minecraftforge/", "net/neoforged/", "org/quiltmc/", "kotlin/", "kotlinx/", "org/jetbrains/", "org/intellij/",
        "org/apache/", "org/objectweb/", "org/lwjgl/", "javax/", "java/", "org/w3c/", "org/xml/", "META-INF/"
    )

    /** Mod ids that are well known and not reported as "unverified" (their contents are still checked for cheat signatures). */
    private val WHITELIST = setOf(
        "fabric-api", "fabricloader", "fabric-language-kotlin", "sodium", "lithium", "iris", "modmenu", "ferritecore",
        "starlight", "entityculling", "dynamic_fps", "indium", "immediatelyfast", "cloth-config", "cloth-config2",
        "architectury", "yet_another_config_lib_v3", "yacl", "optifine", "krypton", "lazydfu", "memoryleakfix",
        "noisium", "moreculling", "sodium-extra", "sodiumextra", "reeses-sodium-options", "lambdynlights", "betterf3",
        "appleskin", "journeymap", "jei", "emi", "roughlyenoughitems", "mousetweaks", "forgeconfigapiport",
        "fabric-permissions-api", "essential", "replaymod", "minecraft", "java"
    )

    fun isWhitelisted(ids: Collection<String>): Boolean =
        ids.isNotEmpty() && ids.all { it.lowercase() in WHITELIST }

    private fun isStandard(entryName: String) = STANDARD_PREFIXES.any { entryName.startsWith(it) }

    private fun prefixOf(entryName: String): String {
        val dir = entryName.substringBeforeLast('/', "")
        return dir.split('/').filter { it.isNotEmpty() }.take(3).joinToString("/")
    }

    // ---- archives -----------------------------------------------------------------------------

    /** Returns null when the file is not a readable ZIP/JAR. [scanStrings] decides, after the metadata is read, whether to read class strings. */
    fun inspect(file: File, displayName: String, budget: InflateBudget, maxMs: Long, scanStrings: (XRay) -> Boolean): XRay? {
        val x = XRay(displayName)
        val deadline = System.currentTimeMillis() + maxMs
        try {
            ZipFile(file).use { zip ->
                val classes = ArrayList<ZipEntry>()
                var n = 0
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (++n > MAX_ENTRIES) {
                        x.partial = true
                        break
                    }
                    if (e.isDirectory) continue
                    val name = e.name
                    val low = name.lowercase()
                    if (low == "plugin.yml" || low == "paper-plugin.yml" || low == "bungee.yml" || low == "velocity-plugin.json") x.serverPlugin = true
                    if (low.endsWith(".class")) {
                        x.classCount++
                        if (isStandard(name)) continue
                        if (classes.size < 6000) classes.add(e)
                        val pkg = prefixOf(name)
                        x.packages[pkg] = (x.packages[pkg] ?: 0) + 1
                        Terms.matchStrong(name)?.let {
                            x.strong.add(it)
                            x.tags.add("$it@path")
                        }
                        for (w in Terms.weakTerms(name)) x.weak.add(w)
                        continue
                    }
                    when {
                        low == "fabric.mod.json" -> readMeta(zip, e, budget) { parseFabric(it, x) }.also { x.hasMeta = true }
                        low == "quilt.mod.json" -> readMeta(zip, e, budget) { parseQuilt(it, x) }.also { x.hasMeta = true }
                        low == "meta-inf/mods.toml" || low == "meta-inf/neoforge.mods.toml" ->
                            readMeta(zip, e, budget) { parseToml(it, x) }.also { x.hasMeta = true }
                        low == "mcmod.info" -> readMeta(zip, e, budget) { parseMcmod(it, x) }.also { x.hasMeta = true }
                        low.endsWith("mixins.json") -> readMeta(zip, e, budget) { parseMixins(it, x) }
                        low == "meta-inf/manifest.mf" -> readMeta(zip, e, budget) { parseManifest(it, x) }
                        low.endsWith(".jar") -> x.nested++
                    }
                }
                applyMetaTerms(x)
                if (scanStrings(x)) deepScan(zip, classes, x, budget, deadline)
            }
        } catch (e: Exception) {
            return null
        }
        return x
    }

    private fun readCapped(zip: ZipFile, e: ZipEntry, cap: Int, budget: InflateBudget): ByteArray? {
        if (!budget.take(0)) return null
        return try {
            zip.getInputStream(e).use { ins ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (total < cap) {
                    val r = ins.read(buf, 0, minOf(buf.size, cap - total))
                    if (r < 0) break
                    out.write(buf, 0, r)
                    total += r
                }
                budget.take(total)
                out.toByteArray()
            }
        } catch (ex: Exception) {
            null
        }
    }

    private fun readMeta(zip: ZipFile, e: ZipEntry, budget: InflateBudget, parse: (String) -> Unit) {
        val bytes = readCapped(zip, e, META_BYTES, budget) ?: return
        try {
            parse(String(bytes, Charsets.UTF_8))
        } catch (ex: Exception) {
            // malformed metadata is itself worth nothing more than being skipped
        }
    }

    private fun add(set: MutableSet<String>, v: String?) {
        val t = v?.trim()
        if (!t.isNullOrEmpty() && t != "null") set.add(t.take(160))
    }

    private fun addAny(set: MutableSet<String>, v: Any?) {
        when (v) {
            is JSONArray -> for (i in 0 until v.length()) addAny(set, v.opt(i))
            is JSONObject -> add(set, if (v.has("value")) v.optString("value") else v.optString("name"))
            null -> {}
            else -> add(set, v.toString())
        }
    }

    private fun parseFabric(text: String, x: XRay) {
        val j = JSONObject(text)
        add(x.ids, j.optString("id"))
        add(x.names, j.optString("name"))
        add(x.versions, j.optString("version"))
        if (x.description.isEmpty()) x.description = j.optString("description").take(200)
        addAny(x.authors, j.opt("authors"))
        j.optJSONObject("entrypoints")?.let { ep ->
            val keys = ep.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val tmp = LinkedHashSet<String>()
                addAny(tmp, ep.opt(k))
                for (v in tmp) x.entrypoints.add("$k=$v")
            }
        }
        val mx = j.opt("mixins")
        if (mx is JSONArray) {
            for (i in 0 until mx.length()) {
                val m = mx.opt(i)
                add(x.mixins, if (m is JSONObject) m.optString("config") else m?.toString())
            }
        }
    }

    private fun parseQuilt(text: String, x: XRay) {
        val j = JSONObject(text)
        val ql = j.optJSONObject("quilt_loader") ?: return
        add(x.ids, ql.optString("id"))
        add(x.versions, ql.optString("version"))
        val md = ql.optJSONObject("metadata")
        if (md != null) {
            add(x.names, md.optString("name"))
            if (x.description.isEmpty()) x.description = md.optString("description").take(200)
            md.optJSONObject("contributors")?.let { c ->
                val keys = c.keys()
                while (keys.hasNext()) add(x.authors, keys.next())
            }
        }
        ql.optJSONObject("entrypoints")?.let { ep ->
            val keys = ep.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val tmp = LinkedHashSet<String>()
                addAny(tmp, ep.opt(k))
                for (v in tmp) x.entrypoints.add("$k=$v")
            }
        }
        addAny(x.mixins, j.opt("mixin"))
    }

    private val TOML_LINE = Regex("^\\s*(modId|displayName|version|authors|description)\\s*=\\s*[\"']*(.*?)[\"']*\\s*$")

    private fun parseToml(text: String, x: XRay) {
        for (line in text.lineSequence()) {
            val m = TOML_LINE.matchEntire(line) ?: continue
            val v = m.groupValues[2]
            when (m.groupValues[1]) {
                "modId" -> add(x.ids, v)
                "displayName" -> add(x.names, v)
                "version" -> add(x.versions, v)
                "authors" -> add(x.authors, v)
                "description" -> if (x.description.isEmpty()) x.description = v.take(200)
            }
        }
    }

    private fun parseMcmod(text: String, x: XRay) {
        val t = text.trim()
        val arr: JSONArray? = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("modList")
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(x.ids, o.optString("modid"))
            add(x.names, o.optString("name"))
            add(x.versions, o.optString("version"))
            if (x.description.isEmpty()) x.description = o.optString("description").take(200)
            addAny(x.authors, o.opt("authorList"))
            addAny(x.authors, o.opt("authors"))
        }
    }

    private fun parseMixins(text: String, x: XRay) {
        val j = JSONObject(text)
        val pkg = j.optString("package")
        val names = ArrayList<String>()
        for (k in listOf("mixins", "client", "server")) {
            val a = j.optJSONArray(k) ?: continue
            for (i in 0 until a.length()) names.add(a.optString(i))
        }
        add(x.mixins, pkg + names.take(5).joinToString(", ", " [", "]"))
    }

    private fun parseManifest(text: String, x: XRay) {
        for (line in text.lineSequence()) {
            for (k in listOf("Premain-Class:", "Agent-Class:", "Launcher-Agent-Class:")) {
                if (line.startsWith(k, ignoreCase = true)) {
                    x.agent = line.substring(k.length).trim().take(80)
                }
            }
        }
    }

    private fun applyMetaTerms(x: XRay) {
        val values = ArrayList<String>()
        values.addAll(x.ids)
        values.addAll(x.names)
        values.addAll(x.authors)
        values.addAll(x.entrypoints)
        values.addAll(x.mixins)
        values.add(x.description)
        for (v in values) {
            if (v.isEmpty()) continue
            Terms.matchStrong(v)?.let {
                x.strong.add(it)
                x.tags.add("$it@meta")
            }
            for (w in Terms.weakTerms(v)) x.weak.add(w)
        }
    }

    private fun printableRuns(b: ByteArray): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (byte in b) {
            val v = byte.toInt() and 0xFF
            if (v in 0x20..0x7E) {
                sb.append(v.toChar())
            } else {
                if (sb.length >= 6) out.add(sb.toString())
                sb.setLength(0)
                if (out.size >= 2000) return out
            }
        }
        if (sb.length >= 6) out.add(sb.toString())
        return out
    }

    private fun deepScan(zip: ZipFile, classes: List<ZipEntry>, x: XRay, budget: InflateBudget, deadline: Long) {
        var scanned = 0
        for (e in classes) {
            if (scanned >= MAX_CLASSES || System.currentTimeMillis() > deadline || budget.left <= 0) {
                x.partial = true
                break
            }
            val bytes = readCapped(zip, e, CLASS_BYTES, budget) ?: continue
            scanned++
            for (str in printableRuns(bytes)) {
                for (c in Terms.COMBAT) if (str.contains(c)) x.combat.add(c)
                Terms.matchStrong(str)?.let {
                    x.strong.add(it)
                    x.tags.add("$it@strings")
                }
                for (w in Terms.weakTerms(str)) x.weak.add(w)
            }
        }
    }

    /** One-line breakdown in the format the dashboard shows. */
    fun describe(x: XRay): String {
        fun join(c: Collection<String>, max: Int) = c.joinToString(", ").take(max).ifEmpty { "-" }
        val pk = x.packages.entries.sortedByDescending { it.value }.take(4)
            .joinToString(", ") { "${it.key}(${it.value})" }.take(100).ifEmpty { "-" }
        val ind = LinkedHashSet<String>()
        ind.addAll(x.tags)
        for (w in x.weak) ind.add("$w(weak)")
        ind.addAll(x.combat)
        x.agent?.let { ind.add("java-agent:$it") }
        if (x.nested > 0) ind.add("nested-jars:${x.nested}(not opened)")
        if (x.partial) ind.add("partly-inspected")
        val sb = StringBuilder()
        sb.append("File: ").append(x.fileName)
        sb.append(" | Internal ID: ").append(join(x.ids, 40))
        sb.append(" | Name: ").append(join(x.names, 40))
        sb.append(" | Authors: ").append(join(x.authors, 50))
        sb.append(" | Entrypoints: ").append(join(x.entrypoints, 90))
        sb.append(" | Custom Packages: ").append(pk)
        sb.append(" | Indicators: ").append(join(ind, 150))
        sb.append(" | Version: ").append(join(x.versions, 24))
        sb.append(" | Mixins: ").append(join(x.mixins, 80))
        sb.append(" | Description: ").append(x.description.take(100).ifEmpty { "-" })
        return sb.toString().take(1000)
    }

    // ---- text and config files ----------------------------------------------------------------

    fun looksLikeText(b: ByteArray): Boolean {
        if (b.isEmpty()) return false
        val n = minOf(b.size, 4096)
        var bad = 0
        for (i in 0 until n) {
            val v = b[i].toInt() and 0xFF
            if (v == 0) return false
            if (v < 9 || v in 14..31) bad++
        }
        return bad * 20 < n
    }

    fun analyzeText(text: String): TextHit {
        val hit = TextHit()
        var count = 0
        for (raw in text.lineSequence()) {
            if (++count > 20_000) break
            val line = if (raw.length > 400) raw.substring(0, 400) else raw
            if (line.isBlank()) continue
            val strongKey = Terms.matchStrong(line)
            val toks = Terms.tokens(line).toSet()
            val hard = Terms.HARD.filter { it in toks }
            val soft = Terms.SOFT.filter { it in toks }
            var ctx = toks.filter { Terms.isContextWord(it) }
            if (Terms.hasEnabledTrue(line)) ctx = ctx + "enabled:true"
            if (strongKey != null) hit.strong.add(strongKey)
            hit.hard.addAll(hard)
            hit.soft.addAll(soft)
            hit.context.addAll(ctx)
            if ((strongKey != null || hard.isNotEmpty() || soft.isNotEmpty()) && hit.lines.size < 12) hit.lines.add(line)
        }
        return hit
    }

    private val SECRET_LINE = Regex("(password|passwd|token|secret|session|apikey|api_key|api-key|authorization|bearer|@[a-z0-9.-]+\\.[a-z]{2,})", RegexOption.IGNORE_CASE)
    private val LONG_RUN = Regex("[A-Za-z0-9+/=_-]{24,}")

    /** A readable excerpt of the matching lines, up to [max] characters; lines that look like credentials are left out. */
    fun snippet(lines: List<String>, max: Int = 200): String {
        val sb = StringBuilder()
        for (l in lines) {
            if (SECRET_LINE.containsMatchIn(l)) continue
            val t = LONG_RUN.replace(l.trim(), "…").take(90)
            if (t.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(" // ")
            sb.append(t)
            if (sb.length >= max) break
        }
        return sb.toString().take(max)
    }
}
