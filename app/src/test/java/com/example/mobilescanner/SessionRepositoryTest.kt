package com.example.mobilescanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryTest {
    private class Mem : SessionStorage {
        var s: Session? = null
        override fun read() = s
        override fun write(session: Session) { s = session }
        override fun clear() { s = null }
    }

    private var now = 1_000_000L
    private val minute = 60_000L

    @Test fun noSessionBeforeStart() {
        assertNull(SessionRepository(Mem()) { now }.current())
    }

    @Test fun lastsExactlyFifteenMinutes() {
        val repo = SessionRepository(Mem()) { now }
        val s = repo.start("tok", 900)
        assertEquals(now + 15 * minute, s.expiresAtMs)
        now += 15 * minute - 1
        assertNotNull(repo.current())
        now += 1
        assertNull(repo.current())
    }

    @Test fun repeatedScansDoNotExtendTheTimer() {
        val repo = SessionRepository(Mem()) { now }
        val first = repo.start("tok", 900)
        repeat(20) { now += 30_000; assertEquals(first.expiresAtMs, repo.current()!!.expiresAtMs) }
        now += 5 * minute
        assertNull(repo.current())
    }

    @Test fun survivesRestartAndBackgroundingUntilExpiry() {
        val storage = Mem()
        SessionRepository(storage) { now }.start("tok", 900)
        now += 10 * minute
        val afterRestart = SessionRepository(storage) { now } // a new process reading the same storage
        assertEquals("tok", afterRestart.current()!!.token)
        now += 5 * minute
        assertNull(SessionRepository(storage) { now }.current())
    }

    @Test fun expiredSessionIsWipedFromStorage() {
        val storage = Mem()
        val repo = SessionRepository(storage) { now }
        repo.start("tok", 900)
        now += 16 * minute
        assertNull(repo.current())
        assertNull(storage.s)
    }

    @Test fun serverCannotGrantMoreThanFifteenMinutes() {
        val s = SessionRepository(Mem()) { now }.start("tok", 99_999)
        assertEquals(now + 15 * minute, s.expiresAtMs)
    }

    @Test fun endClearsTheSession() {
        val repo = SessionRepository(Mem()) { now }
        repo.start("tok", 900)
        repo.end()
        assertNull(repo.current())
    }

    @Test fun remainingTimeCountsDownAndStopsAtZero() {
        val s = Session("t", 10_000)
        assertEquals(4_000, s.remainingMs(6_000))
        assertEquals(0, s.remainingMs(20_000))
        assertTrue(s.isActive(9_999))
        assertFalse(s.isActive(10_000))
    }
}
