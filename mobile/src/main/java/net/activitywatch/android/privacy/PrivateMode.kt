/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.privacy

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 프라이빗 모드의 **로컬 단일 원본**.
 *
 * 요구가 명확했다 — *"워처가 도는 시점부터 모든 정보를 수집하기에 워처 단에서 막아야
 * 의미가 있다. 사실 뷰에서만 안 보이게랑 다를 게 없다."* 그래서 표시 단계에서 가리는
 * 것은 만들지 않는다. 이 클래스는 **아예 수집하지 않기 위한** 상태만 들고 있다.
 *
 * ## 왜 "남은 시간"이 아니라 절대 시각인가
 *
 * `untilMs` 는 **끝나는 시각**이다. "남은 분"으로 저장하면 앱이 죽어 있는 동안 시간이
 * 흐르지 않아 영원히 켜진 채가 된다. 절대 시각이면 앱이 죽었다 살아나도, 알람이 안
 * 울려도, 서버를 못 만나도 **자기 시계만으로** 언제 풀릴지 안다.
 *
 * ## 충돌 규칙 — 더 사적인 쪽이 이긴다
 *
 * 로컬과 서버가 다르면 `max` 를 쓴다. *"최신이 이긴다"* 로 하면 **늦게 도착한 서버
 * 응답이 방금 켠 프라이빗을 조용히 푼다.** 방향이 반대인 실패이고, 조용해서 더 나쁘다.
 * 끄기만 예외다 — 사람이 명시적으로 끈 것이므로 즉시 반영한다.
 *
 * ## 지나간 구간을 남기는 이유
 *
 * 타일을 켠 시점과 워처가 실제로 멈춘 시점 사이(최대 한 주기)에 들어온 이벤트를 나중에
 * 지워야 한다. 폰의 aw-server 는 `127.0.0.1` 바인딩이라 외부 서버가 대신 지울 수 없다.
 * **앱이 직접 정리해야 한다.**
 */
