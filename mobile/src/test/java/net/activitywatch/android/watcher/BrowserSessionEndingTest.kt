/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.threeten.bp.Duration
import org.threeten.bp.Instant

/**
 * `packageNames` 필터 뒤에서 브라우저 세션을 닫는 길 (화면 꺼짐·언바인드·오래됨).
 * 예전에는 "다른 앱 창 이벤트" 가 닫아 줬지만 이제 그 이벤트가 오지 않는다.
 */
class BrowserSessionEndingTest {

    private class FakeClock(private var instant: Instant) {
        fun advanceSeconds(seconds: Long) { instant = instant.plusSeconds(seconds) }
        fun now(): Instant = instant
    }

    private val stale = Duration.ofMinutes(10)

    @Test
    fun endSessionClosesTheOpenSessionAtNow() {
        val clock = FakeClock(Instant.parse("2026-01-01T00:00:00Z"))
        val t = BrowserSessionTracker(clock::now)
        assertNull(t.handleUrl("example.com/a", "com.android.chrome"))
        assertTrue(t.hasOpenSession())
        clock.advanceSeconds(30)

        val done = t.endSession()
        assertNotNull(done)
        assertEquals("example.com/a", done!!.url)
        assertEquals(Duration.ofSeconds(30), done.duration)
        assertFalse(t.hasOpenSession())
        // 두 번 닫아도 두 번 기록되지 않는다 (화면 꺼짐 뒤 언바인드 같은 겹침).
        assertNull(t.endSession())
    }

    @Test
    fun endSessionWithoutOpenSessionIsNoop() {
        val t = BrowserSessionTracker { Instant.parse("2026-01-01T00:00:00Z") }
        assertNull(t.endSession())
        assertNull(t.endIfStale(Duration.ZERO))
    }

    @Test
    fun staleOnlyAfterNoActivityForMaxIdle() {
        val clock = FakeClock(Instant.parse("2026-01-01T00:00:00Z"))
        val t = BrowserSessionTracker(clock::now)
        t.handleUrl("example.com/a", "com.android.chrome")

        clock.advanceSeconds(9 * 60)
        assertNull(t.endIfStale(stale))
        // 같은 URL 에서 이벤트가 왔다 (스크롤 등) — 시계를 다시 잰다.
        t.noteActivity()
        clock.advanceSeconds(9 * 60)
        assertNull(t.endIfStale(stale))
        assertTrue(t.hasOpenSession())

        clock.advanceSeconds(60)
        val done = t.endIfStale(stale)
        assertNotNull(done)
        // 끝은 "지금" — 넘친 꼬리는 서버가 전경 앱 기준으로 잘라 준다.
        assertEquals(Duration.ofMinutes(19), done!!.duration)
        assertFalse(t.hasOpenSession())
    }

    @Test
    fun newSessionResetsActivityClock() {
        val clock = FakeClock(Instant.parse("2026-01-01T00:00:00Z"))
        val t = BrowserSessionTracker(clock::now)
        t.handleUrl("example.com/a", "com.android.chrome")
        t.noteActivity()
        clock.advanceSeconds(20 * 60)
        // 오래된 활동 시각이 남아 있어도 새 URL 이 오면 새 세션은 막 시작한 것이다.
        assertNotNull(t.handleUrl("example.com/b", "com.android.chrome"))
        assertNull(t.endIfStale(stale))
        assertTrue(t.hasOpenSession())
    }

    @Test
    fun afterEndNextUrlStartsFreshSession() {
        val clock = FakeClock(Instant.parse("2026-01-01T00:00:00Z"))
        val t = BrowserSessionTracker(clock::now)
        t.handleUrl("example.com/a", "com.android.chrome")
        clock.advanceSeconds(5)
        assertNotNull(t.endSession())   // 화면 꺼짐
        clock.advanceSeconds(3600)
        // 다시 같은 URL — 꺼져 있던 한 시간을 이전 세션에 붙이지 않는다.
        assertNull(t.handleUrl("example.com/a", "com.android.chrome"))
        clock.advanceSeconds(10)
        assertEquals(Duration.ofSeconds(10), t.endSession()!!.duration)
    }
}
