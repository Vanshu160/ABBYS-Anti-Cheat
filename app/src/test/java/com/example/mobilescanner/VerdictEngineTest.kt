package com.example.mobilescanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictEngineTest {
    private fun sig(kind: SignalKind, client: String = "wurst", file: String = "mods/a.jar", families: Int = 1) =
        CheatSignal(kind, client, file, "$client@test", families)

    private fun archiveCode(client: String = "wurst", file: String = "mods/a.jar") =
        ScanFinding("HIGH", "Archive contents match cheat client signatures ($client)", "x", sig(SignalKind.ARCHIVE_CODE, client, file))

    private fun archiveMeta(client: String = "wurst") =
        ScanFinding("WARN", "Archive contents match cheat client signatures ($client)", "metadata only", sig(SignalKind.ARCHIVE_META, client, "mods/b.jar"))

    private fun config(client: String = "wurst") =
        ScanFinding("HIGH", "Text/config file names a cheat client ($client)", "x", sig(SignalKind.CONFIG_TEXT, client, "config/c.json"))

    private fun corr(families: Int, client: String = "wurst") =
        ScanFinding("WARN", "Several independent traces of \"$client\"", "x", sig(SignalKind.CORRELATED, client, "/games/mc", families))

    // ---- confirmed cheat ----------------------------------------------------------------------

    @Test fun clientCodeInsideAnArchiveIsCheatDetected() {
        val v = VerdictEngine.evaluate(listOf(ScanFinding("INFO", "Launcher scan status", "ok"), archiveCode()))
        assertEquals(VerdictLevel.CHEAT_DETECTED, v.level)
        assertEquals("CHEAT DETECTED", v.headline)
        assertEquals(1, v.detections.size)
        val d = v.detections[0]
        assertEquals("wurst", d.client)
        assertEquals("mods/a.jar", d.file)
        assertEquals("HIGH", d.confidence)
        assertTrue(d.evidence.isNotEmpty())
    }

    @Test fun twoDifferentKindsOfTraceForOneClientIsCheatDetected() {
        val v = VerdictEngine.evaluate(listOf(config("liquidbounce"), archiveMeta("liquidbounce")))
        assertEquals(VerdictLevel.CHEAT_DETECTED, v.level)
        assertEquals("MEDIUM-HIGH", v.detections[0].confidence)
    }

    @Test fun threeIndependentEvidenceFamiliesIsCheatDetected() {
        assertEquals(VerdictLevel.CHEAT_DETECTED, VerdictEngine.evaluate(listOf(corr(3))).level)
    }

    @Test fun aFindingNamingSeveralClientsCountsForEach() {
        val both = ScanFinding("HIGH", "t", "d", CheatSignal(SignalKind.ARCHIVE_CODE, "wurst/vape", "mods/x.jar", "ev"))
        val v = VerdictEngine.evaluate(listOf(both))
        assertEquals(setOf("wurst", "vape"), v.detections.map { it.client }.toSet())
    }

    @Test fun verdictIsTheFirstLineOfTheReportAndIsRedHigh() {
        val f = VerdictEngine.evaluate(listOf(archiveCode())).asFinding()
        assertEquals("HIGH", f.severity)
        assertEquals("VERDICT: CHEAT DETECTED", f.title)
        assertTrue(f.detail.contains("wurst"))
        assertTrue(f.detail.contains("mods/a.jar"))
        assertTrue(f.detail.contains("confidence HIGH"))
    }

    // ---- suspicious only ----------------------------------------------------------------------

    @Test fun nameMatchesAloneAreSuspiciousNeverCheatDetected() {
        val v = VerdictEngine.evaluate(listOf(
            ScanFinding("WARN", "File name resembles a Minecraft cheat client", "Name match only"),
            ScanFinding("WARN", "App name resembles a known cheat/root tool", "Name match only")
        ))
        assertEquals(VerdictLevel.SUSPICIOUS_ACTIVITY, v.level)
        assertTrue(v.detections.isEmpty())
        assertTrue(v.reasons.isNotEmpty())
    }

    @Test fun rootIndicatorsHighSeverityAreSuspiciousNotACheatClient() {
        val v = VerdictEngine.evaluate(listOf(ScanFinding("HIGH", "Root binary found", "su in /system/bin")))
        assertEquals(VerdictLevel.SUSPICIOUS_ACTIVITY, v.level)
    }

    @Test fun manyHighFindingsWithoutSignalsNeverMakeACheatVerdict() {
        val v = VerdictEngine.evaluate(List(40) { ScanFinding("HIGH", "h$it", "d") })
        assertEquals(VerdictLevel.SUSPICIOUS_ACTIVITY, v.level)
    }

    @Test fun metadataOnlyMatchIsSuspiciousNotCheatDetected() {
        assertEquals(VerdictLevel.SUSPICIOUS_ACTIVITY, VerdictEngine.evaluate(listOf(archiveMeta())).level)
    }

    @Test fun configTextAloneIsSuspicious() {
        assertEquals(VerdictLevel.SUSPICIOUS_ACTIVITY, VerdictEngine.evaluate(listOf(config())).level)
    }

    @Test fun twoFamiliesOnlyIsSuspicious() {
        assertEquals(VerdictLevel.SUSPICIOUS_ACTIVITY, VerdictEngine.evaluate(listOf(corr(2))).level)
    }

    @Test fun weakTracesForDifferentClientsAreNotCombined() {
        val v = VerdictEngine.evaluate(listOf(config("wurst"), archiveMeta("vape")))
        assertEquals(VerdictLevel.SUSPICIOUS_ACTIVITY, v.level)
    }

    @Test fun suspiciousVerdictIsAmberWarn() {
        val f = VerdictEngine.evaluate(listOf(config())).asFinding()
        assertEquals("WARN", f.severity)
        assertEquals("VERDICT: SUSPICIOUS ACTIVITY", f.title)
    }

    // ---- no detection -------------------------------------------------------------------------

    @Test fun onlyInfoAndCleanFindingsIsNoCheatDetected() {
        val v = VerdictEngine.evaluate(listOf(
            ScanFinding("INFO", "App not from a major store", "x"),
            ScanFinding("INFO", "Accessibility service enabled", "x"),
            ScanFinding("CLEAN", "Basic native checks found nothing", "x")
        ))
        assertEquals(VerdictLevel.NO_CHEAT_DETECTED, v.level)
        assertEquals("NO CHEAT DETECTED", v.headline)
        assertTrue(v.detections.isEmpty())
    }

    @Test fun noCheatDetectedIsNeverWordedAsGuaranteedClean() {
        val v = VerdictEngine.evaluate(emptyList())
        assertEquals(VerdictLevel.NO_CHEAT_DETECTED, v.level)
        assertTrue(v.summary.contains("not a guarantee"))
        assertFalse(v.headline.contains("CLEAN"))
        assertTrue(v.limits.any { it.contains("private folders") })
        assertEquals("INFO", v.asFinding().severity)
    }

    @Test fun coverageGapsAreReportedAsLimits() {
        val v = VerdictEngine.evaluate(listOf(
            ScanFinding("INFO", "Launcher scan status", "Zalith: installed | Android/data: LOCKED by Android scoped storage"),
            ScanFinding("INFO", "Minecraft scan coverage", "Analyzed 0 Java game folder(s) and 0 Bedrock folder(s). ")
        ))
        assertEquals(VerdictLevel.NO_CHEAT_DETECTED, v.level)
        assertTrue(v.limits.any { it.contains("Android/data") })
        assertTrue(v.limits.any { it.contains("No Minecraft game folder") })
    }

    // ---- report payload -----------------------------------------------------------------------

    @Test fun reportJsonCarriesTheSameVerdict() {
        val v = VerdictEngine.evaluate(listOf(archiveCode()))
        val j = v.toJson()
        assertEquals("CHEAT_DETECTED", j.getString("level"))
        assertEquals("CHEAT DETECTED", j.getString("headline"))
        val d = j.getJSONArray("detections").getJSONObject(0)
        assertEquals("wurst", d.getString("client"))
        assertEquals("mods/a.jar", d.getString("file"))
        assertEquals("HIGH", d.getString("confidence"))
    }

    @Test fun findingsWithoutASignalHaveNone() {
        assertNull(ScanFinding("WARN", "t", "d").signal)
    }

    @Test fun unidentifiedCheatModuleSetInCodeIsCheatDetected() {
        val f = ScanFinding(
            "HIGH", "Archive code contains a set of cheat modules (criticals, nofall, bhop)", "x",
            CheatSignal(SignalKind.ARCHIVE_CODE, "unidentified cheat client", "mods/renamed.png", "modules in code: criticals, nofall, bhop")
        )
        val v = VerdictEngine.evaluate(listOf(f))
        assertEquals(VerdictLevel.CHEAT_DETECTED, v.level)
        assertEquals("unidentified cheat client", v.detections[0].client)
        assertEquals("mods/renamed.png", v.detections[0].file)
        assertEquals("HIGH", v.detections[0].confidence)
    }
}
