package net.activitywatch.android.watcher

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import net.activitywatch.android.RustInterface
import net.activitywatch.android.privacy.PrivateGate
import org.json.JSONObject
import org.threeten.bp.Instant

private fun extractTextByViewId(event: AccessibilityEvent, viewId: String): String? {
    event.source?.let { source ->
        val nodes = source.findAccessibilityNodeInfosByViewId(viewId)
        try {
            return processExtractedText(nodes.firstOrNull()?.text?.toString())
        } finally {
            nodes.forEach { it.recycle() }
            source.recycle()
        }
    }
    return null
}

class WebWatcher : AccessibilityService() {

    // The toolbar is a sibling of the content area, so we search from the window root.
    // findAccessibilityNodeInfosByViewId requires "package:id/name" format and silently
    // rejects bare testTag names, so we traverse manually.
    private fun extractFirefoxUrl(event: AccessibilityEvent): String? {
        val root = rootInActiveWindow ?: return null
        try {
            val found = findNode(root) { it.viewIdResourceName == "ADDRESSBAR_URL_BOX" }
            val result = parseFirefoxAddressBarContentDescription(found?.contentDescription?.toString())
            if (found !== root) found?.recycle()
            return result
        } finally {
            root.recycle()
        }
    }

    private val TAG = "WebWatcher"
    private val bucket_id = "aw-watcher-android-web"

    // 접근성 콜백은 **메인 스레드**에서 온다. RustInterface 는 로컬 aw-server 로 가는
    // 블로킹 JNI/HTTP 라 여기서 직접 부르면 서비스가 응답을 못 해 프레임워크가 언바인드한다.
    // MediaWatcher도 같은 이유로 옮겼다. 그래서 워커 스레드에서만 만진다.
    @Volatile private var ri : RustInterface? = null
    private var lastWindowId: Int? = null
    private val sessionTracker = BrowserSessionTracker()
    private val workerThread = HandlerThread("WebWatcher").also { it.start() }
    private var worker: Handler? = null

    // 앱 라벨은 창 제목의 `"Chrome: "` 접두사를 떼는 데만 쓴다. PackageManager 조회가
    // 이벤트마다 도는 것을 막으려고 캐시한다.
    private val appLabels = HashMap<String, String>()
    private var lastTitleLookup = 0L
    private var lastShortsLookup = 0L
    private var lastShortsSeen = false

