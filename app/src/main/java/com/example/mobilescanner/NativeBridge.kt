package com.example.mobilescanner

object NativeBridge {
    init {
        System.loadLibrary("abyssnative")
    }

    external fun runNativeChecks(): String

    fun getNativeFindings(): List<ScanFinding> {
        val raw = try {
            runNativeChecks()
        } catch (e: Throwable) {
            return listOf(ScanFinding("WARN", "Native Check Error", e.message ?: "JNI failed"))
        }

        if (raw == "CLEAN" || raw.isBlank()) {
            return listOf(ScanFinding("CLEAN", "C++ Kernel Integrity Passed", "No root mounts, Frida hooks, or debuggers found."))
        }

        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size == 3) ScanFinding(parts[0], parts[1], parts[2]) else null
        }
    }
}
