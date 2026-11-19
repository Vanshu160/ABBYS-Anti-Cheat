package com.example.mobilescanner

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

/** Where findings go; implemented by the storage scanner and by this module. */
internal interface FindingSink {
    fun flag(key: String, f: ScanFinding)
}

/** One file or folder, either a real file or a document inside a folder tree the player picked. */
internal interface McEntry {
    val name: String
    val path: String
    val isDir: Boolean
    val modified: Long
    val size: Long
    fun children(): List<McEntry>?
    fun readTail(maxBytes: Int): ByteArray?
    fun asFile(): File?
}

private fun isLink(f: File): Boolean = try {
    Files.isSymbolicLink(f.toPath())
} catch (e: Exception) {
    false
}

private class FileEntry(private val f: File) : McEntry {
    override val name: String get() = f.name
    override val path: String get() = f.path
    override val isDir: Boolean get() = f.isDirectory
    override val modified: Long get() = f.lastModified()
    override val size: Long get() = f.length()

    private var loaded = false
    private var kids: List<McEntry>? = null

    override fun children(): List<McEntry>? {
        if (!loaded) {
            loaded = true
            kids = try {
                f.listFiles()?.filter { !isLink(it) }?.map { FileEntry(it) }
            } catch (e: SecurityException) {
                null
            }
        }
        return kids
    }

    override fun readTail(maxBytes: Int): ByteArray? = try {
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val n = minOf(len, maxBytes.toLong()).toInt()
            raf.seek(len - n)
            val b = ByteArray(n)
            raf.readFully(b)
            b
        }
    } catch (e: Exception) {
        null
    }

    override fun asFile(): File? = f
}

private class SafEntry(
    private val context: Context,
    private val tree: Uri,
    private val docId: String,
    override val name: String,
    override val isDir: Boolean,
    override val modified: Long,
    override val size: Long,
    override val path: String
) : McEntry {
    private var loaded = false
    private var kids: List<McEntry>? = null

    override fun children(): List<McEntry>? {
        if (!loaded) {
            loaded = true
            kids = query()
        }
        return kids
    }

    private fun query(): List<McEntry>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cursor = try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null, null, null
            )
        } catch (e: Exception) {
            null
        }
        if (cursor == null) return null
        val out = ArrayList<McEntry>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val n = c.getString(1) ?: continue
                val dir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                val mod = if (c.isNull(3)) 0L else c.getLong(3)
                val sz = if (c.isNull(4)) 0L else c.getLong(4)
                out.add(SafEntry(context, tree, id, n, dir, mod, sz, "$path/$n"))
            }
        }
        return out
    }

    override fun readTail(maxBytes: Int): ByteArray? = try {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        context.contentResolver.openInputStream(uri)?.use { ins ->
            var toSkip = if (size > maxBytes) size - maxBytes else 0L
            while (toSkip > 0) {
                val s = ins.skip(toSkip)
                if (s <= 0) break
                toSkip -= s
            }
            val buf = ByteArrayOutputStream()
            val tmp = ByteArray(8192)
            while (buf.size() < 4 * 1024 * 1024) {
                val n = ins.read(tmp)
                if (n < 0) break
                buf.write(tmp, 0, n)
            }
            val all = buf.toByteArray()
            if (all.size > maxBytes) all.copyOfRange(all.size - maxBytes, all.size) else all
        }
    } catch (e: Exception) {
        null
    }

    override fun asFile(): File? = null
}

/**
 * Minecraft forensics for Java launchers (PojavLauncher, MojoLauncher, Zalith, FCL, anything with a
 * .minecraft folder) and Minecraft Bedrock. Read-only. Works on real folders (All Files Access) and on a
 * folder tree the player picks with the system folder picker, using the same rules for both.
 *
 * Evidence is grouped in independent families (NAME, ARTIFACT, LOG, CONFIG). One name match is only a
 * WARN that says "name match only"; HIGH is reserved for a mod archive that contains a known cheat
 * client's code; several independent families for the same client are reported together.
 * Account/token files (launcher_profiles.json, accounts) are never opened, and log lines are not sent.
 */
object MinecraftScanner {
    private const val MAX_FS_DEPTH = 5
    private const val MAX_SAF_DEPTH = 4
    private const val MAX_DIR_VISITS = 4000
    private const val TIME_BUDGET_MS = 60_000L
    private const val MAX_FLAGS = 150
    private const val MAX_HIGH = 30
    private const val LOG_LINES = 800
    private const val LOG_BYTES = 512 * 1024
    private const val RECENT_MS = 7L * 24 * 3600 * 1000
    private const val MAX_RECENT_LOGS = 4
    private const val MAX_JAR_ENTRIES = 20_000
    private const val MAX_JAR_BYTES = 300L * 1024 * 1024
    private const val MAX_DOWNLOAD_FLAGS = 40

