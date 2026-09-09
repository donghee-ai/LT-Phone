package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.threeten.bp.Instant

class BrowserSessionTrackerTest {

    // A clock we can advance/rewind by hand so duration math is deterministic and we can
    // reproduce a backward clock step (NTP sync, manual change) without waiting on real time.
    private class FakeClock(private var instant: Instant) {
        fun advanceSeconds(seconds: Long) { instant = instant.plusSeconds(seconds) }
        // 제목 확정 규칙이 밀리초 단위라(전이 구간이 7ms) 밀리초로도 밀 수 있어야 한다.
        fun advanceMillis(millis: Long) { instant = instant.plusMillis(millis) }
        fun rewindSeconds(seconds: Long) { instant = instant.minusSeconds(seconds) }
        fun now(): Instant = instant
    }

    @Test
    fun `first url does not emit a completed session`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        val completed = tracker.handleUrl("example.com", "chrome")

        assertNull(completed)
    }

    @Test
    fun `same url and browser does not emit a completed session`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        tracker.handleUrl("example.com", "chrome")
        val completed = tracker.handleUrl("example.com", "chrome")

        assertNull(completed)
    }

    @Test
    fun `url change emits the previous url as a completed session with correct duration`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        tracker.handleUrl("example.com", "chrome")
        clock.advanceSeconds(30)
        val completed = tracker.handleUrl("example.org", "chrome")

        checkNotNull(completed)
        assertEquals("example.com", completed.url)
        assertEquals("chrome", completed.browser)
        assertEquals(Instant.ofEpochSecond(1000), completed.start)
        assertEquals(30L, completed.duration.seconds)
    }

    @Test
    fun `browser change alone (same url) also ends the session`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        tracker.handleUrl("example.com", "chrome")
        clock.advanceSeconds(5)
        val completed = tracker.handleUrl("example.com", "firefox")

        checkNotNull(completed)
        assertEquals("chrome", completed.browser)
    }

    @Test
    fun `title set right before the url changes belongs to the NEXT page, not this one`() {
        // 제목이 URL보다 먼저 바뀌는 회귀 사례를 재현한다.
        //
        //   실기기에서 재 보니 **창 제목이 주소창 URL 보다 7ms 먼저 바뀐다**(크롬):
        //
        //       18:35:28.678  Title: 사회 : 예시 뉴스
        //       18:35:28.685  Url:   news.example-portal.com/section/100
        //
        //   즉 "URL 이 바뀌기 직전에 온 제목" 은 이전 페이지 것이 아니라 **다음 페이지
        //   것**이다. 옛 동작으로는 `m.example-forum.com` 이벤트에 `"사회 : 예시 뉴스"` 가
        //   붙어 나갔다 — 빈 제목보다 나쁘다. 조용히 틀린 채로 그럴듯해 보인다.
        //
        //   실제 방문 중에 온 제목은 **몇 초씩 머무르므로** 확정된다 (바로 아래 테스트).
        //   여기처럼 오자마자 URL 이 바뀌는 것은 7ms 짜리 전이 구간뿐이다.
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        tracker.handleUrl("example.com", "chrome")
        tracker.handleWindowTitle("Example Domain")
        val completed = tracker.handleUrl("example.org", "chrome")

        checkNotNull(completed)
        assertEquals("", completed.title)
    }

    @Test
    fun `방문 중에 온 제목은 확정되어 붙는다`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        tracker.handleUrl("example.com", "chrome")
        tracker.handleWindowTitle("Example Domain")
        clock.advanceSeconds(5)         // ← 머물렀으므로 이 페이지 것이다
        tracker.handleUrl("example.com", "chrome")
        val completed = tracker.handleUrl("example.org", "chrome")

        checkNotNull(completed)
        assertEquals("Example Domain", completed.title)
    }

    @Test
    fun `missing title defaults to empty string`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        tracker.handleUrl("example.com", "chrome")
        val completed = tracker.handleUrl("example.org", "chrome")

        checkNotNull(completed)
        assertEquals("", completed.title)
    }

    @Test
    fun `title does not carry over into the next session`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        tracker.handleUrl("example.com", "chrome")
        tracker.handleWindowTitle("Example Domain")
        tracker.handleUrl("example.org", "chrome")

        // The title for the new page hasn't arrived yet - should not leak the old title.
        val completed = tracker.handleUrl("example.net", "chrome")

        checkNotNull(completed)
        assertEquals("", completed.title)
    }

    @Test
    fun `handleWindowTitle reports whether the title actually changed`() {
        val tracker = BrowserSessionTracker()

        assertEquals(true, tracker.handleWindowTitle("Example Domain"))
        assertEquals(false, tracker.handleWindowTitle("Example Domain"))
        assertEquals(true, tracker.handleWindowTitle("Something else"))
    }

    @Test
    fun `negative duration from a backward clock step is clamped to zero`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        tracker.handleUrl("example.com", "chrome")
        clock.rewindSeconds(60) // simulate NTP sync stepping the clock backward
        val completed = tracker.handleUrl("example.org", "chrome")

        checkNotNull(completed)
        assertEquals(0L, completed.duration.seconds)
    }

    // ── 제목이 URL 보다 먼저 온다 ──────────────────────────────────────
    //
    // 브라우저에 따라 창 제목이 주소창 URL 보다 먼저 바뀐다. 전이 구간과 방문 중을
    // 가르는 것은 순서가 아니라 **시간**이다 — 방문 중 제목은 몇 초씩 머문다.

    @Test
    fun `전이 직전에 온 제목은 이전 페이지에 안 붙는다`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val t = BrowserSessionTracker(clock::now)

        t.handleUrl("a.com", "chrome")
        clock.advanceSeconds(10)
        t.handleUrl("a.com", "chrome")          // 방문 중 (확정할 제목 없음)
        t.handleWindowTitle("B 페이지")          // ★ 7ms 뒤 URL 이 바뀐다
        clock.advanceMillis(7)
        val done = t.handleUrl("b.com", "chrome")

        assertEquals("", done?.title)
    }

    @Test
    fun `그 제목은 다음 페이지 것으로 넘어간다`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val t = BrowserSessionTracker(clock::now)

        t.handleUrl("a.com", "chrome")
        t.handleWindowTitle("B 페이지")
        clock.advanceMillis(7)
        t.handleUrl("b.com", "chrome")          // a.com 종료 (제목 없음)
        clock.advanceSeconds(5)
        t.handleUrl("b.com", "chrome")          // 1초 넘게 머물렀다 → 확정
        val done = t.handleUrl("c.com", "chrome")

        assertEquals("B 페이지", done?.title)
    }

    @Test
    fun `확정 전에 떠나면 제목을 안 붙인다`() {
        // 틀린 제목보다 빈 제목이 낫다.
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val t = BrowserSessionTracker(clock::now)

        t.handleUrl("a.com", "chrome")
        t.handleWindowTitle("확정 전 제목")
        clock.advanceMillis(200)
        val done = t.handleUrl(null, null)

        assertEquals("", done?.title)
    }
}
