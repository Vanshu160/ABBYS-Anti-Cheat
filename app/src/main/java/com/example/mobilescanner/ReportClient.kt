package com.example.mobilescanner

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Config {
    /** Base URL of your ABBYS server. Must be https (cleartext is disabled in the manifest). */
    const val SERVER_URL = "https://YOUR-SERVER.example"
}

/** Talks to server/server.js. Blocking: call from Dispatchers.IO. */
object ReportClient {
    private const val TIMEOUT_MS = 10_000

    /** True only if the server says this PIN exists, is unused and has not expired. */
    fun verifyPin(pin: String): Boolean {
        if (!pin.matches(Regex("\\d{6}"))) return false
        return try {
            val c = open("/api/verify-pin/$pin", "GET")
            c.responseCode == 200
        } catch (e: Exception) {
            false
        }
    }

    fun submit(pin: String, findings: List<ScanFinding>): Boolean {
        return try {
            val arr = JSONArray()
            findings.forEach {
                arr.put(JSONObject().put("severity", it.severity).put("title", it.title).put("detail", it.detail))
            }
            val body = JSONObject().put("report", arr).toString().toByteArray(Charsets.UTF_8)
            val c = open("/api/submit-report/$pin", "POST")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body) }
            c.responseCode == 200
        } catch (e: Exception) {
            false
        }
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val c = URL(Config.SERVER_URL + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        return c
    }
}