    private val JAVA_LAUNCHERS = linkedMapOf(
        "net.kdt.pojavlaunch" to "PojavLauncher",
        "net.kdt.pojavlaunch.debug" to "PojavLauncher (debug)",
        "git.artdeell.mojo" to "MojoLauncher",
        "com.mojo.launcher" to "MojoLauncher",
        "com.movtery.zalithlauncher" to "Zalith Launcher",
        "com.tungsten.fcl" to "Fold Craft Launcher (FCL)"
    )
    private const val BEDROCK_PKG = "com.mojang.minecraftpe"
    private const val TOOLBOX_PKG = "io.mrarm.mctoolbox"
    private const val PLAY_STORE = "com.android.vending"

    private val ROOT_LAUNCHER_DIRS = listOf(
        "PojavLauncher", "ZalithLauncher", "MojoLauncher", "FCL", "Amethyst", "CraftLauncher", ".minecraft", "minecraft"
    )
    private val GAME_SUBDIRS = setOf("mods", "config", "logs", "resourcepacks", "saves", "libraries")
    private val DOWNLOAD_EXT = setOf("mcpack", "mcaddon", "mcworld", "jar", "so", "lua", "apk")

    // ---- name rules ---------------------------------------------------------------------------

    private class Hit(val key: String, val strong: Boolean, val term: String)

    /** Long, distinctive names: matched inside the letters-only lowercase name. */
    private val LONG_SUBSTR = listOf(
        "liquidbounce" to "liquidbounce", "meteorclient" to "meteor", "bleachhack" to "bleachhack",
        "rusherhack" to "rusherhack", "thunderhack" to "thunderhack", "aristois" to "aristois",
        "doomsday" to "doomsday", "autoclicker" to "autoclicker", "mathax" to "mathax",
        "wurstclient" to "wurst", "impactclient" to "impact", "futureclient" to "future", "coffeeclient" to "coffee"
    )

    /** Shorter names: matched inside a single word of the name. */
    private val TOKEN_SUBSTR = listOf(
        "killaura" to "killaura", "triggerbot" to "triggerbot", "chestesp" to "chestesp",
        "baritone" to "baritone", "freecam" to "freecam", "xray" to "xray"
    )

    /** Ordinary English words that are also client names; only a weak match unless "client"/"hack" is nearby. */
    private val WEAK = setOf(
        "meteor", "impact", "inertia", "future", "coffee", "reach", "velocity", "hitbox", "shoreline", "nametags", "nametag"
    )
    private val CLIENT_WORDS = setOf("client", "clients", "hack", "hacks", "cheat", "cheats")

    private fun hackMatch(name: String): Hit? {
        val lower = name.lowercase().replace("x-ray", "xray")
        val letters = lower.filter { it.isLetter() }
        for ((sub, key) in LONG_SUBSTR) {
            if (letters.contains(sub)) return Hit(key, true, sub)
        }
        val tokens = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        for ((sub, key) in TOKEN_SUBSTR) {
            if (tokens.any { it.contains(sub) }) return Hit(key, true, sub)
        }
        if ("wurst" in tokens) return Hit("wurst", true, "wurst")
        val weak = tokens.firstOrNull { it in WEAK }
        if (weak != null) {
            val key = if (weak == "nametag") "nametags" else weak
            return Hit(key, tokens.any { it in CLIENT_WORDS }, weak)
        }
        return null
    }

    /** Class-path prefixes inside a jar that belong to well-known cheat clients. */
    private val CLASS_MARKERS = listOf(
        Triple("net/ccbluex/liquidbounce", "liquidbounce", "LiquidBounce"),
        Triple("net/wurstclient", "wurst", "Wurst"),
        Triple("meteordevelopment/meteorclient", "meteor", "Meteor Client"),
        Triple("org/bleachhack", "bleachhack", "BleachHack"),
        Triple("me/ionar/salhack", "salhack", "SalHack"),
        Triple("org/kamiblue", "kamiblue", "KAMI Blue")
    )

    /** Text found inside a version .json that belongs to a cheat client. */
    private val JSON_MARKERS = listOf(
        "liquidbounce" to "liquidbounce", "net.ccbluex" to "liquidbounce", "wurstclient" to "wurst",
        "meteordevelopment" to "meteor", "bleachhack" to "bleachhack", "rusherhack" to "rusherhack",
        "aristois" to "aristois", "thunderhack" to "thunderhack", "mathax" to "mathax"
    )

