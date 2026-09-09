/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.watcher

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import net.activitywatch.android.RustInterface
import org.json.JSONObject
import org.threeten.bp.DateTimeUtils
import org.threeten.bp.Instant
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

const val AFK_BUCKET_ID = "aw-watcher-android-afk"

/**
 * 화면·잠금 상태로 afk / not-afk 를 판정해 노트북과 **같은 언어**로 기록한다.
 *
 * `UsageStatsManager` 만 쓴다 — BroadcastReceiver 도 접근성 권한도 필요 없고,
 * 커서로 과거분까지 따라잡을 수 있다.
 *
 * ## 판정 규칙: 화면이 켜져 있고 **잠금이 풀려 있으면** not-afk
 *
 * `USER_INTERACTION` 발생 횟수는 실제 사용 시간의 커버리지를 나타내지 않는다. 게임이나 영상처럼
 * 화면 전환이 적은 앱에서는 이벤트가 거의 없어 실제 사용 구간이 빠질 수 있다.
 * `KEYGUARD_HIDDEN`(잠금 해제)과 `SCREEN_INTERACTIVE`를 함께 사용하면 화면만 켠 경우는
 * 걸러내면서 잠금 해제 후의 연속 사용을 놓치지 않는다.
 *
 * 화면이 켜진 채 잠금이 풀려 있는데 몇 시간 방치하는 경우는 과대 계상될 수 있지만,
 * 폰은 보통 자동으로 화면이 꺼진다 — 그쪽 위험이 훨씬 작다.
 */
class InteractionWatcher(private val context: Context) {

    private val ri = RustInterface(context)
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    companion object {
        const val TAG = "InteractionWatcher"

        /** 마지막 신호 후 이 시간이 지나면 afk. 데스크톱 aw-watcher-afk 와 동일하게 3분. */
        const val AFK_TIMEOUT_MS = 180_000L

        /**
         * 커서보다 이만큼 **뒤로 물러서서** 이벤트를 다시 읽는다.
         *
         * 상태(화면 켜짐·잠금 해제)는 마지막 전환 이벤트에만 담겨 있다. 커서 이후만
         * 조회하면 게임 한 판처럼 전환이 없는 구간에서 마크가 0개가 되어 **아무것도
         * 기록하지 못하고 커서도 안 움직인다**.
         * 뒤로 물러서서 상태를 재구성한 뒤, 커서 이후만 잘라 쓴다.
         */
        const val LOOKBACK_MS = 6 * 60 * 60 * 1000L

        private val lock = java.util.concurrent.locks.ReentrantLock()
    }

    /** 화면/잠금 상태가 바뀐 지점. null 은 "이 이벤트가 그 상태를 바꾸지 않음". */
    private data class Mark(val ts: Long, val screenOn: Boolean?, val unlocked: Boolean?)

    fun processSince(): Int {
        if (!lock.tryLock()) {
            Log.i(TAG, "이미 실행 중이라 이번 호출은 건너뛴다")
            return 0
        }
        try {
            ri.createBucketHelper(AFK_BUCKET_ID, "afkstatus")

            val now = System.currentTimeMillis()
            val cursor = lastEventEnd()
            if (cursor >= now) {
                Log.d(TAG, "커서가 현재 이후다 — 할 일 없음")
                return 0
            }

            // 상태 재구성을 위해 커서보다 뒤에서부터 읽는다.
            val from = if (cursor == 0L) 0L else maxOf(0L, cursor - LOOKBACK_MS)
            val marks = collectMarks(from, now)
            if (marks.isEmpty()) {
                Log.d(TAG, "구간 안에 화면/잠금 이벤트가 없다")
                return 0
            }

            // 지금보다 AFK_TIMEOUT 이전까지는 판정이 더 이상 바뀔 수 없다 — 거기까지 확정한다.
            // 이걸 안 하면 폰을 계속 쓰는 동안 구간이 닫히지 않아 버킷이 통째로 멈춘다.
            val finalizeUntil = now - AFK_TIMEOUT_MS
            val spans = buildSpans(marks, finalizeUntil)

            // 커서 이후만 기록한다. 재구성용으로 읽은 과거분은 버린다.
            var written = 0
            for (s in spans) {
                val start = maxOf(s.start, cursor)
                if (s.end <= start) continue
                val data = JSONObject().put("status", if (s.notAfk) "not-afk" else "afk")
                val instant = DateTimeUtils.toInstant(java.util.Date(start))
                // insertEvent 는 pulsetime 0 이라 병합되지 않는다 — 구간은 우리가 이미 계산했다.
                ri.insertEvent(AFK_BUCKET_ID, instant, (s.end - start) / 1000.0, data)
                written++
            }
            Log.i(TAG, "afk 구간 처리 완료")
            return written
        } catch (e: Exception) {
            Log.e(TAG, "afk 구간 처리 실패")
            return 0
        } finally {
            lock.unlock()
        }
    }

