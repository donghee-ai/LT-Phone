/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.privacy

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 프라이빗이 끝나는 시각에 깨어나 워처를 되살린다.
 *
 * ★ **이중 방어의 한 쪽일 뿐이다.** 알람이 안 울려도(제조사 절전·Doze) 워처가 매 수집
 *   때 [PrivateMode.settleIfExpired] 를 스스로 부른다. 끝 시각이 절대 시각이라 둘 중
 *   무엇이 먼저 와도 같은 결과가 나온다.
 */
class PrivateExpiryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val mode = PrivateMode(context)
        mode.settleIfExpired()
        Log.i(TAG, "만료 알람 — active=${mode.isActive()}")
        PrivateGate.onLocalChange(context)
    }

    companion object {
        private const val TAG = "PrivateExpiry"
        const val ACTION = "net.activitywatch.android.privacy.PRIVATE_EXPIRED"
        private const val REQUEST_CODE = 0x4C54 // 'LT'

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(ACTION).setPackage(context.packageName)
            return PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * `untilMs` 에 깨운다.
         *
         * ★ 여기만은 **정확한 알람**을 쓴다. 수집 주기는 늦어도 되지만(늦는 것은 *도착*이지
         *   *기록*이 아니다) 프라이빗 해제가 늦으면 **안 재기로 한 시간이 아닌데 안 재는**
         *   것이라 성격이 다르다. 사용자가 명시적으로 정한 끝 시각이기도 하다.
         */
        fun scheduleAt(context: Context, untilMs: Long) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = pendingIntent(context)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                    // 권한이 없으면 조용히 부정확 알람으로 떨어진다. 워처 쪽 자체 확인이
                    // 있으므로 최악이라도 한 주기 늦게 풀린다.
                    am.set(AlarmManager.RTC_WAKEUP, untilMs, pi)
                    Log.w(TAG, "정확 알람 권한 없음 — 부정확 알람으로 예약")
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, untilMs, pi)
                }
            } catch (e: SecurityException) {
                am.set(AlarmManager.RTC_WAKEUP, untilMs, pi)
                Log.w(TAG, "정확 알람 거부됨 — 부정확 알람으로 예약")
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            am.cancel(pendingIntent(context))
        }
    }
}