    /** Text in a game log that names a cheat client. */
    private val LOG_MARKERS = listOf(
        "liquidbounce" to "liquidbounce", "wurst client" to "wurst", "wurstclient" to "wurst",
        "meteor client" to "meteor", "meteorclient" to "meteor", "meteordevelopment" to "meteor",
        "bleachhack" to "bleachhack", "rusherhack" to "rusherhack", "aristois" to "aristois",
        "thunderhack" to "thunderhack", "mathax" to "mathax", "doomsday client" to "doomsday",
        "impact client" to "impact", "future client" to "future", "baritone" to "baritone",
        "killaura" to "killaura", "triggerbot" to "triggerbot", "freecam" to "freecam", "xray" to "xray"
    )

    /** Common allowed mods, listed separately so an admin can check them against server rules. */
    private val KNOWN_OK = listOf(
        "sodium", "lithium", "iris", "optifine", "optifabric", "lunar", "feather", "badlion", "labymod",
        "fabric-api", "fabricapi", "ferritecore", "starlight", "dynamicfps", "entityculling", "modmenu",
        "replaymod", "essential"
    )

    private fun bedrockMatch(text: String): String? {
        val lower = text.lowercase().replace("x-ray", "xray")
        val tokens = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        if (tokens.any { it.contains("xray") }) return "X-Ray"
        if (tokens.any { it.startsWith("fullbright") } || ("full" in tokens && "bright" in tokens)) return "Fullbright"
        if ("nightvision" in tokens || ("night" in tokens && "vision" in tokens)) return "Night Vision"
        if ("nofog" in tokens || ("no" in tokens && "fog" in tokens)) return "No-Fog"
        if (tokens.any { it.startsWith("tracer") }) return "Tracers"
        if (tokens.any { it.startsWith("hitbox") }) return "Hitbox"
        if ("reach" in tokens) return "Reach"
        if ("radar" in tokens) return "Radar"
        if ("esp" in tokens) return "ESP"
        return null
    }

    // ---- bookkeeping --------------------------------------------------------------------------

    private class McSink : FindingSink {
        val flags = ArrayList<ScanFinding>()
        val high = ArrayList<ScanFinding>()
        val seen = HashSet<String>()
        var overflow = 0

        override fun flag(key: String, f: ScanFinding) {
            if (!seen.add(key)) return
            if (f.severity == "HIGH") {
                if (high.size < MAX_HIGH) high.add(f) else overflow++
            } else {
                if (flags.size < MAX_FLAGS) flags.add(f) else overflow++
            }
        }
    }

    private class Run(val sink: McSink, val deadline: Long) {
        var visits = 0
        var javaDirs = 0
        var bedrockDirs = 0
        val analyzed = LinkedHashSet<String>()
        fun expired() = System.currentTimeMillis() > deadline || visits > MAX_DIR_VISITS
    }

    /** Which independent kinds of evidence were seen for each client in one game folder. */
    private class Evidence {
        val m = HashMap<String, MutableSet<String>>()
        fun add(key: String, family: String) {
            m.getOrPut(key) { HashSet() }.add(family)
        }
    }

    private fun ts(ms: Long): String =
        if (ms <= 0L) "unknown" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    @Suppress("DEPRECATION")
    private fun storageBase(): File = Environment.getExternalStorageDirectory()

    private fun canon(f: File): String = try {
        f.canonicalPath
    } catch (e: Exception) {
        f.absolutePath
    }

    // ---- entry point --------------------------------------------------------------------------

