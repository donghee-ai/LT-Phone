/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.push

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "PushScheduler"
const val ACTION_PUSH_ALARM = "net.activitywatch.android.push.PUSH_ALARM"

/**
 * 5분마다 서버로 밀어 넣는다.
 *
 * **WorkManager 를 쓰지 않는다** — 주기 작업 최소 간격이 15분이라 5분을 못 맞춘다.
 * 대신 같은 저장소의 `SyncScheduler` 와 **같은 구조**를 쓴다:
 *
 *  - Handler 체인이 평소 주기를 돌리고 (foreground 서비스가 사는 동안),
 *  - AlarmManager 가 폴백으로 살아남는다 (OEM 이 서비스를 죽여도 알람은 남는다),
 *  - 화면이 꺼질 때 한 번 더 보낸다 (Doze 에 들어가기 전에 마지막 세션을 내보낸다).
 *
 * `BackgroundService` 가 이미 foreground 알림을 띄우고 있어 추가 비용이 없다.
 *
 * ★ 배터리: **폰을 깨워서 보내지 않는다.** 데이터는 버킷에 쌓이고 커서로 따라잡으므로
 *   늦게 보내도 빠지는 것은 없다. 폰의 상태가 바뀌는 것은 폰이 깨어 있을 때뿐이고,
 *   그때는 Handler 체인·non-wakeup 알람이 어차피 돈다. 잠든 폰을 5분마다 깨워 봐야
 *   보낼 새 데이터가 거의 없다.
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

    // ── 화면 꺼짐 push ──────────────────────────────────────────────
    //
    // 화면이 꺼지면 WebWatcher 가 열린 브라우저 세션을 닫아 버킷에 쓴다. 그 직후 한 번
    // 보내 두면 Doze 동안 서버가 마지막 세션을 모른 채로 있지 않는다.
    // ★ 켜고 끄기를 반복하면 매번 보내게 되므로 [SCREEN_OFF_MIN_GAP_MS] 로 죈다.
    private var lastScreenOffPushAt: Long? = null
    private val screenOffPush = Runnable {
        if (isRunning) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { pushOnceIn(context) }
                    .onFailure { Log.w(TAG, "화면 꺼짐 push 실패") }
            }
        }
    }
    private var screenOffReceiver: BroadcastReceiver? = null

    private fun registerScreenOff() {
        if (screenOffReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_OFF) return
                val now = SystemClock.elapsedRealtime()
                if (!PushDebounce.shouldPush(now, lastScreenOffPushAt, SCREEN_OFF_MIN_GAP_MS)) return
                lastScreenOffPushAt = now
                // 조금 늦춘다 — WebWatcher 의 세션 저장(다른 스레드의 하트비트)이 먼저 끝나게.
                // 그 사이 CPU 가 잠들면 다음에 깰 때 보낸다. 그것도 괜찮다 (깨우지 않는다).
                handler.removeCallbacks(screenOffPush)
                handler.postDelayed(screenOffPush, SCREEN_OFF_PUSH_DELAY_MS)
            }
        }
        // 시스템 브로드캐스트는 NOT_EXPORTED 로도 받는다 (Android 14 의 플래그 의무 대응).
        ContextCompat.registerReceiver(
            context, r, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenOffReceiver = r
    }

    private fun unregisterScreenOff() {
        handler.removeCallbacks(screenOffPush)
        screenOffReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        screenOffReceiver = null
    }

    fun start() {
        if (isRunning) {
            Log.w(TAG, "이미 실행 중")
            return
        }
        if (!settings.enabled) {
            Log.i(TAG, "push 가 꺼져 있다 — 스케줄러를 띄우지 않는다")
            // 꺼진 채로 예전 알람이 남아 있으면 주기마다 수신자를 띄워 Skipped 만 찍는다.
            // 끈 것은 사용자의 뜻이므로 폴백도 거둔다. 다시 켜면 start() 가 새로 건다.
            cancelAlarm()
            return
        }
        Log.i(TAG, "push 스케줄러 시작")
        isRunning = true
        // 부팅·서비스 기동 직후에는 aw-server 가 아직 안 떴을 수 있어 1분 늦춘다.
        handler.postDelayed(runnable, 60_000L)
        scheduleAlarm()
        registerScreenOff()
    }

    fun stop() {
        Log.i(TAG, "push 스케줄러 정지 (AlarmManager 폴백은 남겨 둔다)")
        isRunning = false
        handler.removeCallbacks(runnable)
        unregisterScreenOff()
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
        //
        // ★ ELAPSED_REALTIME (non-wakeup). 예전에는 _WAKEUP 이라 잠든 폰을 주기마다 깨웠다.
        //   non-wakeup 알람은 폰이 잠든 동안 미뤄졌다가 **다음에 깰 때 한 번** 배달된다 —
        //   그때가 바로 새 데이터가 생기는 때다. 서비스가 죽어도 되살리는 폴백 역할은 같다.
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + interval,
            interval,
            getPendingIntent()
        )
        Log.i(TAG, "AlarmManager 폴백 등록 (non-wakeup)")
    }

    private fun cancelAlarm() {
        runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(getPendingIntent())
        }
    }

    companion object {
        /** 화면 꺼짐 push 의 최소 간격. 켜고 끄기를 반복해도 1분에 한 번만 보낸다. */
        const val SCREEN_OFF_MIN_GAP_MS = 60_000L

        /** 화면 꺼짐 뒤 보내기까지 기다리는 시간 (WebWatcher 세션 저장이 먼저 끝나게). */
        private const val SCREEN_OFF_PUSH_DELAY_MS = 3_000L

        /**
         * 한 번에 한 전송만. Handler 체인·알람·화면 꺼짐이 겹쳐 같은 커서로 두 번 보내면
         * 서버가 받아 주기는 하지만(멱등) 망과 배터리를 두 번 쓴다.
         */
        private val inFlight = AtomicBoolean(false)

        /**
         * 한 번 전송한다. 알람 수신자·Handler 체인·화면 꺼짐이 공유하는 단일 진입점.
         */
        fun pushOnceIn(context: Context) {
            if (!inFlight.compareAndSet(false, true)) {
                Log.i(TAG, "이미 전송 중 — 이번 것은 건너뜀")
                return
            }
            try {
                val r = PushSender(context).pushOnce()
                when (r) {
                    is PushSender.Result.Fatal ->
                        // 401/404/413/400 은 재시도해도 똑같다. 배터리만 먹으므로 남기고 멈춘다.
                        Log.e(TAG, "전송 중단 (HTTP ${r.code})")
                    is PushSender.Result.Retry ->
                        Log.w(TAG, "일시적 전송 실패 — 다음 주기에 다시 시도")
                    else -> Unit
                }
            } finally {
                inFlight.set(false)
            }
        }
    }

}

/** 화면 꺼짐 push 를 거를지. 순수 함수라 JVM 테스트가 된다. */
internal object PushDebounce {
    /**
     * @param nowElapsed 지금 (`SystemClock.elapsedRealtime()`)
     * @param lastAt 지난번에 보낸 때. 한 번도 안 보냈으면 null
     */
    fun shouldPush(nowElapsed: Long, lastAt: Long?, minGapMs: Long): Boolean {
        if (lastAt == null) return true
        // 재부팅 등으로 기준이 되감겼으면 보낸다 — 막아 둘 이유가 없다.
        if (nowElapsed < lastAt) return true
        return nowElapsed - lastAt >= minGapMs
    }
}