class PrivateMode(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── 지금 상태 ────────────────────────────────────────────────────

    /** 프라이빗이 끝나는 **절대 시각**(epoch 밀리초). 과거이거나 0 이면 꺼진 것이다. */
    val untilMs: Long get() = prefs.getLong(KEY_UNTIL, 0L)

    /** 지금 구간이 시작된 시각. 지연분 정리에 쓴다. */
    val startedMs: Long get() = prefs.getLong(KEY_STARTED, 0L)

    fun isActive(nowMs: Long = System.currentTimeMillis()): Boolean = untilMs > nowMs

    /** 타일 라벨용. 켜져 있지 않으면 0. */
    fun remainingMinutes(nowMs: Long = System.currentTimeMillis()): Int {
        val left = untilMs - nowMs
        if (left <= 0L) return 0
        // 올림한다 — "1분 남음"이 0 으로 보이면 사람이 꺼진 줄 안다.
        return ((left + 59_999L) / 60_000L).toInt()
    }

    // ── 켜고 끄기 ────────────────────────────────────────────────────

    /**
     * 지금부터 `minutes` 분 동안 켠다. 이미 켜져 있으면 **더 늦은 쪽**을 남긴다.
     *
     * @return 확정된 끝 시각(epoch 밀리초)
     */
    fun begin(minutes: Int, nowMs: Long = System.currentTimeMillis()): Long {
        val requested = nowMs + minutes.coerceIn(1, MAX_MINUTES) * 60_000L
        val until = maxOf(requested, untilMs)
        // 다시 켰으니 "끔 대기"는 의미가 없다.
        val edit = prefs.edit().putLong(KEY_UNTIL, until).putBoolean(KEY_PENDING_END, false)
        // 이어서 연장한 것이면 시작 시각은 그대로 둔다 — 정리 범위가 줄면 안 된다.
        if (!isActive(nowMs)) edit.putLong(KEY_STARTED, nowMs)
        edit.apply()
        Log.i(TAG, "프라이빗 시작")
        return until
    }

    /**
     * 사람이 명시적으로 껐다. 지나간 구간으로 넘긴다.
     *
     * ★ **끈 것은 서버가 확인해 줄 때까지 붙잡는다** ([pendingEnd]). 안 그러면 서버에
     *   아직 "켜짐"이 남아 있는 동안 폴링이 그걸 읽어 **방금 끈 프라이빗을 되살린다.**
     *   명세가 *"끄기는 예외다 — 사람이 명시적으로 끈 것이므로 즉시 반영한다"* 라고
     *   적어 둔 것이 이 방향이다. `max` 규칙(더 사적인 쪽이 이긴다)은 **켜는 쪽에만**
     *   적용된다.
     */
    fun end(nowMs: Long = System.currentTimeMillis()) {
        if (untilMs != 0L) {
            val endMs = minOf(untilMs, nowMs)
            recordSpan(startedMs, endMs)
            raiseCursorFloor(endMs)
        }
        prefs.edit()
            .putLong(KEY_UNTIL, 0L)
            .putLong(KEY_STARTED, 0L)
            .putBoolean(KEY_PENDING_END, true)
            .apply()
        Log.i(TAG, "프라이빗 종료 (사람이 끔)")
    }

    /** 껐는데 서버가 아직 모른다. 이 동안은 서버 상태를 **무시한다**. */
    val pendingEnd: Boolean get() = prefs.getBoolean(KEY_PENDING_END, false)

    /** 서버가 "꺼짐"을 돌려줬다 — 이제 서버를 다시 믿어도 된다. */
    fun clearPendingEnd() {
        if (pendingEnd) {
            prefs.edit().putBoolean(KEY_PENDING_END, false).apply()
            Log.i(TAG, "서버가 종료를 확인했다")
        }
    }

    /**
     * 시간이 다 되어 저절로 풀렸는지 확인하고, 풀렸으면 구간을 넘긴다.
     *
     * 알람(`PrivateExpiryReceiver`)이 안 울려도 **워처가 매 수집 때 이걸 부르면** 풀린다.
     * 이중 방어다 — 열린 구간을 두면 앱이 죽었을 때 영원히 켜진 채가 된다.
     */
    fun settleIfExpired(nowMs: Long = System.currentTimeMillis()) {
        val until = untilMs
        if (until != 0L && until <= nowMs) {
            recordSpan(startedMs, until)
            raiseCursorFloor(until)
            prefs.edit().putLong(KEY_UNTIL, 0L).putLong(KEY_STARTED, 0L).apply()
            Log.i(TAG, "프라이빗 만료 — 워처 재개")
        }
    }

    /**
     * 서버가 알려준 끝 시각과 합친다. **더 사적인 쪽이 이긴다.**
     *
     * @param serverUntilMs 서버가 준 `until_ts`(밀리초). 꺼져 있으면 0
     * @param serverEnded   서버에서 **사람이 명시적으로 껐다**면 true — 이때만 즉시 푼다
     */
    fun mergeServer(serverUntilMs: Long, serverEnded: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (serverEnded) {
            if (untilMs != 0L) end(nowMs)
            return
        }
        // ★ 방금 껐는데 서버가 아직 모른다. 그 낡은 "켜짐"으로 되살리지 않는다.
        if (pendingEnd) return
        if (serverUntilMs <= nowMs) return          // 서버는 꺼져 있다 — 로컬을 건드리지 않는다
        if (serverUntilMs <= untilMs) return        // 로컬이 이미 더 길다
        val edit = prefs.edit().putLong(KEY_UNTIL, serverUntilMs)
        if (!isActive(nowMs)) edit.putLong(KEY_STARTED, nowMs)
        edit.apply()
        Log.i(TAG, "서버 프라이빗 반영")
    }

    // ── 커서 건너뛰기 ────────────────────────────────────────────────

    /**
     * OS 로그를 커서로 따라잡는 워처(`UsageStatsWatcher`)가 **조회를 시작할 시각**.
     *
     * ★ 읽고 버리는 게 아니라 **처음부터 안 읽는다.** 워처를 멈추기만 하면 다시 켰을 때
     *   커서가 그 구간을 통째로 따라잡는다 — 앱이 OS 에게 "기록 멈춰"라고 할 수는
     *   없고 `UsageStatsManager` 는 읽기 전용이기 때문이다.
     *
     *       ❌ queryEvents(옛날커서, now)          → 프라이빗 구간이 딸려 들어온다
     *       ✅ cursor = max(cursor, 프라이빗_끝)    ← 먼저 민다
     *
     *   읽고 버리면 그 사이 폰 메모리·로그에 창 제목이 스친다.
     *
     * ★ 정직하게: **OS 로그 자체는 폰에 남는다.** 우리가 안 읽기로 하는 것뿐이라, 사용
     *   통계 권한을 가진 다른 앱은 볼 수 있다. PC 의 AW 로컬 DB 와 같은 부류다.
     */
    fun skipCursor(cursorMs: Long, nowMs: Long = System.currentTimeMillis()): Long {
        var out = maxOf(cursorMs, cursorFloorMs)
        if (isActive(nowMs)) out = maxOf(out, nowMs)
        return out
    }

    /**
     * **여태 지나간 프라이빗의 가장 늦은 끝.** 커서는 여기보다 앞으로 못 돌아간다.
     *
     * ★ 예전에는 [spans] 를 훑어 이 값을 냈는데, **[PrivateSweeper] 가 청소를 마치면
     *   그 구간을 목록에서 지운다.** 그러면 다음 수집이 커서를 그대로 되돌려 OS 로그에서
     *   프라이빗 구간을 **통째로 다시 긁어왔다** — 지웠던 것이 되살아났다.
     *
     *   그렇지 않으면 다음 수집에서 OS 로그를 다시 읽어 삭제한 이벤트가 되살아날 수 있다.
     *
     *   그래서 **단조 증가하는 바닥값**을 따로 둔다. 청소 목록과 무관하고, 줄지 않는다.
     */
    private val cursorFloorMs: Long get() = prefs.getLong(KEY_CURSOR_FLOOR, 0L)

    private fun raiseCursorFloor(endMs: Long) {
        if (endMs > cursorFloorMs) prefs.edit().putLong(KEY_CURSOR_FLOOR, endMs).apply()
    }

    // ── 지나간 구간 (지연분 정리용) ──────────────────────────────────

    /** `(시작, 끝)` 밀리초 쌍. 오래된 것부터. */
    fun spans(): List<Pair<Long, Long>> {
        val raw = prefs.getString(KEY_SPANS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val a = o.optLong("a"); val b = o.optLong("b")
                if (a in 1 until b) Pair(a, b) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "구간 목록 해석 실패 — 비운다")
            prefs.edit().remove(KEY_SPANS).apply()
            emptyList()
        }
    }

    /** 정리가 끝난 구간을 목록에서 뺀다. */
    fun forgetSpan(span: Pair<Long, Long>) {
        writeSpans(spans().filterNot { it == span })
    }

    private fun recordSpan(startMs: Long, endMs: Long) {
        if (startMs <= 0L || endMs <= startMs) return
        // 오래된 것부터 버린다. 정리는 곧 돌므로 길게 들고 있을 이유가 없다.
        writeSpans((spans() + Pair(startMs, endMs)).takeLast(MAX_SPANS))
    }

    private fun writeSpans(list: List<Pair<Long, Long>>) {
        val arr = JSONArray()
        for ((a, b) in list) arr.put(JSONObject().put("a", a).put("b", b))
        prefs.edit().putString(KEY_SPANS, arr.toString()).apply()
    }

    companion object {
        private const val TAG = "PrivateMode"
        private const val PREFS_NAME = "LTPrivate"
        private const val KEY_UNTIL = "untilMs"
        private const val KEY_STARTED = "startedMs"
        private const val KEY_SPANS = "spans"
        private const val KEY_PENDING_END = "pendingEnd"
        private const val KEY_CURSOR_FLOOR = "cursorFloorMs"

        /** 한 번에 켤 수 있는 최대. 무한히 켜 두는 것을 막는다. */
        const val MAX_MINUTES = 12 * 60

        /** 타일을 한 번 누르면 켜지는 기본 길이. */
        const val DEFAULT_MINUTES = 60

        private const val MAX_SPANS = 32
    }
}
