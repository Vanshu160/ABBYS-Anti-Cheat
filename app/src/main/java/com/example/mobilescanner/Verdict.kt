package com.example.mobilescanner

import org.json.JSONArray
import org.json.JSONObject

/** What kind of trace a finding is. Only some kinds can, alone or together, justify a cheat verdict. */
enum class SignalKind {
    /** A known cheat client's name in the code of a mod archive (class paths or class strings). Conclusive. */
    ARCHIVE_CODE,
    /** The name only in an archive's metadata (mod id, description, entrypoints). Weak alone. */
    ARCHIVE_META,
    /** A known client's name in a text/config file inside a game folder. Weak alone. */
    CONFIG_TEXT,
    /** Several independent kinds of trace for one client in one game folder. */
    CORRELATED
}

/** Structured evidence attached to the findings that can feed the verdict. Generic matches never carry one. */
data class CheatSignal(
    val kind: SignalKind,
    val client: String,
    val file: String,
    val evidence: String,
    val families: Int = 1
)

enum class VerdictLevel(val headline: String) {
    CHEAT_DETECTED("CHEAT DETECTED"),
    SUSPICIOUS_ACTIVITY("SUSPICIOUS ACTIVITY"),
    NO_CHEAT_DETECTED("NO CHEAT DETECTED")
}

data class Detection(val client: String, val file: String, val evidence: String, val confidence: String)

data class Verdict(
    val level: VerdictLevel,
    val summary: String,
    val detections: List<Detection>,
    val reasons: List<String>,
    val limits: List<String>
) {
    val headline: String get() = level.headline

    /** The verdict as the first line of the report, so every view of the report (and its copy) carries it. */
    fun asFinding(): ScanFinding {
        val sev = when (level) {
            VerdictLevel.CHEAT_DETECTED -> "HIGH"
            VerdictLevel.SUSPICIOUS_ACTIVITY -> "WARN"
            VerdictLevel.NO_CHEAT_DETECTED -> "INFO"
        }
        val lines = ArrayList<String>()
        lines.add(summary)
        detections.forEach { lines.add("${it.client} | ${it.file} | ${it.evidence} | confidence ${it.confidence}") }
        if (level != VerdictLevel.CHEAT_DETECTED) reasons.forEach { lines.add(it) }
        limits.forEach { lines.add("Limit: $it") }
        return ScanFinding(sev, "VERDICT: $headline", lines.joinToString(" || "))
    }

    fun toJson(): JSONObject = JSONObject()
        .put("level", level.name)
        .put("headline", headline)
        .put("summary", summary)
        .put("detections", JSONArray().also { arr ->
            detections.forEach {
                arr.put(JSONObject().put("client", it.client).put("file", it.file).put("evidence", it.evidence).put("confidence", it.confidence))
            }
        })
        .put("reasons", JSONArray(reasons))
        .put("limits", JSONArray(limits))
}

/**
 * Turns the scanner's findings into the final verdict.
 *
 * Root cause this replaces: the scan result was only a flat list of findings with a severity word, and nothing
 * ever decided what they add up to. HIGH is also used for root/hook indicators, so counting HIGH would flag
 * root as a cheat client, while counting nothing flagged a real cheat as "Scan complete".
 *
 * The verdict uses structured evidence (CheatSignal), never severity counts or finding titles:
 * - CHEAT DETECTED: a cheat client's code inside an archive (ARCHIVE_CODE), OR two different kinds of trace for the
 *   same client (for example a config file plus archive metadata), OR three independent evidence families
 *   (name, artifact, log, config) for one client.
 * - SUSPICIOUS ACTIVITY: any HIGH/WARN finding or single weak trace that does not reach the above (name matches,
 *   metadata-only matches, config-only matches, root/hook indicators, unverified mods).
 * - NO CHEAT DETECTED: nothing above WARN. Never worded as proof of a clean device.
 */
object VerdictEngine {

    fun evaluate(findings: List<ScanFinding>): Verdict {
        val detections = ArrayList<Detection>()
        val used = HashSet<CheatSignal>() // the original signals that were part of a detection

        // A finding naming several clients ("a/b") counts for each of them.
        val entries = findings.mapNotNull { it.signal }.flatMap { orig ->
            orig.client.split('/').filter { it.isNotBlank() }.map { c -> orig to orig.copy(client = c) }
        }
        for ((_, group) in entries.groupBy { it.second.client.lowercase() }) {
            val sigs = group.map { it.second }
            val code = sigs.filter { it.kind == SignalKind.ARCHIVE_CODE }
            val kinds = sigs.map { it.kind }.filter { it != SignalKind.CORRELATED }.toSet()
            val strongCorrelation = sigs.firstOrNull { it.kind == SignalKind.CORRELATED && it.families >= 3 }
            when {
                code.isNotEmpty() -> {
                    code.forEach { detections.add(Detection(it.client, it.file, it.evidence, "HIGH")) }
                    used.addAll(group.map { it.first })
                }
                kinds.size >= 2 -> {
                    sigs.filter { it.kind != SignalKind.CORRELATED }.forEach {
                        detections.add(Detection(it.client, it.file, it.evidence, "MEDIUM-HIGH"))
                    }
                    used.addAll(group.map { it.first })
                }
                strongCorrelation != null -> {
                    detections.add(Detection(strongCorrelation.client, strongCorrelation.file, strongCorrelation.evidence, "MEDIUM-HIGH"))
                    used.addAll(group.map { it.first })
                }
            }
        }

        val limits = limitsOf(findings)

        if (detections.isNotEmpty()) {
            val clients = detections.map { it.client }.distinct()
            return Verdict(
                VerdictLevel.CHEAT_DETECTED,
                "Evidence of ${clients.joinToString(", ")} was found. An admin should review the details below.",
                detections.take(10), emptyList(), limits
            )
        }

        val open = findings.filter { it.severity == "HIGH" || it.severity == "WARN" || (it.signal != null && it.signal !in used) }
        if (open.isNotEmpty()) {
            val reasons = open.sortedBy { if (it.severity == "HIGH") 0 else 1 }.take(5).map { "[${it.severity}] ${it.title}" }
            return Verdict(
                VerdictLevel.SUSPICIOUS_ACTIVITY,
                "${open.size} indicator(s) need a look, but none is conclusive evidence of a cheat client.",
                emptyList(), reasons, limits
            )
        }

        return Verdict(
            VerdictLevel.NO_CHEAT_DETECTED,
            "No cheat-related evidence was found in what ABYSS could read. This is not a guarantee that the device is clean.",
            emptyList(), emptyList(), limits
        )
    }

    /** Honest coverage notes taken from the scan's own status findings. */
    private fun limitsOf(findings: List<ScanFinding>): List<String> {
        val out = ArrayList<String>()
        findings.firstOrNull { it.title == "Launcher scan status" }?.let {
            if (it.detail.contains("LOCKED")) out.add("Android blocked a launcher's Android/data folder; pick that folder manually to include it.")
            if (it.detail.contains("not checked")) out.add("All Files Access was missing, so some folders were not checked.")
        }
        findings.firstOrNull { it.title == "Minecraft scan coverage" }?.let {
            if (it.detail.contains("Analyzed 0 Java game folder(s) and 0 Bedrock")) out.add("No Minecraft game folder was found or scanned.")
            if (it.detail.contains("Stopped early")) out.add("The scan stopped early (time or folder limit).")
        }
        out.add("Other apps' private folders and permanently deleted files cannot be read without root.")
        return out
    }
}