    // Applies stripProtocol uniformly to whatever extractor matched, so the logged url is
    // formatted identically no matter which browser/view-variant produced it.
    private fun extractUrl(packageName: String, event: AccessibilityEvent): String? = when (packageName) {
        "com.android.chrome" -> extractTextByViewId(event, "com.android.chrome:id/url_bar")
        "org.mozilla.firefox" ->
            // Compose toolbar (current)
            extractFirefoxUrl(event)
                // View-based toolbar (older Firefox versions)
                ?: extractTextByViewId(event, "org.mozilla.firefox:id/url_bar_title")
                ?: extractTextByViewId(event, "org.mozilla.firefox:id/mozac_browser_toolbar_url_view")
        "com.sec.android.app.sbrowser" ->
            extractTextByViewId(event, "com.sec.android.app.sbrowser:id/location_bar_edit_text")
                ?: extractTextByViewId(event, "com.sec.android.app.sbrowser:id/custom_tab_toolbar_url_bar_text")
        "com.opera.browser" ->
            extractTextByViewId(event, "com.opera.browser:id/url_field")
                ?: extractTextByViewId(event, "com.opera.browser:id/address_field")
        "com.microsoft.emmx" -> extractTextByViewId(event, "com.microsoft.emmx:id/url_bar")
        else -> null
    }?.let(stripProtocol)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating WebWatcher")
        val h = Handler(workerThread.looper)
        worker = h
        h.post {
            try {
                ri = RustInterface(applicationContext).also {
                    it.createBucketHelper(bucket_id, "web.tab.current")
                    // 쇼츠 제목은 **미디어 버킷**에 넣는다 (아래 logShorts 주석 참조).
                    // MediaWatcher 도 같은 버킷을 만드는데, 둘 중 누가 먼저 떠도 되게 양쪽에서 만든다.
                    it.createBucketHelper(MEDIA_BUCKET_ID, MEDIA_BUCKET_TYPE)
                }
            } catch (ex: Throwable) {
                // Catch Throwable (not just Exception) because System.loadLibrary() throws
                // UnsatisfiedLinkError (an Error subclass) when the native library is missing.
                Log.e(TAG, "Failed to initialize RustInterface")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker?.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
    }

    // TODO: This method is called very often, which might affect performance. Future optimizations needed.
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (shouldIgnoreEvent(event)) {
            return
        }
        // 프라이빗 구간에서는 URL·제목·쇼츠 무엇도 만들지 않는다. 열려 있던 세션도 버린다
        // — 안 버리면 프라이빗이 풀릴 때 그 구간을 포함한 채로 기록된다.
        if (PrivateGate.isActive(this)) {
            sessionTracker.handleUrl(null, null)
            lastShortsSeen = false
            return
        }

        val packageName = event.packageName?.toString()
        val isKnownBrowser = packageName != null && packageName in KNOWN_BROWSER_PACKAGES

        val windowChanged = windowChanged(event.windowId)
        lastWindowId = event.windowId

        if (!isKnownBrowser) {
            // for some browsers like Firefox event.packageName can be null (no extractor matched)
            // but we are still on the same window
            if (windowChanged) {
                Log.i(TAG, "Window changed away from a tracked browser; ending session")
                handleUrl(null, newBrowser = null)
            }
            // ★ `packageName` 으로 거르지 않는다 — **유튜브 이벤트는 그 값이 null 로 온다**
            //   (같은 이유로 위 Firefox 주석이 있다). 대신 창의 실제 패키지를 안에서 본다.
            maybeLogShorts()

            return
        }

        try {
            event.source?.let { source ->
                try {
                    val browser = packageName!!
                    val newUrl = extractUrl(browser, event)

                    if (newUrl != null) {
                        handleUrl(newUrl, newBrowser = browser)
                    }
                    browserWindowTitle(browser)?.let { handleWindowTitle(it) }
                } finally {
                    source.recycle()
                }
            }
        } catch(ex : Exception) {
            Log.e(TAG, "Failed to process accessibility event")
        }
    }

    private fun windowChanged(windowId: Int): Boolean = windowId != lastWindowId

    private fun shouldIgnoreEvent(event: AccessibilityEvent) =
        event.packageName == "com.android.systemui"