    fun scan(context: Context, storageGameDirs: List<File>, safTree: Uri?): List<ScanFinding> {
        val sink = McSink()
        val run = Run(sink, System.currentTimeMillis() + TIME_BUDGET_MS)
        val installed = packageChecks(context, sink)

        val fsOk = hasAllFilesPermission(context)
        if (fsOk) {
            val base = storageBase()
            val candidates = LinkedHashMap<String, File>()
            val add = { f: File -> if (f.isDirectory) candidates.putIfAbsent(canon(f), f) }

            for ((pkg, label) in JAVA_LAUNCHERS) {
                if (pkg in installed) probeData(File(base, "Android/data/$pkg/files"), pkg, label, sink, add)
            }
            if (BEDROCK_PKG in installed) {
                probeData(
                    File(base, "Android/data/$BEDROCK_PKG/files/games/com.mojang"),
                    BEDROCK_PKG, "Minecraft Bedrock", sink, add
                )
            }
            File(base, "games").listFiles()?.filter { it.isDirectory }?.forEach { add(it) }
            add(File(base, "games/com.mojang"))
            for (n in ROOT_LAUNCHER_DIRS) add(File(base, n))
            for (g in storageGameDirs) add(g)

            for (d in candidates.values) {
                if (run.expired()) break
                locate(FileEntry(d), 0, MAX_FS_DEPTH, run)
            }
            downloads(sink)
        }

        if (safTree != null) scanTree(context, safTree, run)

        val out = ArrayList<ScanFinding>()
        out.addAll(sink.high)
        out.addAll(sink.flags)
        if (sink.overflow > 0) {
            out.add(ScanFinding("INFO", "More Minecraft findings omitted", "${sink.overflow} further matches were left out to keep the report a reasonable size."))
        }
        val where = run.analyzed.take(4).joinToString("; ") { it.takeLast(70) }
        out.add(
            ScanFinding(
                "INFO", "Minecraft scan coverage",
                "Analyzed ${run.javaDirs} Java game folder(s) and ${run.bedrockDirs} Bedrock folder(s)" +
                    (if (where.isNotEmpty()) " ($where)" else "") + ". " +
                    (if (!fsOk) "All Files Access is missing, so internal folders were skipped. " else "") +
                    (if (run.expired()) "Stopped early (time or folder limit). " else "") +
                    "Mod archives from a picked folder are checked by name only. Other apps' private folders stay hidden by Android unless picked."
            )
        )
        return out
    }

