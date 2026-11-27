package net.activitywatch.android.watcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import net.activitywatch.android.RustInterface
import net.activitywatch.android.privacy.PrivateGate
import net.activitywatch.android.push.LTSettings
import org.json.JSONObject
import org.threeten.bp.Duration
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

    // ── 최소 권한: 세션 끝내기 · 스위치 ─────────────────────────────
    //
    // ★ `accessibility_service_config.xml` 의 `packageNames` 때문에 브라우저·유튜브 밖의
    //   이벤트는 이 서비스에 **아예 안 온다.** 예전의 "다른 앱 창으로 바뀜 → 세션 끝" 은
    //   이제 대부분 발화하지 않으므로, 세션은 다음 네 길로 닫는다:
    //     1. 유튜브(목록 안의 비브라우저)의 TYPE_WINDOW_STATE_CHANGED
    //     2. 화면 꺼짐 (ACTION_SCREEN_OFF — 아래 receiver)
    //     3. onInterrupt / onUnbind
    //     4. 마지막 브라우저 이벤트로부터 [WEB_SESSION_STALE] 가 지나면 (staleCheck)
    //   서버 rollup 은 추가로 **사용 통계의 전경 앱이 브라우저인 칸에만** 웹 URL 을 붙인다.
    //   그래서 4 번이 꼬리를 조금 길게 잡아도 그 꼬리는 서버에서 잘려 나간다.
    //
    // 세션 트래커는 접근성 콜백과 같은 **메인 스레드**에서만 만진다 (receiver·핸들러 포함).
    private val mainHandler = Handler(Looper.getMainLooper())
    private val staleCheck = Runnable {
        sessionTracker.endIfStale(WEB_SESSION_STALE)?.let {
            Log.i(TAG, "Browser session stale; ending session")
            logBrowserEvent(it)
        }
    }
    private var screenOffReceiver: BroadcastReceiver? = null
    private var settings: LTSettings? = null

    // ★ 리스너는 필드로 붙잡아 둔다. SharedPreferences 는 리스너를 **약한 참조**로 들고 있어서
    //   람다를 바로 넘기면 GC 뒤에 스위치를 꺼도 아무 일도 안 일어난다.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == LTSettings.KEY_CAPTURE_WEB && settings?.webCaptureEnabled == false) {
            releaseAccessibility("스위치가 꺼졌다")
        }
    }
    @Volatile private var released = false

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        released = false
        val s = LTSettings(applicationContext)
        settings = s
        // 앱 안 스위치가 꺼져 있으면 시스템 설정에서 켰더라도 **바로 내려놓는다.**
        // 켜기는 설정 화면(또는 온보딩)의 스위치가 먼저 값을 켠 뒤 시스템 설정으로 보낸다.
        if (!s.webCaptureEnabled) {
            releaseAccessibility("연결됐지만 스위치가 꺼져 있다")
            return
        }
        s.registerListener(prefsListener)
        registerScreenOffReceiver()
    }

    /**
     * 화면이 꺼지면 열린 브라우저 세션을 닫는다.
     *
     * ★ `packageNames` 필터 뒤로는 "브라우저를 떠났다" 는 신호가 거의 없다. 화면 꺼짐은
     *   폰을 내려놓는 가장 흔한 끝이고, 이걸 안 잡으면 다음에 브라우저를 열 때까지(또는
     *   staleCheck 까지) 세션이 잠든 시간 내내 늘어난다.
     */
    private fun registerScreenOffReceiver() {
        if (screenOffReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_OFF) return
                lastShortsSeen = false
                endBrowserSession("screen off")
            }
        }
        // 시스템 브로드캐스트는 NOT_EXPORTED 로도 받는다 (Android 14 의 플래그 의무 대응).
        ContextCompat.registerReceiver(
            this, r, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenOffReceiver = r
    }

    private fun unregisterHooks() {
        mainHandler.removeCallbacks(staleCheck)
        screenOffReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenOffReceiver = null
        settings?.unregisterListener(prefsListener)
    }

    private fun endBrowserSession(reason: String) {
        mainHandler.removeCallbacks(staleCheck)
        // 타이머를 지웠으니 다음 브라우저 이벤트가 곧바로 다시 걸게 한다.
        lastStaleArm = 0L
        sessionTracker.endSession()?.let {
            Log.i(TAG, "Ending browser session ($reason)")
            logBrowserEvent(it)
        }
    }

    /**
     * 스위치가 꺼졌다 — 열린 세션을 닫고 **접근성 권한 자체를 돌려준다.**
     *
     * 이벤트를 무시하기만 하면 권한은 그대로 남아 시스템 설정에 "사용 중" 으로 보인다.
     * 사용자가 끈 것은 "기록" 이 아니라 "이 앱이 화면을 읽는 것" 이므로 `disableSelf()` 로
     * 서비스를 실제로 끈다. 다시 켜려면 시스템 접근성 설정에서 켜야 한다.
     */
    private fun releaseAccessibility(reason: String) {
        if (released) return
        released = true
        Log.i(TAG, "웹 수집 꺼짐 — 접근성 권한을 내려놓는다 ($reason)")
        endBrowserSession("capture disabled")
        lastShortsSeen = false
        unregisterHooks()
        try {
            disableSelf()
        } catch (e: Exception) {
            Log.w(TAG, "disableSelf 실패")
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // 접근성을 끄거나 앱을 업데이트할 때 온다. 워커는 onDestroy 전까지 살아 있으므로
        // 여기서 닫은 세션은 저장된다.
        endBrowserSession("unbind")
        unregisterHooks()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterHooks()
        worker?.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
    }

    // TODO: This method is called very often, which might affect performance. Future optimizations needed.
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 스위치가 꺼졌으면 **아무것도 읽지 않는다.** disableSelf() 가 효력을 내기 전에
        // 들어온 이벤트도 여기서 버린다.
        if (released) return
        if (settings?.webCaptureEnabled == false) {
            releaseAccessibility("이벤트 중 스위치 꺼짐 확인")
            return
        }
        if (shouldIgnoreEvent(event)) {
            return
        }
        // 프라이빗 구간에서는 URL·제목·쇼츠 무엇도 만들지 않는다. 열려 있던 세션도 버린다
        // — 안 버리면 프라이빗이 풀릴 때 그 구간을 포함한 채로 기록된다.
        if (PrivateGate.isActive(this)) {
            sessionTracker.handleUrl(null, null)
            mainHandler.removeCallbacks(staleCheck)
            lastStaleArm = 0L
            lastShortsSeen = false
            return
        }

        val packageName = event.packageName?.toString()
        val windowChanged = windowChanged(event.windowId)
        lastWindowId = event.windowId

        val kind = CaptureToggles.classifyWebEvent(
            packageName = packageName,
            isWindowStateChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            windowChanged = windowChanged,
            browsers = KNOWN_BROWSER_PACKAGES
        )

        if (kind != CaptureToggles.WebEvent.BROWSER) {
            // for some browsers like Firefox event.packageName can be null (no extractor matched)
            // but we are still on the same window
            if (kind == CaptureToggles.WebEvent.LEFT_BROWSER && sessionTracker.hasOpenSession()) {
                endBrowserSession("left browser window")
            }
            // ★ `packageName` 으로 거르지 않는다 — **유튜브 이벤트는 그 값이 null 로 온다**
            //   (같은 이유로 위 Firefox 주석이 있다). 대신 창의 실제 패키지를 안에서 본다.
            // ★ 단, `packageNames` 필터를 건 뒤로는 프레임워크가 **패키지가 null 인 이벤트를
            //   아예 안 보낼 수 있다** (필터가 이벤트의 packageName 으로 비교한다). 그래도
            //   유튜브가 패키지를 단 이벤트(창 전환·내용 변경)는 오므로 여기서 쇼츠를 본다.
            //   쇼츠가 실제 폰에서 여전히 잡히는지는 **기기에서 확인해야 한다.**
            maybeLogShorts()

            return
        }

        // 브라우저가 살아 있다는 표시. 2초에 한 번만 타이머를 다시 건다 — 이벤트마다
        // removeCallbacks/postDelayed 를 부르는 것도 공짜는 아니다.
        sessionTracker.noteActivity()
        val nowUp = SystemClock.uptimeMillis()
        if (nowUp - lastStaleArm > STALE_REARM_MS) {
            lastStaleArm = nowUp
            mainHandler.removeCallbacks(staleCheck)
            mainHandler.postDelayed(staleCheck, WEB_SESSION_STALE.toMillis() + STALE_REARM_MS)
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

    private var lastStaleArm = 0L

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

    // 시스템이 피드백을 끊으라고 할 때 온다. 세션을 열어 둔 채 두면 이 다음 이벤트가
    // 언제 올지 모르므로 닫는다.
    override fun onInterrupt() {
        endBrowserSession("interrupt")
    }

    companion object {
        private const val TITLE_LOOKUP_INTERVAL_MS = 500L
        private const val SHORTS_LOOKUP_INTERVAL_MS = 2_000L

        /**
         * 브라우저 이벤트가 이만큼 없으면 세션을 닫는다 ([BrowserSessionTracker.endIfStale]).
         *
         * 서버 rollup 칸이 10분이라 그에 맞췄다. 짧으면 스크롤 없이 긴 글을 읽는 동안
         * 세션이 쪼개지고, 길면 브라우저를 떠난 뒤의 꼬리가 길어진다 (서버가 잘라 준다).
         */
        internal val WEB_SESSION_STALE: Duration = Duration.ofMinutes(10)

        /** stale 타이머를 다시 거는 최소 간격. 이벤트마다 Handler 를 만지지 않으려고. */
        private const val STALE_REARM_MS = 2_000L
        internal const val YOUTUBE_PACKAGE = "com.google.android.youtube"

        /** 쇼츠 플레이어에만 있는 id. 일반 시청 화면과 구별하는 관문이다. */
        private const val SHORTS_FOOTER_VIEW_ID = "$YOUTUBE_PACKAGE:id/reel_player_footer_container"

        // MediaWatcher 와 같은 버킷·같은 pulsetime 을 쓴다.
        private const val MEDIA_BUCKET_ID = "aw-watcher-android-media"
        private const val MEDIA_BUCKET_TYPE = "media.playback"
        private const val MEDIA_PULSETIME = 60.0

        /** 쇼츠 구간에 붙이는 라벨. 개별 영상 제목은 쫓지 않는다 — 위 주석 참조.
         *  유튜브 자신이 한국어 UI 에서도 탭 이름을 "Shorts" 로 쓰므로 그대로 따른다. */
        private const val SHORTS_TITLE = "Shorts"

        /**
         * ★ **세 곳이 같은 목록이어야 한다:**
         *   1. 여기 (URL 을 읽을 브라우저)
         *   2. `res/xml/accessibility_service_config.xml` 의 `android:packageNames`
         *      (여기에 유튜브 [YOUTUBE_PACKAGE] 를 더한 것 — 이 밖의 앱 이벤트는 서비스에 안 온다)
         *   3. life-trainer 서버의 `config/rules.yaml` `browser_apps` (rollup 이 웹 URL 을
         *      붙일 칸을 고르는 기준)
         *   여기에 브라우저를 더하고 xml 을 안 고치면 그 브라우저의 이벤트는 **조용히 안 온다.**
         *   xml 의 packageNames 는 리소스라 상수를 참조할 수 없어서 한 곳으로 못 모은다.
         */
        internal val KNOWN_BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.microsoft.emmx"
        )

        /** 접근성 설정 `packageNames` 와 같아야 하는 목록. JVM 테스트가 xml 과 맞춰 본다. */
        internal val ACCESSIBILITY_PACKAGES: Set<String> = KNOWN_BROWSER_PACKAGES + YOUTUBE_PACKAGE
    }
}
