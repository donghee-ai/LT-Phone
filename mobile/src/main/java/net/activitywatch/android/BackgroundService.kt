package net.activitywatch.android

import net.activitywatch.android.privacy.PrivateMode
import net.activitywatch.android.privacy.PrivateScheduler
import net.activitywatch.android.watcher.WatcherHealth
import net.activitywatch.android.push.PushScheduler
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "BackgroundService"
private // LT: 채널 설정(중요도)은 만든 뒤에는 앱이 바꿀 수 없다. IMPORTANCE_MIN 을 적용하려면
        // **새 채널 ID** 가 필요하다. 예전 채널은 createNotificationChannel 에서 지운다.
        const val CHANNEL_ID = "lt_background_min"
        private const val LEGACY_CHANNEL_ID = "aw_background_channel"
private const val NOTIFICATION_ID = 1

class BackgroundService : Service() {

    companion object {
        // Sent by SyncSettingsActivity when the user toggles sync on/off so the
        // running scheduler reflects the new setting immediately without a restart.
        const val ACTION_SYNC_ENABLED_CHANGED = "net.activitywatch.android.SYNC_ENABLED_CHANGED"

        // LT: push 설정을 바꿨을 때 스케줄러가 즉시 반영되게 한다. 그러지 않으면
        // 전송을 켜도 서비스가 재시작될 때까지 아무 일도 일어나지 않는다.
        const val ACTION_PUSH_SETTINGS_CHANGED = "net.activitywatch.android.PUSH_SETTINGS_CHANGED"

        // LT: 타일·만료 알람이 프라이빗 상태를 바꿨다. 서버에 통지하고 지연분을 정리한다.
        const val ACTION_PRIVATE_CHANGED = "net.activitywatch.android.PRIVATE_CHANGED"

        // LT: 워처 침묵 점검 주기. 임계값이 12시간이라 촘촘할 이유가 없다.
        private const val HEALTH_REFRESH_MS = 30L * 60 * 1000
        private const val HEALTH_FIRST_DELAY_MS = 2L * 60 * 1000
    }

    private lateinit var syncScheduler: SyncScheduler
    // LT: 서버 push 스케줄러 (5분). SyncScheduler 와 같은 구조다.
    private lateinit var pushScheduler: PushScheduler
    private lateinit var rustInterface: RustInterface
    // LT: 프라이빗 모드 — 서버 폴링 + 지연분 정리
    private lateinit var privateScheduler: PrivateScheduler

    // Becomes true after the first full onStartCommand() completes (server started,
    // workers scheduled, etc.). Guards against ACTION_SYNC_ENABLED_CHANGED skipping
    // full initialization when Android kills and recreates the service.
    private var isFullyStarted = false

