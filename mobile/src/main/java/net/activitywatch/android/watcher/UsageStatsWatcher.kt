package net.activitywatch.android.watcher

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.activitywatch.android.AWPreferences
import net.activitywatch.android.RustInterface
import net.activitywatch.android.models.Event
import net.activitywatch.android.privacy.PrivateMode
import net.activitywatch.android.watcher.SessionEventWatcher
import org.json.JSONObject
import org.threeten.bp.DateTimeUtils
import org.threeten.bp.Instant
import java.net.URL
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

// Single source of truth lives in SessionEventWatcher; alias here so the two watchers
// can never drift to different bucket names.
const val bucket_id = SESSION_BUCKET_ID
const val unlock_bucket_id = UNLOCK_BUCKET_ID

class UsageStatsWatcher constructor(val context: Context) {
    private val ri = RustInterface(context)
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    private val sessionWatcher = SessionEventWatcher(context)
    // LT: 화면·터치 → afk 버킷. 세션 워처와 같은 주기로 돈다.
    private val interactionWatcher = InteractionWatcher(context)

    var lastUpdated: Instant? = null
    var useSessionBasedEvents = true // Toggle between individual events and session-based events


    enum class PermissionStatus {
        GRANTED, DENIED, CANNOT_BE_GRANTED
    }

