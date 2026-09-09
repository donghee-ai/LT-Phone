/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.privacy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "PrivateScheduler"

/**
 * 서버의 프라이빗 상태를 따라가고, 지나간 구간의 지연분을 치운다.
 *
 * `PushScheduler` 와 같은 구조(Handler 체인)를 쓰되 **알람 폴백은 두지 않는다.**
 * 폴링이 늦어도 로컬 상태가 이미 정답이기 때문이다 — 폴링은 *서버 웹에서 켠 것*을
 * 폰이 따라가기 위한 것이지, 폰에서 켠 것을 지키는 장치가 아니다. 폰에서 켠 것은
 * [PrivateMode] 와 [PrivateExpiryReceiver] 가 알아서 지킨다.
 */
class PrivateScheduler(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val mode = PrivateMode(context)
    private val client = PrivateClient(context)
    private val sweeper = PrivateSweeper(context)
    private var isRunning = false
    private var intervalMs = DEFAULT_INTERVAL_MS

    /** 직전 폴링에서 본 서버의 끝 시각. "서버에서 사람이 껐다"를 알아내는 데만 쓴다. */
    private var lastServerUntilMs = 0L

    private val runnable = Runnable { tick() }

    private fun tick() {
        if (!isRunning) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { poll() }.onFailure { Log.w(TAG, "폴링 실패") }
            if (isRunning) handler.postDelayed(runnable, intervalMs)
        }
    }

    private fun poll() {
        // 1) 시간이 다 됐으면 먼저 푼다 — 알람이 안 울렸을 수 있다 (이중 방어).
        val wasActive = mode.isActive()
        mode.settleIfExpired()
        if (wasActive && !mode.isActive()) PrivateGate.onLocalChange(context)

        // 2) 껐는데 서버가 아직 모르면 **될 때까지 다시 알린다.**
        //    한 번 실패하고 마는 구조면, 서버에 남은 "켜짐"을 폴링이 읽어 방금 끈 것을
        //    되살릴 수 있다.
        if (mode.pendingEnd && client.isConfigured()) {
            if (client.notifyEnd()) Log.i(TAG, "종료 재통지 성공")
        }

        // 3) 서버가 뭐라 하는지 본다. 못 물어보면 로컬을 그대로 둔다.
        if (client.isConfigured()) {
            val server = client.fetch()
            if (server != null) {
                if (!server.active) {
                    // 서버도 꺼짐을 확인해 줬다. 이제 서버를 다시 믿어도 된다.
                    mode.clearPendingEnd()
                }

                // ★ 서버 웹에서 **사람이 껐다**를 알아낸다.
                //
                //   서버가 주는 것은 `active` 와 `until_ts` 뿐이라 "원래 꺼져 있었다"와
                //   "방금 껐다"가 겉으로 같다. 그래서 **직전에 본 서버 상태**와 비교한다:
                //   아까는 아직 안 끝난 구간이 켜져 있었는데 지금 꺼졌다면, 시간이 되어
                //   풀린 것이 아니라 **누가 끈 것**이다.
                //
                //   이걸 안 하면 서버 웹에서 끈 것이 폰에 영영 안 온다. 명세의
                //   *"끄기는 예외다 — 사람이 명시적으로 끈 것이므로 즉시 반영한다"* 가
                //   이 방향이다.
                val serverEndedByHuman = !server.active &&
                    lastServerUntilMs > System.currentTimeMillis()
                if (serverEndedByHuman) Log.i(TAG, "서버에서 껐다 — 폰도 푼다")
                lastServerUntilMs = if (server.active) server.untilMs else 0L

                // ★ 서버가 꺼져 있다고 해서 로컬을 풀지 않는다. 더 사적인 쪽이 이긴다.
                //   반대로 **방금 로컬에서 끈 것**은 mergeServer 가 pendingEnd 로 지킨다.
                val wasLocalActive = mode.isActive()
                mode.mergeServer(server.untilMs, serverEnded = serverEndedByHuman)
                if (wasLocalActive && !mode.isActive()) PrivateGate.onLocalChange(context)
                if (server.pollSec > 0) intervalMs = server.pollSec * 1000L
                if (kotlin.math.abs(server.skewMs) > SKEW_WARN_MS) {
                    Log.w(TAG, "폰 시계가 서버과 ${server.skewMs / 1000}초 차이 난다")
                }
            }
        }

        // 4) 지나간 구간의 지연분을 치운다. 프라이빗이 꺼져 있을 때만 — 켜져 있는 동안은
        //    아직 구간이 안 닫혔고, 닫히지 않은 구간을 지우면 경계가 흔들린다.
        if (!mode.isActive()) {
            runCatching { sweeper.sweep(mode) }.onFailure { Log.w(TAG, "지연분 정리 실패") }
        }
    }

    /** 타일이 눌렸다. 서버에 알리고(실패해도 로컬은 이미 확정) 즉시 한 번 돈다. */
    fun onLocalChange() {
        val active = mode.isActive()
        CoroutineScope(Dispatchers.IO).launch {
            if (client.isConfigured()) {
                val ok = if (active) {
                    client.notifyBegin(mode.remainingMinutes().coerceAtLeast(1))
                } else {
                    client.notifyEnd()
                }
                if (!ok) Log.w(TAG, "서버 통지 실패 — 로컬 상태는 그대로 유효하다")
            }
            if (!active) {
                runCatching { sweeper.sweep(mode) }.onFailure { Log.w(TAG, "지연분 정리 실패") }
            }
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        handler.postDelayed(runnable, FIRST_DELAY_MS)
        Log.i(TAG, "프라이빗 폴링 시작")
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(runnable)
    }

    companion object {
        /** 서버가 `poll_sec` 를 주기 전까지 쓰는 값. */
        private const val DEFAULT_INTERVAL_MS = PrivateClient.DEFAULT_POLL_SEC * 1000L

        /** 서비스가 막 뜬 직후에 망을 때리지 않는다. */
        private const val FIRST_DELAY_MS = 20_000L

        private const val SKEW_WARN_MS = 60_000L
    }
}