    // LT: 워처가 조용해진 것을 상시 알림 문구로 말한다. 알림은 만들 때 한 번만
    // 그려지므로 주기적으로 다시 그려야 한다. 별도 스케줄러를 두지 않고 여기서 돈다 —
    // 하는 일이 "문구 한 줄 갱신" 뿐이라 스케줄러 하나를 더 만들 값이 없다.
    private val healthHandler = Handler(Looper.getMainLooper())
    private val healthRefresh = object : Runnable {
        override fun run() {
            runCatching {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, createNotification())
            }.onFailure { Log.w(TAG, "상시 알림 갱신 실패") }
            healthHandler.postDelayed(this, HEALTH_REFRESH_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BackgroundService created")
        // Promote to foreground BEFORE slow native init so the OS 5-second
        // startForegroundService() countdown can't expire.  RustInterface loads
        // libaw_server.so + calls initialize() which can take several seconds on
        // slow/no-KVM emulators (CI).
        createNotificationChannel()
        val notification = createNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else
                0
        )
        rustInterface = RustInterface(this)
        syncScheduler = SyncScheduler(this)
        pushScheduler = PushScheduler(this)
        privateScheduler = PrivateScheduler(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only short-circuit for the scheduler-toggle action when the service is already
        // fully running. If Android killed the service while SyncSettingsActivity was open,
        // the toggle re-creates the service with this action as its first command — in that
        // case isFullyStarted is false and we fall through to run the complete startup path
        // (server start, event parsing, notify scheduling) before honouring the toggle.
        // LT: 주기가 바뀌었을 수도 있으니 껐다 켠다. start() 는 설정이 비었거나
        // 꺼져 있으면 스스로 빠지므로 여기서 따로 판단하지 않는다.
        if (intent?.action == ACTION_PUSH_SETTINGS_CHANGED && isFullyStarted) {
            Log.i(TAG, "LT push 설정이 바뀌었다 — 스케줄러 재시작")
            pushScheduler.stop()
            pushScheduler.start()
            return START_STICKY
        }

        // LT: 타일을 눌렀다. 로컬 상태는 이미 확정돼 있으므로 여기서는 **알리고 치운다**.
        if (intent?.action == ACTION_PRIVATE_CHANGED && isFullyStarted) {
            privateScheduler.onLocalChange()
            // 상시 알림 문구도 **즉시** 맞춘다. 30분 주기를 기다리면 프라이빗을 켰는데
            // 알림은 "수집 중" 이라고 말하는 구간이 생긴다.
            // ★ 먼저 지운다 — runnable 이 스스로를 다시 예약하므로, 지우지 않고 post 하면
            //   타일을 누를 때마다 체인이 하나씩 늘어난다.
            healthHandler.removeCallbacks(healthRefresh)
            healthHandler.post(healthRefresh)
            return START_STICKY
        }

        if (intent?.action == ACTION_SYNC_ENABLED_CHANGED && isFullyStarted) {
            // LT: 위와 같은 이유로 켜기는 무시하고 끄기만 존중한다.
            val enabled = AWPreferences(this).isSyncEnabled()
            Log.i(TAG, "Sync enabled changed to $enabled (LT: 시작은 하지 않는다)")
            syncScheduler.stop()
            return START_STICKY
        }

        Log.i(TAG, "BackgroundService started")

        // Ensure the API key is written to config.toml before the server reads it.
        // MainActivity does this when the user launches the app normally, but
        // BackgroundService is also started on BOOT_COMPLETED (via SyncAlarmReceiver)
        // without ever going through MainActivity, so we need to guarantee the key
        // exists here as well.
        ensureDashboardApiKey(this)

        // Start the server
        rustInterface.startServerTask()

        // Run hostname migration off the main thread — migrateHostname() is a blocking JNI call.
        // Only mark as migrated on success so a retry is possible if the server wasn't ready yet.
        val prefs = AWPreferences(this)
        if (!prefs.hasMigratedHostname()) {
            CoroutineScope(Dispatchers.IO).launch {
                val hostname = rustInterface.getDeviceName(this@BackgroundService)
                val result = rustInterface.migrateHostname(hostname)
                Log.i(TAG, "Hostname migration completed")
                // The native migrateHostname() returns a plain-text success string
                // ("Migrated hostname for N bucket(s)") or a JSON error object
                // ({"error": "..."}).  Treat the plain-text prefix as success; only
                // retry when the native lib explicitly reports an error.
                val migrationSucceeded = if (result.startsWith("Migrated hostname for")) {
                    true
                } else {
                    Log.w(TAG, "Hostname migration failed; will retry on next start")
                    false
                }
                if (migrationSucceeded) {
                    prefs.setHostnameMigrated()
                }
            }
        }

        // Start the sync scheduler only when the user has enabled sync.
        // Default is off — the sync directory is not accessible to other apps
        // (Android scoped storage), so auto-sync would silently no-op for most users.
        // ★ LT: aw-sync 를 시작하지 않는다.
        //
        // 업스트림의 aw-sync 네이티브 코드가 SIGABRT 로 죽는다 (실측:
        // Java_net_activitywatch_android_SyncInterface_syncBoth+952). SIGABRT 는 프로세스를
        // 통째로 죽이므로 catch 로 막을 수 없고, **같은 프로세스의 PushScheduler 까지 함께
        // 사라진다.** SyncScheduler 와 PushScheduler 가 둘 다 "1분 뒤" 첫 실행이라
        // sync 가 매번 먼저 죽으면서 push 를 데려가, 전송이 한 번도 실행되지 못했다.
        //
        // 이 포크는 서버로 직접 HTTP push 하므로 파일 기반 동기화가 필요 없다.
        // 사용자가 실수로 스위치를 켜도 안전하도록 여기서 원천 차단한다.
        if (prefs.isSyncEnabled()) {
            Log.w(TAG, "LT: sync 설정이 켜져 있지만 시작하지 않는다 " +
                "(aw-sync 네이티브 크래시가 push 까지 죽인다 — 이 포크는 직접 push 를 쓴다)")
        }

        // LT: 서버 push. 설정이 비었거나 꺼져 있으면 start() 안에서 스스로 빠진다.
        pushScheduler.start()

        // LT: 프라이빗 모드 — 서버 상태 폴링 + 지연분 정리.
        privateScheduler.start()

        // LT: 워처 침묵 점검. 첫 회는 서버가 뜬 뒤로 미룬다.
        healthHandler.removeCallbacks(healthRefresh)
        healthHandler.postDelayed(healthRefresh, HEALTH_FIRST_DELAY_MS)

        // Schedule event parsing
        scheduleEventParsing()

        // LT: 활동 시간 알림(aw-notify)을 쓰지 않는다. 이 포크의 목적은 조용히 수집해서
        // 서버로 보내는 것이고, 폰에서 알림을 받을 이유가 없다.
        //
        // enqueueUniquePeriodicWork 로 등록된 작업은 앱을 업데이트해도 남아 있으므로,
        // 예약을 안 하는 것만으로는 부족하고 **기존 예약을 명시적으로 취소**해야 한다.
        androidx.work.WorkManager.getInstance(this).cancelUniqueWork("NotifyWorker")

        isFullyStarted = true
        return START_STICKY
    }

    private fun scheduleNotifyChecks() {
        val notifyRequest = androidx.work.PeriodicWorkRequest.Builder(
            net.activitywatch.android.workers.NotifyWorker::class.java,
            15, java.util.concurrent.TimeUnit.MINUTES
        )
            .addTag("NotifyWorker")
            .build()

        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "NotifyWorker",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            notifyRequest
        )
        Log.i(TAG, "Scheduled activity-time notification worker (every 15 minutes)")
    }

