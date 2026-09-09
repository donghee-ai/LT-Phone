package net.activitywatch.android.watcher

import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.HandlerThread
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import net.activitywatch.android.RustInterface
import net.activitywatch.android.privacy.PrivateGate
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import org.threeten.bp.Instant

/**
 * Watches active media sessions (music, podcasts, video) and logs playback events
 * to ActivityWatch using the NotificationListenerService API.
 *
 * Requires the user to grant "Notification Access" in system settings.
 *
 * Bucket: aw-watcher-android-media
 * Event data: app, title, artist, album, state (playing/paused/stopped)
 */
class MediaWatcher : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaWatcher"
        private const val BUCKET_ID = "aw-watcher-android-media"
        private const val BUCKET_TYPE = "media.playback"
        // Heartbeat pulsetime: merge events within 60s (same track playing continuously)
        private const val PULSETIME = 60.0
        // How often to poll active media sessions to send heartbeats
        private const val POLL_INTERVAL_MS = 15000L

        fun isNotificationAccessGranted(context: android.content.Context): Boolean {
            val flattenedComponent = ComponentName(context, MediaWatcher::class.java).flattenToString()
            val enabledListeners = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            // Parse colon-separated list and match exactly to avoid false positives
            return enabledListeners.split(":").any { it == flattenedComponent }
        }
    }

    // Written from the handlerThread (see onCreate), read from handlerThread callbacks/polling;
    // @Volatile guarantees the initialized instance is visible once the init post completes.
    @Volatile private var ri: RustInterface? = null
    private var sessionManager: MediaSessionManager? = null
    private var activeSessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    // ConcurrentHashMap: onDestroy (main thread) and pollActiveSessions (handlerThread) may
    // access these maps concurrently; CHM prevents ConcurrentModificationException.
    private val activeControllers = ConcurrentHashMap<MediaSession.Token, MediaController>()
    private val activeCallbacks = ConcurrentHashMap<MediaSession.Token, MediaController.Callback>()
    private val lastEventKeys = ConcurrentHashMap<String, String>()

    // Polling mechanism to prevent 60-second cutoffs
    private var handler: android.os.Handler? = null
    private var pollingRunnable: Runnable? = null

    // ★ onCreate 에서 만든다. 예전에는 프로퍼티 초기화였는데, onDestroy 가 quitSafely() 로
    //   루퍼를 죽인 뒤 같은 인스턴스에 onCreate/onListenerConnected 가 다시 오면 **죽은
    //   루퍼**에 핸들러를 걸게 된다. 그러면 postDelayed 가 조용히 false 를 돌려주고
    //   (폴링이 안 돈다), addOnActiveSessionsChangedListener 에 넘긴 핸들러도 죽어 있어
    //   **세션 변경 콜백이 영영 안 온다** — 서비스는 살아 있는 채로 아무것도 기록하지 않는다.
    private var handlerThread: HandlerThread? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MediaWatcher created")
        val thread = HandlerThread("MediaWatcher").also { it.start() }
        handlerThread = thread
        handler = android.os.Handler(thread.looper)

        // RustInterface construction and createBucketHelper() make blocking JNI/HTTP calls to the
        // local aw-server (which may not be running yet at boot). Run them on the handlerThread so
        // they never block the NotificationListenerService main thread. This is posted before the
        // polling runnable and per-session callbacks (all on the same looper), so ri is initialized
        // before the first poll; any event arriving earlier is dropped by the ri?. null-safe calls.
        handler?.post {
            try {
                val r = RustInterface(applicationContext)
                r.createBucketHelper(BUCKET_ID, BUCKET_TYPE)
                ri = r
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize media bucket")
            }
        }

        val localRunnable = object : Runnable {
            override fun run() {
                pollActiveSessions()
                handler?.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        pollingRunnable = localRunnable
        sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "MediaWatcher listener connected")
        registerActiveSessionListener()
        pollingRunnable?.let { handler?.postDelayed(it, POLL_INTERVAL_MS) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "MediaWatcher listener disconnected")
        unregisterAllCallbacks()
        handler?.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "MediaWatcher destroyed")
        unregisterAllCallbacks()
        handler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // We rely on MediaSessionManager for media tracking, not individual notifications.
        // This callback is required by NotificationListenerService but we don't need it.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Not needed for media tracking.
    }

    /**
     * Register a listener for active media session changes.
     * This is called once on service creation and handles all session lifecycle.
     */
    private fun registerActiveSessionListener() {
        val componentName = ComponentName(this, MediaWatcher::class.java)
        try {
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                onActiveSessionsChanged(controllers)
            }
            activeSessionsListener = listener
            // Pass handler so session-change callbacks run on handlerThread, same as the polling loop.
            sessionManager?.addOnActiveSessionsChangedListener(listener, componentName, handler)
            // Process currently active sessions
            val activeSessions = sessionManager?.getActiveSessions(componentName)
            if (activeSessions != null) {
                onActiveSessionsChanged(activeSessions)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification access not granted")
        }
    }

    /**
     * Called when the list of active media sessions changes.
     * Registers callbacks for new sessions and cleans up stale ones.
     */
    private fun onActiveSessionsChanged(controllers: List<MediaController>?) {
        if (controllers == null) return

        val currentTokens = controllers.map { it.sessionToken }.toSet()

        // Remove callbacks for sessions that are no longer active
        val staleTokens = activeControllers.keys - currentTokens
        for (token in staleTokens) {
            val controller = activeControllers.remove(token)
            val callback = activeCallbacks.remove(token)
            if (controller != null && callback != null) {
                controller.unregisterCallback(callback)
                Log.d(TAG, "Unregistered media callback")
            }
        }

        // Register callbacks for new sessions
        for (controller in controllers) {
            val token = controller.sessionToken
            if (!activeControllers.containsKey(token)) {
                val callback = createMediaCallback(controller)
                // Pass handler so all callback methods run on handlerThread, same as the polling loop.
                controller.registerCallback(callback, handler)
                activeControllers[token] = controller
                activeCallbacks[token] = callback
                Log.i(TAG, "Registered media callback")

                // Send initial state if already playing
                val state = controller.playbackState
                val metadata = controller.metadata
                if (state != null && metadata != null) {
                    handlePlaybackChange(controller, state, metadata)
                }
            }
        }
    }

    /**
     * Create a MediaController.Callback that logs playback state changes.
     */
    private fun createMediaCallback(controller: MediaController): MediaController.Callback {
        return object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                val metadata = controller.metadata ?: return
                if (state != null) {
                    handlePlaybackChange(controller, state, metadata)
                }
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                val state = controller.playbackState ?: return
                if (metadata != null) {
                    handlePlaybackChange(controller, state, metadata)
                }
            }

            override fun onSessionDestroyed() {
                val token = controller.sessionToken
                activeControllers.remove(token)
                activeCallbacks.remove(token)?.let { controller.unregisterCallback(it) }
                controller.packageName?.let { lastEventKeys.remove(it) }
                Log.d(TAG, "Media session destroyed")
            }
        }
    }

    /**
     * Process a playback state or metadata change and send an event.
     */
    private fun handlePlaybackChange(
        controller: MediaController,
        state: PlaybackState,
        metadata: MediaMetadata
    ) {
        val packageName = controller.packageName ?: return

        // 프라이빗 구간에서는 무엇을 재생 중인지 만들지 않는다.
        if (PrivateGate.isActive(applicationContext)) {
            lastEventKeys.remove(packageName)   // 풀린 뒤 첫 이벤트가 새 구간으로 시작하게
            return
        }

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

        val playbackState = when (state.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_STOPPED -> "stopped"
            PlaybackState.STATE_BUFFERING -> "buffering"
            else -> return // Ignore transitional states (none, connecting, etc.)
        }

        // Skip events with no useful metadata
        if (title.isEmpty() && artist.isEmpty()) return

        // Resolve app name from package
        val appName = try {
            val pm = applicationContext.packageManager
            pm.getApplicationLabel(
                pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            ).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Application info not found for media session")
            packageName
        }

        // Build event data
        val data = JSONObject().apply {
            put("app", appName)
            put("package", packageName)
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("state", playbackState)
        }

        // Deduplicate: don't send identical heartbeats
        val eventKey = "$packageName|$title|$artist|$playbackState"
        val lastKey = lastEventKeys[packageName]
        if (eventKey == lastKey && playbackState == "playing") {
            // Same track still playing — let heartbeat merging handle it
            ri?.heartbeatHelper(BUCKET_ID, Instant.now(), 0.0, data, PULSETIME)
            return
        }
        lastEventKeys[packageName] = eventKey

        ri?.heartbeatHelper(BUCKET_ID, Instant.now(), 0.0, data, PULSETIME)
    }

    /**
     * 15초마다 재생 중인 세션에 하트비트를 친다.
     *
     * ★ **매번 세션 목록을 다시 물어본다.** 예전에는 `activeControllers` 만 훑었는데,
     *   그 맵은 `OnActiveSessionsChangedListener` 콜백으로만 채워진다. 콜백이 한 번
     *   끊기면 새로 시작된 재생이 맵에 영영 안 들어오고, 폴링은 빈 맵을 돌고,
     *   **서비스는 살아 있는 채로 아무것도 기록하지 않는다.**
     *
     *   알림 접근 권한과 서비스 바인딩이 유지되어도 콜백이 끊기면 새 재생이 기록되지 않을 수 있다.
     *
     *   `getActiveSessions()` 는 작은 바인더 호출이고 워커 스레드에서 돈다.
     *   콜백이 살아 있으면 `onActiveSessionsChanged` 가 변화 없음으로 끝난다.
     */
    private fun pollActiveSessions() {
        refreshActiveSessions()
        for (controller in activeControllers.values) {
            val state = controller.playbackState
            val metadata = controller.metadata
            if (state != null && state.state == PlaybackState.STATE_PLAYING && metadata != null) {
                handlePlaybackChange(controller, state, metadata)
            }
        }
    }

    /** 지금 살아 있는 세션 목록을 다시 읽어 등록/해제를 맞춘다. 콜백이 끊겨도 여기서 복구된다. */
    private fun refreshActiveSessions() {
        try {
            val componentName = ComponentName(this, MediaWatcher::class.java)
            onActiveSessionsChanged(sessionManager?.getActiveSessions(componentName))
        } catch (e: SecurityException) {
            // 알림 접근 권한이 꺼졌다. 다음 폴링에서 다시 본다 — 조용히 죽지는 않는다.
            Log.w(TAG, "Notification access not granted while polling")
        }
    }

    private fun unregisterAllCallbacks() {
        // Remove the active sessions listener to prevent leaks
        activeSessionsListener?.let { listener ->
            sessionManager?.removeOnActiveSessionsChangedListener(listener)
        }
        activeSessionsListener = null

        // Remove all per-controller callbacks
        for ((token, controller) in activeControllers) {
            activeCallbacks[token]?.let { controller.unregisterCallback(it) }
        }
        activeControllers.clear()
        activeCallbacks.clear()
        lastEventKeys.clear()
    }
}
