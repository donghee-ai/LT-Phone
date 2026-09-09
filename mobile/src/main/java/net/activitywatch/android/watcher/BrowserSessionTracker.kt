package net.activitywatch.android.watcher

import org.threeten.bp.Duration
import org.threeten.bp.Instant

internal data class CompletedBrowserSession(
    val url: String,
    val browser: String,
    val title: String,
    val start: Instant,
    val duration: Duration
)

// Pure session/state-machine logic extracted out of WebWatcher so it can be unit-tested
// (mobile/src/test) without an AccessibilityService or device/emulator.
internal class BrowserSessionTracker(
    private val now: () -> Instant = { Instant.ofEpochMilli(System.currentTimeMillis()) }
) {
    private var lastUrlTimestamp: Instant? = null
    private var lastUrl: String? = null
    private var lastBrowser: String? = null

    // ── 제목을 두 칸으로 나눠 들고, **시간으로** 가른다 ──────────────
    //
    // ★ **창 제목은 주소창 URL 보다 먼저 바뀔 수 있다.**
    //
    //     Title: Example article                         ← 새 페이지 제목이 먼저
    //     Url:   news.example.com/article/100            ← 직후에 URL
    //
    //   한 칸만 쓰면 두 이벤트 사이에 **다음 페이지 제목이 이전 URL 에 덮어써진다.**
    //   실제로 `m.example-forum.com` 이벤트에 `"사회 : 예시 뉴스"` 가 붙어 나갔다.
    //
    // ★ 그렇다고 "먼저 온 제목은 다음 페이지 것" 이라고 단정하면 **반대로 밀린다.**
    //   그것도 재 봤다 — `news.example-portal.com` 에 이전 페이지 제목이 붙었다. 방문 중에
    //   정상적으로 온 제목까지 다음 페이지로 넘어가기 때문이다.
    //
    //   가르는 것은 순서가 아니라 **시간**이다. 전이 구간은 7ms 인데 방문 중에 온
    //   제목은 몇 초씩 머문다. 그래서 [TITLE_SETTLE] 만큼 살아남은 제목만 **이 페이지
    //   것으로 확정**하고, 그보다 어린 제목은 다음 페이지 후보로 넘긴다.
    private var confirmedTitle: String? = null
    private var pendingTitle: String? = null
    private var pendingTitleAt: Instant? = null

    // Returns the just-completed session (previous url/browser/title) when the url or
    // browser changes, so the caller can log it. We wait for the url to change before
    // logging so we have a chance to receive the page title, which often only arrives
    // after the page loads and/or the user interacts with it.
    fun handleUrl(newUrl: String?, newBrowser: String?): CompletedBrowserSession? {
        settleTitle()
        if (newUrl == lastUrl && newBrowser == lastBrowser) return null

        val completed = lastUrl?.let { url ->
            lastBrowser?.let { browser ->
                val start = lastUrlTimestamp!!
                CompletedBrowserSession(
                    url = url,
                    browser = browser,
                    title = confirmedTitle ?: "",
                    start = start,
                    // Clock can step backward (NTP sync, manual change) between `start` and now;
                    // don't report a negative duration in that case.
                    duration = Duration.between(start, now()).coerceAtLeast(Duration.ZERO)
                )
            }
        }

        lastUrlTimestamp = now()
        lastUrl = newUrl
        lastBrowser = newBrowser
        // ★ `pendingTitle` 은 **남긴다.** 확정될 만큼 오래 안 머문 제목이므로 아직
        //   이 페이지 것이라고 볼 수 없고, URL 보다 먼저 온 새 페이지 제목일 수 있다.
        confirmedTitle = null
        return completed
    }

    // Returns true when the title actually changed (so the caller can log it).
    fun handleWindowTitle(newWindowTitle: String): Boolean {
        if (newWindowTitle == pendingTitle || newWindowTitle == confirmedTitle) return false
        pendingTitle = newWindowTitle
        pendingTitleAt = now()
        return true
    }

    /** [TITLE_SETTLE] 만큼 머문 제목은 **이 페이지 것**으로 확정한다. */
    private fun settleTitle() {
        val at = pendingTitleAt ?: return
        val title = pendingTitle ?: return
        if (Duration.between(at, now()) >= TITLE_SETTLE) {
            confirmedTitle = title
            pendingTitle = null
            pendingTitleAt = null
        }
    }

    private companion object {
        /**
         * 제목이 이 페이지 것으로 인정받기까지 머물러야 하는 시간.
         *
         * 재 본 전이 구간은 **7ms** 이고 방문 중 제목은 몇 초씩 머문다 — 그 사이면
         * 어디를 잡아도 되지만, 짧게 잡을수록 전이를 놓치고 길게 잡을수록 짧은 방문의
         * 제목을 잃는다. 1초는 전이보다 100배 크고 사람이 페이지를 보는 시간보다 작다.
         *
         * ★ 확정 못 한 제목은 **안 붙인다.** 틀린 제목보다 빈 제목이 낫다.
         */
        val TITLE_SETTLE: Duration = Duration.ofSeconds(1)
    }
}