    private fun scheduleEventParsing() {
        val currentDate = java.util.Calendar.getInstance()
        val dueDate = java.util.Calendar.getInstance()
        // Set to midnight
        dueDate.set(java.util.Calendar.HOUR_OF_DAY, 0)
        dueDate.set(java.util.Calendar.MINUTE, 0)
        dueDate.set(java.util.Calendar.SECOND, 0)
        dueDate.set(java.util.Calendar.MILLISECOND, 0)
        if (dueDate.before(currentDate)) {
            dueDate.add(java.util.Calendar.HOUR_OF_DAY, 24)
        }
        val timeDiff = dueDate.timeInMillis - currentDate.timeInMillis

        val saveRequest = androidx.work.PeriodicWorkRequest.Builder(
            net.activitywatch.android.workers.EventParsingWorker::class.java,
            24, java.util.concurrent.TimeUnit.HOURS
        )
            .setInitialDelay(timeDiff, java.util.concurrent.TimeUnit.MILLISECONDS)
            .addTag("EventParsing")
            .build()

        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "EventParsingWorker",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            saveRequest
        )
        Log.i(TAG, "Scheduled event parsing worker with initial delay: ${timeDiff}ms")
    }

    override fun onDestroy() {
        Log.i(TAG, "BackgroundService destroyed")
        if (::syncScheduler.isInitialized) syncScheduler.stop()
        if (::pushScheduler.isInitialized) pushScheduler.stop()
        if (::privateScheduler.isInitialized) privateScheduler.stop()
        healthHandler.removeCallbacks(healthRefresh)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Background tracking"
            val descriptionText = "Keeps LT Phone collecting in the background"
            // LT: IMPORTANCE_MIN 이면 소리·헤드업이 없고 **상태표시줄 아이콘도 뜨지 않는다**
            // (알림 그늘을 내렸을 때만 접힌 채로 보인다). foreground service 알림은
            // 안드로이드가 없앨 수 없게 막아 두었으므로 이것이 가장 조용한 상태다.
            val importance = NotificationManager.IMPORTANCE_MIN
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            // 예전 LOW 채널을 남겨두면 알림 설정에 유령 항목으로 보인다.
            runCatching { notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            }

        // ★ 상태를 **여기 한 곳**으로 말한다. 새 알림을 띄우지 않는 이유:
        //   `POST_NOTIFICATIONS` 를 한 번만 요청하고 다시 안 묻기 때문에 거절한
        //   사용자에게는 새 알림이 안 간다. 이 상시 알림은 권한이 필요 없다.
        //   그리고 상주하는 자리를 두 곳으로 늘리지 않는다 (private-mode.md §5).
        //
        // ★ **프라이빗이 워처 침묵보다 위다.** 두 가지 이유가 있다:
        //   1. 프라이빗 중에는 워처가 멈춘 것이 **정상**이라 침묵 경고는 헛경보다
        //   2. 프라이빗인데 "백그라운드 수집 중" 이라고 말하면 **그게 거짓말이다.**
        //      프라이버시 기능에서 상태 표시가 틀리는 것은 다른 어떤 오류보다 나쁘다
        val privateMode = PrivateMode(this)
        runCatching { privateMode.settleIfExpired() }
        val text = when {
            privateMode.isActive() ->
                "프라이빗 — ${privateMode.remainingMinutes()}분 남음 (기록 안 함)"
            else -> {
                val quiet = runCatching { WatcherHealth.check(this) }.getOrDefault(emptyList())
                if (quiet.isEmpty()) "백그라운드 수집 중"
                else "★ ${quiet.joinToString("·")} 워처가 멈춰 있다 — 권한을 확인하세요"
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LT Phone")
            .setContentText(text)
            // Adaptive launcher mipmaps are not valid status-bar icons (alpha-only).
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentIntent(pendingIntent)
            // LT: 가능한 한 눈에 띄지 않게. MIN 이면 상태표시줄 아이콘이 숨는다.
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setShowWhen(false)
            .setOngoing(true)
            .build()
    }
}
