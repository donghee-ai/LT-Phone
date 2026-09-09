/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "PushAlarmReceiver"

/**
 * `PushScheduler` 의 AlarmManager 폴백이 깨우는 지점.
 *
 * 서비스가 OS 에 죽어 Handler 체인이 끊겨도 이 알람은 남아 전송을 이어간다.
 * `SyncAlarmReceiver` 와 같은 역할이다.
 */
class PushAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PUSH_ALARM) {
            Log.w(TAG, "모르는 intent: ${intent.action}")
            return
        }
        // 브로드캐스트는 메인 스레드에서 오고 10초 안에 끝나야 한다.
        // 네트워크는 IO 로 넘기고 goAsync 로 수명을 연장한다.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                PushScheduler.pushOnceIn(context)
            } catch (e: Exception) {
                Log.e(TAG, "알람 경로 push 실패")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