    /** 우리 버킷의 마지막 이벤트 끝 시각. 없으면 0 (OS 가 갖고 있는 만큼 전부 따라잡는다). */
    private fun lastEventEnd(): Long {
        // ★ limit 를 반드시 명시한다. RustInterface 의 기본값 0 은 JNI 에서 Some(0) →
        //   SQL `LIMIT 0` 이 되어 **이벤트가 0건 온다.** 조용히 빈 결과가 되는 함정이다.
        val events = ri.getEventsJSON(AFK_BUCKET_ID, limit = 1)
        if (events.length() == 0) return 0L
        return try {
            val e = events[0] as JSONObject
            val start = isoFormatter.parse(e.getString("timestamp"))?.time ?: return 0L
            start + ((e.optDouble("duration", 0.0) * 1000).toLong())
        } catch (e: ParseException) {
            Log.e(TAG, "마지막 이벤트 시각을 읽지 못했다")
            0L
        }
    }

    private fun collectMarks(from: Long, to: Long): List<Mark> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val out = ArrayList<Mark>()
        val events = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        while (events.getNextEvent(e)) {
            when (e.eventType) {
                // 화면이 꺼지면 잠금 여부와 무관하게 사용 중이 아니다.
                UsageEvents.Event.SCREEN_NON_INTERACTIVE ->
                    out.add(Mark(e.timeStamp, false, false))
                UsageEvents.Event.SCREEN_INTERACTIVE ->
                    out.add(Mark(e.timeStamp, true, null))
                UsageEvents.Event.KEYGUARD_HIDDEN ->
                    out.add(Mark(e.timeStamp, true, true))
                UsageEvents.Event.KEYGUARD_SHOWN ->
                    out.add(Mark(e.timeStamp, null, false))
                // 터치가 잡히면 확실히 쓰는 중이다. 잠금 해제 이벤트를 놓친 경우의 보정.
                UsageEvents.Event.USER_INTERACTION ->
                    out.add(Mark(e.timeStamp, true, true))
            }
        }
        out.sortBy { it.ts }
        return out
    }

    private data class Span(val start: Long, val end: Long, val notAfk: Boolean)

    /**
     * 상태 구간을 만든다. [finalizeUntil] 까지만 확정해서 내보낸다 —
     * 그 이후는 아직 이벤트가 더 올 수 있어 길이가 달라질 수 있다.
     */
    private fun buildSpans(marks: List<Mark>, finalizeUntil: Long): List<Span> {
        val spans = ArrayList<Span>()
        var screenOn = false
        var unlocked = false
        var spanStart = marks.first().ts
        var spanNotAfk = false

        for (m in marks) {
            if (m.screenOn != null) screenOn = m.screenOn
            if (m.unlocked != null) unlocked = m.unlocked

            val s = screenOn && unlocked
            if (s != spanNotAfk) {
                if (m.ts > spanStart) spans.add(Span(spanStart, m.ts, spanNotAfk))
                spanStart = m.ts
                spanNotAfk = s
            }
        }

        // 마지막 구간을 확정 가능한 지점까지 닫는다.
        if (finalizeUntil > spanStart) {
            spans.add(Span(spanStart, finalizeUntil, spanNotAfk))
        }
        return spans
    }
}
