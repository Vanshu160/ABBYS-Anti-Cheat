package com.example.mobilescanner

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Config {
    /** Base URL of your ABBYS server. Must be https (cleartext is disabled in the manifest). */
    const val SERVER_URL = "https://abbys-anticheat.onrender.com"
}

/** Talks to server/server.js. Blocking: call from Dispatchers.IO. */
object ReportClient {
    private const val TIMEOUT_MS = 45_000 // Render free tier can take ~30-50s to wake from sleep

    sealed class Start {
        class Ok(val token: String, val expiresInSeconds: Long) : Start()
        object Rejected : Start() // wrong, used or expired PIN
        object Unreachable : Start()
    }

    enum class Submit { SENT, SESSION_EXPIRED, FAILED }

    /** Enters the PIN once. The server starts a 15-minute session and returns a token; the PIN is not needed again. */
    fun startSession(pin: String): Start {
        if (!pin.matches(Regex("\\d{6}"))) return Start.Rejected
        return try {
            val c = open("/api/start-session/$pin", "POST")
            when (c.responseCode) {
                200 -> {
                    val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    Start.Ok(j.getString("playerToken"), j.getLong("expiresInSeconds"))
                }
                404 -> Start.Rejected
                else -> Start.Unreachable
            }
        } catch (e: Exception) {
            Start.Unreachable
        }
    }

    /** Uploads one report (findings plus the verdict computed from them) with the session token. May be called any number of times until the session expires. */
    fun submit(token: String, findings: List<ScanFinding>, verdict: Verdict): Submit {
        return try {
            val arr = JSONArray()
            findings.forEach {
                arr.put(JSONObject().put("severity", it.severity).put("title", it.title).put("detail", it.detail))
            }
            val body = JSONObject().put("report", arr).put("verdict", verdict.toJson()).toString().toByteArray(Charsets.UTF_8)
            val c = open("/api/submit-report", "POST")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-player-token", token)
            c.outputStream.use { it.write(body) }
            when (c.responseCode) {
                200 -> Submit.SENT
                401 -> Submit.SESSION_EXPIRED
                else -> Submit.FAILED
            }
        } catch (e: Exception) {
            Submit.FAILED
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