    companion object {
        const val TAG = "UsageStatsWatcher"

        fun isUsageAllowed(context: Context): Boolean {
            // https://stackoverflow.com/questions/27215013/check-if-my-application-has-usage-access-enabled
            try {
                context.packageManager.getApplicationInfo(context.packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "Application info lookup failed")
                return false
            }

            return getUsageStatsPermissionsStatus(context)
        }

        fun isAccessibilityAllowed(context: Context): Boolean {
            return getAccessibilityPermissionStatus(context)
        }

        private fun getUsageStatsPermissionsStatus(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
           return if (mode == AppOpsManager.MODE_DEFAULT) context.checkCallingOrSelfPermission(
                    Manifest.permission.PACKAGE_USAGE_STATS
                ) == PackageManager.PERMISSION_GRANTED else mode == AppOpsManager.MODE_ALLOWED
        }

        private fun getAccessibilityPermissionStatus(context: Context): Boolean {
            // https://stackoverflow.com/a/54839499/4957939
            val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val accessibilityServices = accessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityEvent.TYPES_ALL_MASK)
            return accessibilityServices.any { it.id.contains(context.packageName) }
        }
    }

    private fun getUSM(): UsageStatsManager? {
        val usageIsAllowed = isUsageAllowed(context)

        return if (usageIsAllowed) {
            // Get UsageStatsManager stuff
            val usm: UsageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            usm
        } else {
            Log.w(TAG, "Was not allowed access to UsageStats, enable in settings.")

            // Unused, deprecated in favor of OnboardingActivity
            /*
            Handler(Looper.getMainLooper()).post {
                // Create an alert dialog to inform the user
                AlertDialog.Builder(context)
                    .setTitle("ActivityWatch needs Usage Access")
                    .setMessage("This gives ActivityWatch access to your device use data, which is required for the basic functions of the application.\n\nWe respect your privacy, no data leaves your device.")
                    .setPositiveButton("Continue") { _, _ ->
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        dialog.cancel()
                        System.exit(0)
                    }
                    .show()
            }
             */
            null
        }
    }

    private var alarmMgr: AlarmManager? = null
    private lateinit var alarmIntent: PendingIntent

    fun setupAlarm() {
        alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmIntent = Intent(context, AlarmReceiver::class.java).let { intent ->
            intent.action = "net.activitywatch.android.watcher.LOG_DATA"
            PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }

        // LT: 원본은 AlarmManager.INTERVAL_HOUR 고정이었다. 설정값(기본 5분)으로 바꾼다.
        val interval = AWPreferences(context).getCollectIntervalMinutes() * 60_000L
        alarmMgr?.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + interval,
            interval,
            alarmIntent
        )
    }


    fun queryUsage() {
        val usm = getUSM() ?: return

        usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, 0, Long.MAX_VALUE)
        Log.i(TAG, "Usage access check completed")
    }

    private fun getLastEvent(): JSONObject? {
        val events = ri.getEventsJSON(bucket_id, limit=1)
        return if (events.length() == 1) {
            //Log.d(TAG, "Last event: ${events[0]}")
            events[0] as JSONObject
        } else {
            Log.w(TAG, "Unexpected last-event result count")
            null
        }
    }

    // TODO: Maybe return end of event instead of start?
    private fun getLastEventTime(): Instant? {
        val lastEvent = getLastEvent()

        return if(lastEvent != null) {
            val timestampString = lastEvent.getString("timestamp")
            // Instant.parse("2014-10-23T00:35:14.800Z").toEpochMilli()
            try {
                val timeCreatedDate = isoFormatter.parse(timestampString)
                DateTimeUtils.toInstant(timeCreatedDate)
            } catch (e: ParseException) {
                Log.e(TAG, "Unable to parse event timestamp")
                null
            }
        } else {
            null
        }
    }

    private inner class SendHeartbeatsTask : AsyncTask<URL, Instant, Int>() {
        override fun doInBackground(vararg urls: URL): Int? {
            Log.i(TAG, "Sending heartbeats...")

            // TODO: Use other bucket type when support for such a type has been implemented in aw-webui
            ri.createBucketHelper(bucket_id, "currentwindow")
            ri.createBucketHelper(unlock_bucket_id, "os.lockscreen.unlocks")
            lastUpdated = getLastEventTime()

            val usm = getUSM() ?: return 0

            // Store activities here that have had a RESUMED but not a PAUSED event.
            // (to handle out-of-order events)
            //val activeActivities = [];

            // TODO: Fix issues that occur when usage stats events are out of order (RESUME before PAUSED)
            var heartbeatsSent = 0
            // ★ 프라이빗 구간은 **처음부터 조회하지 않는다.**
            //
            //   워처를 멈추기만 하면 다시 켤 때 커서가 그 구간을 통째로 따라잡는다 —
            //   앱이 OS 에게 "기록 멈춰"라고 할 수는 없고 UsageStatsManager 는 읽기
            //   전용이기 때문이다. 읽고 버리는 것도 아니다. 읽으면 그 사이 창 제목이
            //   프로세스 메모리를 스친다.
            //
            //   이 한 줄이 앱 세션과 unlock 을 **둘 다** 막는다 — 아래 루프가 둘을 같이
            //   쓰기 때문이다. afk 는 InteractionWatcher 의 별도 경로라 계속 기록된다
            //   (명세: "폰을 쓰고 있었다는 사실은 남는다. 가려지는 것은 무엇을 했는지다").
            val privateMode = PrivateMode(context)
            privateMode.settleIfExpired()
            // (이 경로는 `useSessionBasedEvents=false` 일 때만 돈다. 지금 실사용은
            //  SessionEventWatcher 쪽이고 거기에 같은 가드가 있다 — 둘 다 막아 둔다.)
            // ★ 켜져 있는 동안에는 **조회 자체를 안 한다.** 커서만 밀면 그 시점 이후로는
            //   계속 수집되므로, 프라이빗이 켜져 있는 중에 수집이 한 번 돌면 그 뒤 구간이
            //   그대로 들어올 수 있다 — 앱 재설치로 서비스가
            //   재시작되면서 프라이빗 한가운데서 수집이 돌았다).
            //   끝난 구간은 아래 skipCursor 의 바닥값이 막는다.
            if (privateMode.isActive()) {
                Log.i(TAG, "프라이빗 — 이번 수집을 건너뛴다")
                return 0
            }
            val rawCursor = lastUpdated?.toEpochMilli() ?: 0L
            val cursor = privateMode.skipCursor(rawCursor)
            if (cursor != rawCursor) {
                Log.i(TAG, "프라이빗 구간을 건너뛴다")
            }
            val usageEvents = usm.queryEvents(cursor, Long.MAX_VALUE)
            nextEvent@ while(usageEvents.hasNextEvent()) {
                val event = UsageEvents.Event()
                usageEvents.getNextEvent(event)

                // Log screen unlock
                if(event.eventType !in arrayListOf(UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED)) {
                    if(event.eventType == UsageEvents.Event.KEYGUARD_HIDDEN){
                        val timestamp = DateTimeUtils.toInstant(java.util.Date(event.timeStamp))
                        // NOTE: getLastEventTime() returns the last time of an event from  the activity bucket(bucket_id)
                        // Therefore, if an unlock happens after last event from main bucket, unlock event will get sent twice.
                        // Fortunately not an issue because identical events will get merged together (see heartbeats)
                        ri.heartbeatHelper(unlock_bucket_id, timestamp, 0.0, JSONObject(), 0.0)
                    }
                    // Not sure which events are triggered here, so we use a (probably safe) fallback
                    //Log.d(TAG, "Rare eventType: ${event.eventType}, skipping")
                    continue@nextEvent
                }

                // Log activity
                val awEvent = Event.fromUsageEvent(event, context, includeClassname = true)
                val pulsetime: Double
                when(event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        // ACTIVITY_RESUMED: Activity was opened/reopened
                        pulsetime = 1.0
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        // ACTIVITY_PAUSED: Activity was moved to background
                        pulsetime = 24 * 60 * 60.0   // 24h, we will assume events should never grow longer than that
                    }
                    else -> {
                        Log.w(TAG, "This should never happen!")
                        continue@nextEvent
                    }
                }

                ri.heartbeatHelper(bucket_id, awEvent.timestamp, awEvent.duration, awEvent.data, pulsetime)
                if(heartbeatsSent % 100 == 0) {
                    publishProgress(awEvent.timestamp)
                }
                heartbeatsSent++
            }
            return heartbeatsSent
        }

        override fun onProgressUpdate(vararg progress: Instant) {
            lastUpdated = progress[0]
            // The below is useful in testing, but otherwise just noisy.
            //Toast.makeText(context, "Logging data, progress: $lastUpdated", Toast.LENGTH_LONG).show()
        }

        override fun onPostExecute(result: Int?) {
            Log.w(TAG, "Finished SendHeartbeatTask")
            // The below is useful in testing, but otherwise just noisy.
            /*
            if(result != 0) {
                Toast.makeText(context, "Completed logging of data! Logged events: $result", Toast.LENGTH_LONG).show()
            }
            */
        }
    }

    /***
     * Returns the number of events sent
     */
    @Deprecated(
        "Use sendHeartbeatsSuspend() from a coroutine scope. In session mode this is a no-op; " +
            "callers should switch to the suspend variant to avoid silent data loss.",
        ReplaceWith("sendHeartbeatsSuspend()")
    )
    fun sendHeartbeats() {
        if (!useSessionBasedEvents) {
            Log.w(TAG, "Starting SendHeartbeatTask (individual events)")
            SendHeartbeatsTask().execute()
        }
    }

    /**
     * Awaitable variant — use when the caller needs to wait for events to be persisted
     * before querying (e.g. widget refresh).
     *
     * In legacy mode (useSessionBasedEvents=false) this is intentionally a no-op because
     * SendHeartbeatsTask is not awaitable; callers on the legacy path should use sendHeartbeats().
     */
    suspend fun sendHeartbeatsSuspend() {
        if (useSessionBasedEvents) {
            sessionWatcher.sendSessionEventsSuspend()
        }
        // LT: afk 판정은 세션 이벤트 방식과 무관하게 항상 돌린다 — 서버가 노트북 afk 와
        // 나란히 놓고 중재하므로 빠지면 폰 시간이 통째로 과대 계상된다.
        withContext(Dispatchers.IO) { interactionWatcher.processSince() }
        // Legacy SendHeartbeatsTask path is not awaitable; skip for widget use case.
    }

    /**
     * Send session-based events for today only
     */
    /**
     * Enable or disable session-based events
     */
    fun setSessionBasedEvents(enabled: Boolean) {
        useSessionBasedEvents = enabled
        Log.i(TAG, "Session-based events ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Enable discrete event insertion mode (recommended for accuracy)
     */
    fun enableDiscreteEventMode() {
        setSessionBasedEvents(true)
        Log.i(TAG, "Switched to discrete event insertion mode (insert_event with pulsetime=0)")
    }

    /**
     * Enable heartbeat mode (traditional merging behavior)
     */
    fun enableHeartbeatMode() {
        setSessionBasedEvents(false)
        Log.i(TAG, "Switched to heartbeat mode (traditional event merging)")
    }

    /**
     * Check if currently using discrete events
     */
    fun isUsingDiscreteEvents(): Boolean = useSessionBasedEvents

}
