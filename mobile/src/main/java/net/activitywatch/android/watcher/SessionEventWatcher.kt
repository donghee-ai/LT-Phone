package net.activitywatch.android.watcher

import android.content.Context
import android.util.Log
import net.activitywatch.android.RustInterface
import net.activitywatch.android.privacy.PrivateMode
import net.activitywatch.android.data.AppSession
import net.activitywatch.android.parser.SessionParser
import net.activitywatch.android.utils.SessionUtils
import org.json.JSONObject
import org.threeten.bp.DateTimeUtils
import org.threeten.bp.Instant
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.*

const val SESSION_BUCKET_ID = "aw-watcher-android"
const val UNLOCK_BUCKET_ID = "aw-watcher-android-unlock"

class SessionEventWatcher(val context: Context) {
    private val ri = RustInterface(context)
    private val sessionParser = SessionParser(context)
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    var lastUpdated: Instant? = null

    companion object {
        const val TAG = "SessionEventWatcher"
        private val lock = java.util.concurrent.locks.ReentrantLock()
    }

    // queryEvents uses an inclusive lower bound, so replaying the last stored
    // session's start timestamp would duplicate that session on every run.
    private fun nextQueryStartTimestamp(): Long = (lastUpdated?.toEpochMilli()?.plus(1L)) ?: 0L

    suspend fun sendSessionEventsSuspend() {
        Log.w(TAG, "Starting SendSessionEventTask (awaitable)")
        withContext(Dispatchers.IO) {
            processEventsSinceLastUpdate()
            Log.w(TAG, "Finished SendSessionEventTask")
        }
    }

    private fun getLastEventTime(): Instant? {
        val events = ri.getEventsJSON(SESSION_BUCKET_ID, limit = 1)
        return if (events.length() == 1) {
            val lastEvent = events[0] as JSONObject
            val timestampString = lastEvent.getString("timestamp")
            try {
                val timeCreatedDate = isoFormatter.parse(timestampString)
                DateTimeUtils.toInstant(timeCreatedDate)
            } catch (e: ParseException) {
                Log.e(TAG, "Unable to parse event timestamp")
                null
            }
        } else if (events.length() == 0) {
            null  // normal on first run
        } else {
            Log.w(TAG, "Unexpected last-event result count")
            null
        }
    }

    /**
     * Synchronously process events since last update.
     * Returns the number of events sent.
     */
    fun processEventsSinceLastUpdate(): Int {
        if (!lock.tryLock()) {
            Log.i(TAG, "processEventsSinceLastUpdate already running, skipping concurrent call")
            return 0
        }
        try {
            return processEventsSinceLastUpdateLocked()
        } finally {
            lock.unlock()
        }
    }

    private fun processEventsSinceLastUpdateLocked(): Int {
        Log.i(TAG, "Processing session events...")

        // ── 프라이빗 모드 ────────────────────────────────────────────
        //
        // ★ **켜져 있는 동안에는 조회 자체를 안 한다.** 커서만 밀면 그 시점 *이후*는
        //   계속 수집되므로, 프라이빗 한가운데서 수집이 한 번 돌면 그 뒤 구간이 그대로
        //   들어온다.
        //
        // ★ 끝난 구간은 [PrivateMode.skipCursor] 의 **단조 증가 바닥값**이 막는다.
        //   워처를 멈추기만 하면 다시 켤 때 커서가 그 구간을 통째로 따라잡는다 —
        //   앱이 OS 에게 "기록 멈춰"라고 할 수는 없고 UsageStatsManager 는 읽기 전용이다.
        //   읽고 버리는 것도 아니다. 읽으면 그 사이 창 제목이 프로세스 메모리를 스친다.
        //
        // 이 한 곳이 **앱 세션과 unlock 을 둘 다** 막는다 — 아래가 둘을 같이 쓴다.
        // afk 는 InteractionWatcher 의 별도 경로라 계속 기록된다 (명세: "폰을 쓰고
        // 있었다는 사실은 남는다. 가려지는 것은 무엇을 했는지다").
        val privateMode = PrivateMode(context)
        privateMode.settleIfExpired()
        if (privateMode.isActive()) {
            Log.i(TAG, "프라이빗 — 이번 수집을 건너뛴다")
            return 0
        }

        // Create bucket for session events
        ri.createBucketHelper(SESSION_BUCKET_ID, "currentwindow")
        ri.createBucketHelper(UNLOCK_BUCKET_ID, "os.lockscreen.unlocks")

        lastUpdated = getLastEventTime()

        val rawStart = nextQueryStartTimestamp()
        val startTimestamp = privateMode.skipCursor(rawStart)
        if (startTimestamp != rawStart) {
            Log.i(TAG, "프라이빗 구간을 건너뛴다")
        }
        val sessions = sessionParser.parseUsageEventsSince(startTimestamp)
        val unlockTimestamps = sessionParser.parseUnlockEventsSince(startTimestamp)

        var eventsSent = 0

        for (session in sessions) {
            // Insert session as individual event
            insertSessionAsEvent(session)
            eventsSent++
        }
        
        for (timestamp in unlockTimestamps) {
            val instant = DateTimeUtils.toInstant(java.util.Date(timestamp))
            ri.heartbeatHelper(UNLOCK_BUCKET_ID, instant, 0.0, JSONObject(), 0.0)
        }
        
        Log.i(TAG, "Finished processing session events")
        return eventsSent
    }



    /**
     * Insert a single session as an individual event (not a heartbeat)
     */
    private fun insertSessionAsEvent(session: AppSession) {
        val startInstant = DateTimeUtils.toInstant(java.util.Date(session.startTime))
        val duration = session.durationSeconds
        val data = session.toEventData()

        // Use insertEvent method to insert as discrete event
        // This prevents merging behavior and treats each session as a separate event
        ri.insertEvent(SESSION_BUCKET_ID, startInstant, duration, data)

    }

    /**
     * Insert session as discrete event with specific timestamp and duration
     */
    fun insertSessionEvent(
        packageName: String,
        appName: String,
        className: String = "",
        startTime: Long,
        durationMs: Long
    ) {
        val session = AppSession(
            packageName = packageName,
            appName = appName,
            className = className,
            startTime = startTime,
            endTime = startTime + durationMs
        )

        ri.createBucketHelper(SESSION_BUCKET_ID, "currentwindow")
        insertSessionAsEvent(session)

    }

    /**
     * Clear all session events from the bucket (useful for testing)
     */
    fun clearSessionEvents() {
        // Note: There's no direct clear method in RustInterface
        // This is a placeholder for potential future implementation
        Log.w(TAG, "Clear session events not implemented - would require new RustInterface method")
    }

    /**
     * Get count of events in session bucket
     */
    fun getSessionEventCount(): Int {
        val events = ri.getEventsJSON(SESSION_BUCKET_ID)
        return events.length()
    }
}
