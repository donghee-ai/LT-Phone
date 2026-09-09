/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.privacy

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import net.activitywatch.android.BackgroundService

/**
 * 워처들이 "지금 기록해도 되나"를 묻는 **한 곳**.
 *
 * ## 왜 서비스를 언바인드하지 않고 여기서 막나
 *
 * 명세는 실시간 워처(웹·미디어)를 *"진짜로 정지"* 하라고 적었다. 가장 강한 해석은
 * 접근성·알림 서비스를 **언바인드**하는 것인데, 그러면 안 된다 —
 * [`issues/0030`] 에서 확인했듯 이 기기는 **한번 풀린 바인딩을 스스로 안 되돌린다.**
 * 프라이빗 한 번에 워처가 며칠 죽는 것과 맞바꿀 수는 없다.
 *
 * 대신 **이벤트 처리의 첫 줄에서 막는다.** 기록되는 것이 없다는 점에서 결과는 같고,
 * 프라이빗이 풀리면 바인딩이 살아 있으므로 그 자리에서 즉시 재개된다.
 *
 * ★ 정직하게: 그 사이 이벤트는 앱 프로세스 메모리를 **스친다.** 디스크에 쓰지 않고
 *   네트워크로 내보내지 않을 뿐이다. 언바인드가 그 점에서는 더 강하지만, 위 대가를
 *   생각하면 지금은 이쪽이 맞다.
 *
 * ## 캐시를 두는 이유
 *
 * [isActive] 는 접근성 이벤트마다 불린다(초당 수십 번). 매번 SharedPreferences 를
 * 읽으면 그 자체가 부담이라 짧게 캐시한다. 프라이빗을 켠 뒤 최대 [CACHE_MS] 만큼
 * 늦게 막힐 수 있는데, 그 구간은 어차피 지연분 정리가 지운다.
 */
object PrivateGate {

    private const val TAG = "PrivateGate"
    private const val CACHE_MS = 1_000L

    @Volatile private var cachedActive = false
    @Volatile private var cachedAtUptime = 0L

    /** 지금 기록하면 안 되면 true. */
    fun isActive(context: Context): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedAtUptime < CACHE_MS) return cachedActive
        val mode = PrivateMode(context)
        mode.settleIfExpired()
        cachedActive = mode.isActive()
        cachedAtUptime = now
        return cachedActive
    }

    /** 타일·알람이 상태를 바꿨다. 캐시를 버리고 서비스에 알린다. */
    fun onLocalChange(context: Context) {
        cachedAtUptime = 0L
        try {
            context.startService(
                Intent(context, BackgroundService::class.java)
                    .setAction(BackgroundService.ACTION_PRIVATE_CHANGED)
            )
        } catch (e: Exception) {
            // 백그라운드 시작 제한에 걸릴 수 있다. 서비스는 다음 주기에 스스로 확인하므로
            // 치명적이지 않다 — 상태는 이미 SharedPreferences 에 확정돼 있다.
            Log.w(TAG, "서비스 통지 실패(무시)")
        }
    }
}
