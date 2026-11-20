package com.example.mobilescanner

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** An authenticated session. Only the server-issued token and the expiry are kept; the PIN itself is never stored. */
data class Session(val token: String, val expiresAtMs: Long) {
    fun isActive(nowMs: Long): Boolean = nowMs < expiresAtMs
    fun remainingMs(nowMs: Long): Long = (expiresAtMs - nowMs).coerceAtLeast(0L)
}

/** Where the session lives. Kept tiny so the session rules can be unit tested without Android. */
interface SessionStorage {
    fun read(): Session?
    fun write(session: Session)
    fun clear()
}

/**
 * The 15-minute PIN session rules (pure Kotlin, covered by SessionRepositoryTest):
 * - started once, when the server accepts the PIN; the expiry is fixed at that moment
 * - never extended by later scans or by reading it
 * - survives app restarts and backgrounding (it is read back from storage) until it expires
 * - an expired session is wiped from storage when it is next looked at
 */
class SessionRepository(private val storage: SessionStorage, private val clock: () -> Long = System::currentTimeMillis) {

    fun current(): Session? {
        val s = storage.read() ?: return null
        return if (s.isActive(clock())) s else { storage.clear(); null }
    }

    /** Called once after the server accepted the PIN. The server's lifetime is capped at 15 minutes here too. */
    fun start(token: String, expiresInSeconds: Long): Session {
        val seconds = expiresInSeconds.coerceIn(1L, DURATION_MS / 1000)
        val session = Session(token, clock() + seconds * 1000)
        storage.write(session)
        return session
    }

    fun end() = storage.clear()

    companion object {
        const val DURATION_MS = 15 * 60 * 1000L
    }
}

/**
 * Session storage encrypted with a key held in the Android Keystore. If the keystore or the file is
 * unusable for any reason, the session is simply treated as absent and the PIN is asked for again.
 */
class EncryptedSessionStorage(context: Context) : SessionStorage {
    private val appContext = context.applicationContext

    private fun prefs(): SharedPreferences? = try {
        val key = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            appContext, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        null
    }

    override fun read(): Session? = try {
        val p = prefs()
        val token = p?.getString(K_TOKEN, null)
        val expires = p?.getLong(K_EXPIRES, 0L) ?: 0L
        if (token.isNullOrEmpty() || expires <= 0L) null else Session(token, expires)
    } catch (e: Exception) {
        null
    }

    override fun write(session: Session) {
        try {
            prefs()?.edit()?.putString(K_TOKEN, session.token)?.putLong(K_EXPIRES, session.expiresAtMs)?.apply()
        } catch (e: Exception) {
            // session simply will not survive a restart
        }
    }

    override fun clear() {
        try {
            prefs()?.edit()?.clear()?.apply()
        } catch (e: Exception) {
        }
    }

    private companion object {
        const val FILE = "abyss_session"
        const val K_TOKEN = "t"
        const val K_EXPIRES = "e"
    }
}
