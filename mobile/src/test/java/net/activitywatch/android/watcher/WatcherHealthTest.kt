/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 워처 하나가 혼자 조용한 것을 앱이 스스로 알아채는 판정.
 *
 * ★ **안 울려야 하는 경우가 어려운 쪽이다.** 밤에는 모든 워처가 같이 조용하므로
 *   절대 시간으로 재면 매일 아침 운다. 아래 테스트 절반이 그 경우들이다.
 */
class WatcherHealthTest {

    private val hour = 60L * 60 * 1000
    private val now = 1_788_000_000_000L

    private fun quiet(sessionAgoH: Long, targetAgoH: Long?) = WatcherHealth.isQuiet(
        sessionEndMs = now - sessionAgoH * hour,
        targetEndMs = targetAgoH?.let { now - it * hour },
        nowMs = now,
        lagWarnMs = WatcherHealth.MEDIA_LAG_WARN_MS
    )

    @Test
    fun `폰은 쓰는데 그 워처만 뒤처지면 조용하다고 본다`() {
        // 앱 세션은 계속 오지만 미디어 watcher만 장시간 멈춘 상태를 재현한다.
        assertTrue(quiet(sessionAgoH = 0, targetAgoH = 15))
    }

    @Test
    fun `임계값을 안 넘으면 조용하다고 하지 않는다`() {
        assertFalse(quiet(sessionAgoH = 0, targetAgoH = 11))
    }

    @Test
    fun `폰을 안 쓰면 아무 말도 안 한다`() {
        // 밤새 안 쓰면 세션도 같이 끊긴다. 여기서 울면 매일 아침 운다.
        assertFalse(quiet(sessionAgoH = 9, targetAgoH = 20))
    }

    @Test
    fun `한 번도 안 튼 워처는 고장이 아니다`() {
        // 옳지만 아직 증명 못 한 상태를 실패로 세지 않는다.
        assertFalse(quiet(sessionAgoH = 0, targetAgoH = null))
    }

    @Test
    fun `세션 기록이 없으면 판정하지 않는다`() {
        assertFalse(
            WatcherHealth.isQuiet(
                sessionEndMs = 0L,
                targetEndMs = now - 99 * hour,
                nowMs = now,
                lagWarnMs = WatcherHealth.MEDIA_LAG_WARN_MS
            )
        )
    }

    @Test
    fun `임계값이 없으면 판정하지 않는다`() {
        // 웹이 이 상태다 — 깨끗한 관측 구간이 없어 임계값을 정할 근거가 없다.
        assertFalse(
            WatcherHealth.isQuiet(
                sessionEndMs = now,
                targetEndMs = now - 99 * hour,
                nowMs = now,
                lagWarnMs = null
            )
        )
    }

    @Test
    fun `웹은 아직 임계값이 없어 목록에 안 오른다`() {
        val out = WatcherHealth.quietWatchers(
            sessionEndMs = now,
            mediaEndMs = now - 20 * hour,
            webEndMs = now - 200 * hour,
            nowMs = now
        )
        assertEquals(listOf("미디어"), out)
    }

    @Test
    fun `전부 살아 있으면 목록이 비어 있다`() {
        val out = WatcherHealth.quietWatchers(
            sessionEndMs = now,
            mediaEndMs = now - 1 * hour,
            webEndMs = now - 1 * hour,
            nowMs = now
        )
        assertTrue(out.isEmpty())
    }
}
