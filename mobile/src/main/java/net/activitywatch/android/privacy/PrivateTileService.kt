/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.privacy

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import net.activitywatch.android.R

/**
 * 빠른 설정 타일 — 누르면 그 시간대를 **아예 안 재기** 시작한다.
 *
 * ## 지켜야 타일이 안 죽는 규칙
 *
 * | | 무엇 | 왜 |
 * |---|---|---|
 * | [onStartListening] | **로컬 상태만** 읽어 칠한다. 네트워크 금지 | 패널을 내릴 때마다 불린다. 여기서 HTTP 를 하면 패널이 버벅이고 Doze 에서 실패한다 |
 * | [onClick] | **로컬을 먼저 뒤집고** 타일을 갱신한 뒤 서버에 통지 | 비행기 모드에서도 켜져야 한다. 서버 왕복을 기다리면 "눌렀는데 안 켜졌다"가 된다 |
 * | 라벨 | 켜짐이면 `프라이빗 12분` | 남은 시간을 안 보여주면 켠 것을 잊는다 — **조용한 손실이 이 기능의 유일한 실패 모드다** |
 */
class PrivateTileService : TileService() {

    private val mode by lazy { PrivateMode(applicationContext) }

    override fun onStartListening() {
        super.onStartListening()
        Log.i(TAG, "onStartListening (active=${mode.isActive()})")
        // ★ 여기서 네트워크를 부르지 않는다. 로컬만 본다.
        mode.settleIfExpired()
        render()
    }

    override fun onClick() {
        super.onClick()
        val nowMs = System.currentTimeMillis()
        Log.i(TAG, "onClick (active=${mode.isActive(nowMs)})")
        mode.settleIfExpired(nowMs)

        // ★ 상태 뒤집기를 **먼저, 단독으로** 한다. 알람 예약이나 타일 렌더가 실패해도
        //   사용자가 누른 뜻은 이미 확정돼 있어야 한다 — 프라이빗은 "눌렀는데 안 켜졌다"가
        //   가장 나쁜 실패다.
        val nowActive = if (mode.isActive(nowMs)) {
            mode.end(nowMs); false
        } else {
            mode.begin(PrivateMode.DEFAULT_MINUTES, nowMs); true
        }

        // 나머지는 전부 실패해도 되는 일이다.
        runCatching {
            if (nowActive) PrivateExpiryReceiver.scheduleAt(applicationContext, mode.untilMs)
            else PrivateExpiryReceiver.cancel(applicationContext)
        }.onFailure { Log.w(TAG, "만료 알람 처리 실패") }

        runCatching { render() }.onFailure { Log.w(TAG, "타일 렌더 실패") }
        runCatching { PrivateGate.onLocalChange(applicationContext) }
            .onFailure { Log.w(TAG, "상태 전파 실패") }
    }

    private fun render() {
        val tile = qsTile ?: return
        val active = mode.isActive()
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.private_tile_label)
        // 남은 시간은 부제로 — 라벨을 길게 만들면 잘린다.
        tile.contentDescription = tile.label
        val sub = if (active) {
            getString(R.string.private_tile_remaining, mode.remainingMinutes())
        } else {
            getString(R.string.private_tile_off)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = sub
        } else {
            tile.contentDescription = "${tile.label}: $sub"
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_private_tile)
        try {
            tile.updateTile()
        } catch (e: Exception) {
            // 패널이 이미 닫혔으면 던진다. 다음 onStartListening 에서 다시 칠해진다.
            Log.w(TAG, "타일 갱신 실패")
        }
    }

    companion object {
        private const val TAG = "PrivateTile"
    }
}