    private fun packageChecks(context: Context, sink: FindingSink): Set<String> {
        val installed = HashSet<String>()
        for ((pkg, label) in JAVA_LAUNCHERS) {
            if (isInstalled(context, pkg)) {
                installed.add(pkg)
                sink.flag("lp:$pkg", ScanFinding("INFO", "Minecraft launcher installed", "$label ($pkg)"))
            }
        }
        if (isInstalled(context, BEDROCK_PKG)) {
            installed.add(BEDROCK_PKG)
            val installer = installerOf(context, BEDROCK_PKG)
            if (installer == PLAY_STORE) {
                sink.flag("bp", ScanFinding("INFO", "Minecraft Bedrock installed", "Installed from Google Play."))
            } else {
                sink.flag(
                    "bp",
                    ScanFinding(
                        "WARN", "Minecraft Bedrock not installed from the Play Store",
                        "Installer: ${installer ?: "none / APK file"}. This can be a modified or pirated APK, or a legitimate other store (for example Amazon or Samsung)."
                    )
                )
            }
        }
        if (isInstalled(context, TOOLBOX_PKG)) {
            sink.flag(
                "tb",
                ScanFinding("WARN", "Toolbox for Minecraft installed", "$TOOLBOX_PKG is a Bedrock modding tool. Installed on its own this is not proof of cheating.")
            )
        }
        return installed
    }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun installerOf(context: Context, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(pkg)
        }
    } catch (e: Exception) {
        null
    }

    private fun probeData(dir: File, pkg: String, label: String, sink: FindingSink, add: (File) -> Any?) {
        val kids = try {
            dir.listFiles()
        } catch (e: SecurityException) {
            null
        }
        if (kids != null) {
            add(dir)
        } else {
            sink.flag(
                "pd:$pkg",
                ScanFinding(
                    "INFO", "$label data folder not readable",
                    "Android protects Android/data/$pkg on this device, so it was not inspected. Pick it with the folder button if Android allows it; folders under /games are still scanned."
                )
            )
        }
    }

    // ---- folder discovery ---------------------------------------------------------------------

    private fun locate(entry: McEntry, depth: Int, maxDepth: Int, run: Run) {
        if (run.expired()) return
        run.visits++
        val kids = entry.children() ?: return
        val dirs = kids.filter { it.isDir }
        val names = dirs.map { it.name.lowercase() }.toSet()
        val javaLike = entry.name.equals(".minecraft", true) || "mods" in names ||
            ("versions" in names && names.any { it in GAME_SUBDIRS })
        val bedrockLike = entry.name.equals("com.mojang", true) || "resource_packs" in names ||
            "behavior_packs" in names || "minecraftpe" in names
        if (javaLike || bedrockLike) {
            if (run.analyzed.add(entry.path)) {
                if (javaLike) {
                    run.javaDirs++
                    analyzeJava(entry, kids, run)
                }
                if (bedrockLike) {
                    run.bedrockDirs++
                    analyzeBedrock(entry, kids, run)
                }
            }
            return
        }
        if (depth >= maxDepth) return
        for (d in dirs) {
            if (run.expired()) return
            locate(d, depth + 1, maxDepth, run)
        }
    }

    private fun scanTree(context: Context, tree: Uri, run: Run) {
        val sink = run.sink
        val rootId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (e: Exception) {
            sink.flag("saf:bad", ScanFinding("INFO", "Selected Minecraft folder could not be used", "Android did not give usable access to that folder."))
            return
        }
        // A folder on internal storage gets the same path as the real file system, so it is not analyzed twice.
        val label = if (rootId.startsWith("primary:")) File(storageBase(), rootId.removePrefix("primary:")).path else rootId
        val root = SafEntry(context, tree, rootId, label.substringAfterLast('/'), true, 0L, 0L, label)
        val before = run.analyzed.size
        locate(root, 0, MAX_SAF_DEPTH, run)
        if (run.analyzed.size == before) {
            sink.flag(
                "saf:none",
                ScanFinding(
                    "INFO", "No Minecraft folder found in the selected folder",
                    "Searched $label to a depth of $MAX_SAF_DEPTH (or it was already scanned). Pick the .minecraft folder, or the com.mojang folder for Bedrock."
                )
            )
        }
    }

    // ---- Java launchers -----------------------------------------------------------------------

    private fun sub(kids: List<McEntry>, name: String): McEntry? =
        kids.firstOrNull { it.isDir && it.name.equals(name, true) }

    private fun analyzeJava(game: McEntry, kids: List<McEntry>, run: Run) {
        val sink = run.sink
        val ev = Evidence()
        val modKeys = HashSet<String>()
        val now = System.currentTimeMillis()

        // Mods
        val mods = sub(kids, "mods")
        val jars = ArrayList<McEntry>()
        if (mods != null) collectJars(mods, 0, jars)
        val others = ArrayList<String>()
        val known = ArrayList<String>()
        var newest = 0L
        for (j in jars) {
            if (run.expired()) break
            newest = maxOf(newest, j.modified)
            val hit = hackMatch(j.name)
            if (hit != null) {
                modKeys.add(hit.key)
                ev.add(hit.key, "NAME")
                if (hit.strong) {
                    sink.flag(
                        "mj:" + j.path,
                        ScanFinding(
                            "WARN", "Mod file name resembles a known cheat client",
                            "${j.path} matched \"${hit.term}\". Modified ${ts(j.modified)}. Name match only; verify before drawing conclusions."
                        )
                    )
                } else {
                    sink.flag(
                        "mj:" + j.path,
                        ScanFinding(
                            "INFO", "Mod file name contains a word some cheat clients use",
                            "${j.path} matched \"${hit.term}\". Modified ${ts(j.modified)}. Weak match: it is also an ordinary word (for example a harmless library)."
                        )
                    )
                }
            }
            j.asFile()?.let { inspectJar(it, sink, ev, modKeys) }
            if (hit == null) {
                val disp = j.name.removeSuffix(".disabled").removeSuffix(".jar")
                if (KNOWN_OK.any { disp.lowercase().contains(it) }) known.add(disp) else others.add(disp)
            }
        }
        if (mods != null) {
            sink.flag(
                "mf:" + game.path,
                ScanFinding("INFO", "Minecraft mods folder", "${mods.path}: ${jars.size} mod file(s); newest modified ${ts(newest)}.")
            )
            emitChunks(sink, "ml:" + game.path, "Known PvP/performance mods (check the server allows them)", mods.path, known)
            emitChunks(sink, "mo:" + game.path, "Other installed mods", mods.path, others)
        }

        // Versions
        val versions = sub(kids, "versions")?.children()?.filter { it.isDir }
        if (versions != null) {
            for (v in versions.take(200)) {
                if (run.expired()) break
                hackMatch(v.name)?.let { h ->
                    ev.add(h.key, "NAME")
                    sink.flag(
                        "vn:" + v.path,
                        ScanFinding(
                            if (h.strong) "WARN" else "INFO", "Game version name resembles a cheat client",
                            "${v.path} matched \"${h.term}\". Modified ${ts(v.modified)}. Name match only."
                        )
                    )
                }
                for (f in v.children() ?: emptyList()) {
                    if (f.isDir) continue
                    val lowerName = f.name.lowercase()
                    if (lowerName.endsWith(".json")) {
                        hackMatch(f.name)?.let { h -> ev.add(h.key, "NAME") }
                        val text = f.readTail(262_144)?.toString(Charsets.UTF_8)?.lowercase()
                        if (text != null) {
                            val marker = JSON_MARKERS.firstOrNull { text.contains(it.first) }
                            if (marker != null) {
                                ev.add(marker.second, "ARTIFACT")
                                sink.flag(
                                    "vj:" + f.path,
                                    ScanFinding(
                                        "WARN", "Version profile references cheat client code",
                                        "${f.path} mentions \"${marker.first}\". Modified ${ts(f.modified)}. Based on the file's text, not just its name."
                                    )
                                )
                            }
                            if (text.contains("-javaagent:")) {
                                sink.flag(
                                    "va:" + f.path,
                                    ScanFinding(
                                        "WARN", "Version profile starts a Java agent",
                                        "${f.path} contains -javaagent. Agents can be legitimate (authentication or profiling) but can also inject cheats."
                                    )
                                )
                            }
                        }
                    } else if (lowerName.endsWith(".jar")) {
                        f.asFile()?.let { inspectJar(it, sink, ev, modKeys) }
                    }
                }
            }
        }

        // Leftover configs of deleted clients
        sub(kids, "config")?.children()?.forEach { c ->
            val h = hackMatch(c.name.substringBeforeLast('.', c.name)) ?: return@forEach
            ev.add(h.key, "CONFIG")
            if (h.key !in modKeys) {
                sink.flag(
                    "cf:" + c.path,
                    ScanFinding(
                        if (h.strong) "WARN" else "INFO", "Config for a cheat client with no matching mod file",
                        "${c.path} matched \"${h.term}\". Modified ${ts(c.modified)}. The client may have been deleted; config files often outlast it. Name match only."
                    )
                )
            }
        }

        // Resource packs
        sub(kids, "resourcepacks")?.children()?.forEach { p ->
            if (hackMatch(p.name)?.key == "xray" || bedrockMatch(p.name) == "X-Ray") {
                ev.add("xray", "NAME")
                sink.flag(
                    "rp:" + p.path,
                    ScanFinding("WARN", "Resource pack name suggests X-ray", "${p.path}. Modified ${ts(p.modified)}. Name match only; some servers allow X-ray packs.")
                )
            }
        }

        // Logs
        val logFiles = ArrayList<McEntry>()
        sub(kids, "logs")?.children()?.let { lk ->
            lk.firstOrNull { !it.isDir && it.name.equals("latest.log", true) }?.let { logFiles.add(it) }
            lk.filter {
                !it.isDir && it.name.endsWith(".log", true) && !it.name.equals("latest.log", true) && now - it.modified <= RECENT_MS
            }.sortedByDescending { it.modified }.take(MAX_RECENT_LOGS).forEach { logFiles.add(it) }
        }
        kids.firstOrNull { !it.isDir && it.name.equals("latestlog.txt", true) }?.let { logFiles.add(it) }
        for (lf in logFiles) {
            if (run.expired()) break
            scanLog(lf, game.path, sink, ev)
        }

        emitCorrelation(game.path, ev, sink)
    }

    private fun collectJars(dir: McEntry, depth: Int, out: MutableList<McEntry>) {
        val kids = dir.children() ?: return
        for (k in kids) {
            if (out.size >= 1000) return
            val n = k.name.lowercase()
            if (k.isDir) {
                if (depth < 1) collectJars(k, depth + 1, out)
            } else if (n.endsWith(".jar") || n.endsWith(".jar.disabled")) {
                out.add(k)
            }
        }
    }

    /** Reads only the archive's file list (never inflates entries), so zip bombs cannot hurt. */
    private fun inspectJar(jar: File, sink: FindingSink, ev: Evidence, modKeys: MutableSet<String>) {
        if (jar.length() > MAX_JAR_BYTES) return
        try {
            ZipFile(jar).use { zip ->
                var n = 0
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    if (++n > MAX_JAR_ENTRIES) return@use
                    val entry = entries.nextElement().name.lowercase()
                    val hit = CLASS_MARKERS.firstOrNull { entry.startsWith(it.first) }
                    if (hit != null) {
                        modKeys.add(hit.second)
                        ev.add(hit.second, "ARTIFACT")
                        sink.flag(
                            "jh:" + jar.path,
                            ScanFinding(
                                "HIGH", "Archive contains a known cheat client's code",
                                "${jar.path} (modified ${ts(jar.lastModified())}) contains classes under \"${hit.first}\" (${hit.third}). Based on the archive's contents, not just its name; an admin should confirm."
                            )
                        )
                        return@use
                    }
                }
            }
        } catch (e: Exception) {
            // not a readable archive: nothing to add
        }
    }

    private fun scanLog(log: McEntry, gamePath: String, sink: FindingSink, ev: Evidence) {
        val bytes = log.readTail(LOG_BYTES) ?: return
        val lines = String(bytes, Charsets.UTF_8).split('\n').takeLast(LOG_LINES)
        for ((i, line) in lines.withIndex()) {
            val lower = line.lowercase()
            val agentAt = line.indexOf("-javaagent:", ignoreCase = true)
            if (agentAt >= 0) {
                val rest = line.substring(agentAt + "-javaagent:".length)
                val end = rest.indexOfFirst { it == ' ' || it == '"' || it == '=' || it == ',' || it == ']' }
                val token = (if (end < 0) rest else rest.substring(0, end)).substringAfterLast('/').substringAfterLast('\\').take(100)
                ev.add("javaagent", "LOG")
                sink.flag(
                    "la:$gamePath:$token",
                    ScanFinding(
                        "WARN", "Game log shows a Java agent",
                        "${log.path} (log line ${i + 1} of the last ${lines.size}): -javaagent:$token. Agents can be legitimate (for example authentication or profiling tools) but can also inject cheats."
                    )
                )
            }
            val marker = LOG_MARKERS.firstOrNull { lower.contains(it.first) } ?: continue
            val isMixin = lower.contains("mixin")
            ev.add(marker.second, "LOG")
            val kind = if (isMixin) "mixin" else "mention"
            sink.flag(
                "ll:$gamePath:${marker.second}:$kind",
                ScanFinding(
                    "WARN",
                    if (isMixin) "Game log shows a mixin from a cheat client (${marker.second})" else "Game log mentions a cheat client (${marker.second})",
                    "${log.path} (log line ${i + 1} of the last ${lines.size}, modified ${ts(log.modified)}). Matched \"${marker.first}\". " +
                        "A log can name a client without it having been used; the line itself is not sent."
                )
            )
        }
    }

    private fun emitCorrelation(gamePath: String, ev: Evidence, sink: FindingSink) {
        for ((key, families) in ev.m) {
            if (families.size >= 2) {
                sink.flag(
                    "ev:$gamePath:$key",
                    ScanFinding(
                        "WARN", "Several independent traces of \"$key\"",
                        "Under $gamePath: ${families.sorted().joinToString(", ")}. Traces from different places together are stronger than one name match; an admin should review them."
                    )
                )
            }
        }
    }

    private fun emitChunks(sink: FindingSink, key: String, title: String, where: String, items: List<String>) {
        if (items.isEmpty()) return
        val groups = ArrayList<ArrayList<String>>()
        var len = 0
        for (item in items) {
            if (groups.isEmpty() || len + item.length > 300) {
                groups.add(ArrayList())
                len = 0
            }
            groups.last().add(item)
            len += item.length + 2
        }
        val shown = minOf(groups.size, 6)
        for (i in 0 until shown) {
            val t = if (shown > 1) "$title (${i + 1}/$shown)" else title
            sink.flag("$key:$i", ScanFinding("INFO", t, "$where: ${groups[i].joinToString(", ")}"))
        }
        if (groups.size > shown) {
            val more = groups.drop(shown).sumOf { it.size }
            sink.flag("$key:more", ScanFinding("INFO", "$title (more)", "$more more not listed."))
        }
    }

    // ---- Bedrock ------------------------------------------------------------------------------

    private fun analyzeBedrock(root: McEntry, kids: List<McEntry>, run: Run) {
        val sink = run.sink
        val ev = Evidence()
        val packNames = HashMap<String, String>()

        for (folder in listOf("resource_packs", "behavior_packs", "development_resource_packs", "development_behavior_packs")) {
            val packs = sub(kids, folder)?.children()?.filter { it.isDir } ?: continue
            for (p in packs.take(300)) {
                if (run.expired()) return
                var pname = ""
                var desc = ""
                val manifest = p.children()?.firstOrNull { !it.isDir && it.name.equals("manifest.json", true) }
                val text = manifest?.readTail(65_536)?.toString(Charsets.UTF_8)
                if (text != null) {
                    try {
                        val header = JSONObject(text).optJSONObject("header")
                        pname = header?.optString("name") ?: ""
                        desc = header?.optString("description") ?: ""
                        val uuid = (header?.optString("uuid") ?: "").lowercase()
                        if (uuid.isNotEmpty()) packNames[uuid] = p.name + " " + pname
                    } catch (e: Exception) {
                        // unreadable manifest: fall back to the folder name
                    }
                }
                val label = bedrockMatch(p.name + " " + pname + " " + desc)
                if (label != null) {
                    ev.add(label.lowercase(), "NAME")
                    sink.flag(
                        "bk:" + p.path,
                        ScanFinding(
                            "WARN", "Bedrock pack name suggests $label",
                            "${p.path}${if (pname.isNotBlank()) " (named \"${pname.take(60)}\")" else ""}, modified ${ts(p.modified)}. Name match only; some servers allow packs like Night Vision or Fullbright."
                        )
                    )
                }
            }
        }

        val mc = sub(kids, "minecraftpe")?.children()
        val globalPacks = mc?.firstOrNull { !it.isDir && it.name.equals("global_resource_packs.json", true) }
        if (globalPacks != null) {
            val text = globalPacks.readTail(65_536)?.toString(Charsets.UTF_8)
            if (text != null) {
                try {
                    val arr = JSONArray(text)
                    var unknown = 0
                    for (i in 0 until arr.length()) {
                        val id = (arr.optJSONObject(i)?.optString("pack_id") ?: "").lowercase()
                        val nm = packNames[id]
                        if (nm == null) {
                            unknown++
                            continue
                        }
                        val label = bedrockMatch(nm) ?: continue
                        ev.add(label.lowercase(), "CONFIG")
                        sink.flag(
                            "bg:" + root.path + id,
                            ScanFinding(
                                "WARN", "Active global resource pack suggests $label",
                                "${globalPacks.path} activates a pack named \"${nm.take(80)}\" for every world. Name match only."
                            )
                        )
                    }
                    sink.flag(
                        "bgs:" + root.path,
                        ScanFinding("INFO", "Bedrock global resource packs", "${arr.length()} active; $unknown not found in the local pack folders (may be built in). Read from ${globalPacks.path}.")
                    )
                } catch (e: Exception) {
                    // not valid JSON: ignore
                }
            }
        }
        val options = mc?.firstOrNull { !it.isDir && it.name.equals("options.txt", true) }
        if (options != null) {
            val text = options.readTail(131_072)?.toString(Charsets.UTF_8)
            if (text != null) {
                val seenLabels = HashSet<String>()
                for (line in text.split('\n')) {
                    val key = line.substringBefore(':').trim()
                    // Only the setting name is checked and reported: values can hold personal data such as the player name.
                    val label = bedrockMatch(key.replace('_', ' ')) ?: continue
                    if (!seenLabels.add(label)) continue
                    ev.add(label.lowercase(), "CONFIG")
                    sink.flag(
                        "bo:" + root.path + label,
                        ScanFinding("WARN", "Bedrock options.txt has a setting named like $label", "${options.path}: setting \"${key.take(60)}\". Name match only.")
                    )
                }
            }
        }
        emitCorrelation(root.path, ev, sink)
    }

    // ---- Downloads ----------------------------------------------------------------------------

    private fun downloads(sink: FindingSink) {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(Pair(dir, 0))
        var count = 0
        var overflow = 0
        while (stack.isNotEmpty()) {
            val (d, depth) = stack.removeLast()
            val kids = try {
                d.listFiles()
            } catch (e: SecurityException) {
                null
            }
            if (kids == null) continue
            for (f in kids) {
                if (isLink(f)) continue
                if (f.isDirectory) {
                    if (depth < 3) stack.addLast(Pair(f, depth + 1))
                    continue
                }
                val ext = f.name.substringAfterLast('.', "").lowercase()
                if (ext !in DOWNLOAD_EXT) continue
                count++
                if (count > MAX_DOWNLOAD_FLAGS) {
                    overflow++
                    continue
                }
                val hit = hackMatch(f.name)
                val warn = ext == "so" || ext == "lua" || (hit != null && hit.strong)
                sink.flag(
                    "dl:" + f.path,
                    ScanFinding(
                        if (warn) "WARN" else "INFO", "File in Downloads: .$ext",
                        "${f.path} modified ${ts(f.lastModified())}, ${f.length() / 1024} KB." +
                            (if (hit != null) " Name matched \"${hit.term}\" (name match only)." else "") +
                            (if (ext == "so" || ext == "lua") " Native or script files here are unusual; not proof by itself." else "")
                    )
                )
            }
        }
        if (overflow > 0) {
            sink.flag("dl:more", ScanFinding("INFO", "More Downloads files omitted", "$overflow further Minecraft-related files were left out."))
        }
    }
}
