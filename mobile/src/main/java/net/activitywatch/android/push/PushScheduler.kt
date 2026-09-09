/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.push

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "PushScheduler"
const val ACTION_PUSH_ALARM = "net.activitywatch.android.push.PUSH_ALARM"

/**
 * 5분마다 서버로 밀어 넣는다.
 *
 * **WorkManager 를 쓰지 않는다** — 주기 작업 최소 간격이 15분이라 5분을 못 맞춘다.
 * 대신 같은 저장소의 `SyncScheduler` 와 **같은 구조**를 쓴다:
 *
 *  - Handler 체인이 평소 주기를 돌리고,
 *  - AlarmManager 가 폴백으로 살아남는다 (OEM 이 서비스를 죽여도 알람은 남는다).
 *
 * `BackgroundService` 가 이미 foreground 알림을 띄우고 있어 추가 비용이 없다.
 */
class PushScheduler(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val settings = LTSettings(context)
    private var isRunning = false

    // 자기 자신을 다시 예약해야 하는데, 프로퍼티 초기화식에서는 자기 참조가 안 된다.
    // 그래서 실제 동작은 tick() 으로 빼고 여기서는 호출만 한다.
    private val runnable = Runnable { tick() }

    private fun tick() {
        if (!isRunning) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { pushOnceIn(context) }
                .onFailure { Log.w(TAG, "push 실패") }
            // 다음 실행은 이번 것이 끝난 뒤에 잡는다 — 겹쳐 도는 것을 막는다.
            if (isRunning) handler.postDelayed(runnable, intervalMs())
        }
    }

    private fun intervalMs(): Long = settings.pushIntervalMinutes * 60_000L

    fun start() {
        if (isRunning) {
            Log.w(TAG, "이미 실행 중")
            return
        }
        if (!settings.enabled) {
            Log.i(TAG, "push 가 꺼져 있다 — 스케줄러를 띄우지 않는다")
            return
        }
        Log.i(TAG, "push 스케줄러 시작")
        isRunning = true
        // 부팅·서비스 기동 직후에는 aw-server 가 아직 안 떴을 수 있어 1분 늦춘다.
        handler.postDelayed(runnable, 60_000L)
        scheduleAlarm()
    }

    fun stop() {
        Log.i(TAG, "push 스케줄러 정지 (AlarmManager 폴백은 남겨 둔다)")
        isRunning = false
        handler.removeCallbacks(runnable)
        // SyncScheduler 와 같은 이유로 알람은 취소하지 않는다: 서비스가 OS 에 죽으면
        // (START_STICKY 재시작을 억제하는 OEM 포함) 알람만이 되살릴 수단이다.
    }

    private fun getPendingIntent(): PendingIntent {
        val intent = Intent(ACTION_PUSH_ALARM).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleAlarm() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val interval = intervalMs()
        // setInexactRepeating 으로 충분하다 — 커서로 따라잡는 구조라 늦는 것은 *도착*이지
        // *기록*이 아니다. 정확한 알람은 배터리만 먹고 Doze 와 싸운다.
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + interval,
            interval,
            getPendingIntent()
        )
        Log.i(TAG, "AlarmManager 폴백 등록")
    }

    companion object {
        /**
         * 한 번 전송한다. 알람 수신자와 Handler 체인이 공유하는 단일 진입점.
         */
        fun pushOnceIn(context: Context) {
            val r = PushSender(context).pushOnce()
            when (r) {
                is PushSender.Result.Fatal ->
                    // 401/404/413/400 은 재시도해도 똑같다. 배터리만 먹으므로 남기고 멈춘다.
                    Log.e(TAG, "전송 중단 (HTTP ${r.code})")
                is PushSender.Result.Retry ->
                    Log.w(TAG, "일시적 전송 실패 — 다음 주기에 다시 시도")
                else -> Unit
            }
        }
    }

}