    /**
     * 페이지 제목은 **노드가 아니라 창 속성**에 있다.
     *
     * 예전에는 `android.webkit.WebView` 노드의 `text` 를 읽었지만 일부 브라우저에서는 비어 있다.
     *
     *     WebView 노드            text=''  contentDescription=''
     *     AccessibilityWindowInfo title='Chrome: 인기글 목록 - 예시 커뮤니티'   ← 여기 있다
     *
     * ★ 창 목록은 접근성 서비스가 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` 를 요구할 때만 온다.
     *   우리 서비스 설정에 이미 들어 있다 (`dumpsys accessibility` 에서 확인).
     *
     * ★ 크롬은 렌더러 접근성을 **접근성 서비스가 붙은 뒤에 켠다.** 서비스가 죽었다 살아나면
     *   이미 떠 있던 크롬은 콘텐츠를 노출하지 않는다 — 창 제목은 그 영향을 받지 않으므로
     *   노드 트리 대신 창을 읽는 편이 이 점에서도 튼튼하다.
     *
     * 앱 라벨 접두사(`"Chrome: "`)는 떼어낸다. 안 떼면 모든 제목이 같은 글자로 시작해
     * 분류 규칙과 타임라인 라벨이 앞부분만 읽고 뭉갠다.
     */
    private fun browserWindowTitle(packageName: String): String? {
        // getWindows() 는 IPC 다. TYPE_WINDOW_CONTENT_CHANGED 는 초당 수십 번 오므로 죈다.
        val now = SystemClock.uptimeMillis()
        if (now - lastTitleLookup < TITLE_LOOKUP_INTERVAL_MS) return null
        lastTitleLookup = now

        val label = appLabel(packageName)
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            if (!window.isActive) continue
            val raw = window.title?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) continue
            val stripped =
                (if (label.isNotEmpty() && raw.startsWith("$label: ")) raw.substring(label.length + 2)
                 else raw).trim()
            if (stripped.isEmpty()) continue
            // ★ 앱 이름은 제목이 아니다. 페이지 전환 중이나 새 탭에서 창 제목이 그냥
            //   "Chrome" 으로 돌아가는데, 그것을 저장하면 그 칸이 아무것도 말하지 않는다.
            //   비워 두는 편이 정직하다 (android/docs/phone-titles.md §5).
            if (stripped.equals(label, ignoreCase = true)) continue
            return stripped
        }
        return null
    }

    /**
     * 유튜브 **쇼츠를 보고 있는 동안**을 미디어 버킷에 `"쇼츠"` 한 덩어리로 남긴다.
     *
     * ## 왜 개별 제목을 안 쫓나
     *
     * 쇼츠는 몇 초마다 다음 영상으로 넘어간다. 제목을 하나하나 따라가려면 접근성 트리에서
     * 매번 **어느 노드가 제목인지** 골라야 하는데, 그 자리에는 안정된 표지가 없다
     * (resource-id 가 없어 "컨테이너 안에서 가장 넓은 노드" 같은 배치 규칙에 기대야 한다).
     * 유튜브가 배치를 바꾸면 `"댓글 10개 보기"` 같은 버튼 설명이 제목 자리에 들어간다 —
     * **조용히 틀린 라벨**이라 비어 있는 것보다 나쁘다.
     *
     * 얻는 것에 비해 위험이 컸다. 10분 칸 하나에 쇼츠가 열 개씩 들어가므로 개별 제목은
     * 어차피 그 칸을 대표하지 못한다. **"쇼츠를 봤다"까지가 이 칸이 정직하게 말할 수 있는
     * 전부**이고, 그건 "일반 영상을 봤다"와 실제로 다른 활동이라 라벨로서 값이 있다.
     *
     * ## 왜 MediaSession 으로는 안 되나
     *
     * 짧은 동영상 재생 중에도 MediaSession이 **직전 영상 상태로 얼어붙을 수 있다**:
     *
     *     state    = NONE(0) 또는 STOPPED(1)   ← 재생 중인데 안 재생 중이라고 한다
     *     TITLE    = 직전에 본 일반 영상 제목
     *     DURATION = 177000 (2분 57초)          ← 재생 중인 쇼츠는 59초였다
     *     VIDEO    = 1280x720 (가로)            ← 쇼츠는 세로다
     *
     * ★ 그래서 **치수(가로/세로)로도 쇼츠를 구별할 수 없다.** 그 값 역시 직전 영상의
     *   것이고, 일반 영상을 한 번도 안 봤으면 치수 자체가 없다.
     *
     * ## 남은 유일한 표지
     *
     * `reel_player_footer_container` — 쇼츠 플레이어에만 있는 id 다. 일반 시청 화면·피드·
     * 검색에는 없다. **있으면 쇼츠, 없으면 아무것도 안 넣는다.** 유튜브가 이 id 를 바꾸면
     * 조용히 기록이 멈춘다 — 틀린 라벨을 남기는 것보다 그쪽이 낫다
     * (`android/docs/phone-titles.md` §5).
     *
     * ## 왜 미디어 버킷인가
     *
     * 서버의 `rollup._title_from_media` 가 같은 패키지의 앱 세션에 미디어 제목을 얹어 준다.
     * `MediaWatcher` 와 같은 data 모양으로 넣으면 서버를 한 줄도 안 고쳐도 된다.
     */
    private fun maybeLogShorts() {
        val now = SystemClock.uptimeMillis()
        if (now - lastShortsLookup < SHORTS_LOOKUP_INTERVAL_MS) return
        lastShortsLookup = now

        val root = rootInActiveWindow ?: return
        val onShorts = try {
            // 이벤트의 packageName 은 못 믿는다(유튜브에서는 null 로 온다). 창의 패키지를 본다.
            if (root.packageName?.toString() != YOUTUBE_PACKAGE) {
                false
            } else {
                // ★ 손으로 트리를 훑지 않는다. `findNode` 같은 클라이언트측 DFS 는 노드를
                //   재활용하며 내려가는데, 유튜브처럼 큰 화면에서는 그 사이 캐시가 무효화돼
                //   **실제로 있는 노드를 못 찾을 수 있다**.
                //   `findAccessibilityNodeInfosByViewId` 는 프레임워크가 살아 있는 트리에서
                //   직접 찾아 준다. `flagReportViewIds` 가 켜져 있어야 하고, 이미 켜져 있다.
                val footers = root.findAccessibilityNodeInfosByViewId(SHORTS_FOOTER_VIEW_ID)
                try {
                    footers.isNotEmpty()
                } finally {
                    footers.forEach { it.recycle() }
                }
            }
        } finally {
            root.recycle()
        }
        if (!onShorts) {
            lastShortsSeen = false
            return
        }

        if (!lastShortsSeen) {
            lastShortsSeen = true
            Log.i(TAG, "Tracked media session started")
        }

        val data = JSONObject()
            .put("app", appLabel(YOUTUBE_PACKAGE).ifEmpty { "YouTube" })
            .put("package", YOUTUBE_PACKAGE)
            .put("title", SHORTS_TITLE)
            .put("artist", "")
            .put("album", "")
            .put("state", "playing")
        // 하트비트라 쇼츠를 보는 동안이 **하나의 구간으로 자란다.** 영상이 몇 개
        // 넘어가든 data 가 같으므로 끊기지 않는다 — 그게 이 설계의 요점이다.
        worker?.post { ri?.heartbeatHelper(MEDIA_BUCKET_ID, Instant.now(), 0.0, data, MEDIA_PULSETIME) }
    }

    private fun appLabel(packageName: String): String = appLabels.getOrPut(packageName) {
        try {
            val pm = applicationContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
    }

    private fun handleUrl(newUrl : String?, newBrowser: String?) {
        sessionTracker.handleUrl(newUrl, newBrowser)?.let { logBrowserEvent(it) }
    }

    private fun handleWindowTitle(newWindowTitle: String) {
        sessionTracker.handleWindowTitle(newWindowTitle)
    }

    private fun logBrowserEvent(session: CompletedBrowserSession) {
        val data = JSONObject()
            .put("url", session.url)
            .put("browser", session.browser)
            .put("title", session.title)
            .put("audible", false) // TODO
            .put("incognito", false) // TODO

        // 메인 스레드에서 부르지 않는다 — 위 ri 선언의 주석 참조.
        worker?.post { ri?.heartbeatHelper(bucket_id, session.start, session.duration.seconds.toDouble(), data, 1.0) }
    }

    override fun onInterrupt() {}

    companion object {
        private const val TITLE_LOOKUP_INTERVAL_MS = 500L
        private const val SHORTS_LOOKUP_INTERVAL_MS = 2_000L
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"

        /** 쇼츠 플레이어에만 있는 id. 일반 시청 화면과 구별하는 관문이다. */
        private const val SHORTS_FOOTER_VIEW_ID = "$YOUTUBE_PACKAGE:id/reel_player_footer_container"

        // MediaWatcher 와 같은 버킷·같은 pulsetime 을 쓴다.
        private const val MEDIA_BUCKET_ID = "aw-watcher-android-media"
        private const val MEDIA_BUCKET_TYPE = "media.playback"
        private const val MEDIA_PULSETIME = 60.0

        /** 쇼츠 구간에 붙이는 라벨. 개별 영상 제목은 쫓지 않는다 — 위 주석 참조.
         *  유튜브 자신이 한국어 UI 에서도 탭 이름을 "Shorts" 로 쓰므로 그대로 따른다. */
        private const val SHORTS_TITLE = "Shorts"

        internal val KNOWN_BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.microsoft.emmx"
        )
    }
}
