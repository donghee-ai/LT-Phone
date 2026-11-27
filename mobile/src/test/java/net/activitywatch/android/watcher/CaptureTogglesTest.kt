/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.watcher

import net.activitywatch.android.watcher.CaptureToggles.GrantState
import net.activitywatch.android.watcher.CaptureToggles.WebEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureTogglesTest {

    private val browsers = setOf("com.android.chrome", "org.mozilla.firefox")

    // ── 스위치 기본값 ──────────────────────────────────────────────

    @Test
    fun webDefaultsOnWhenNeverStored() {
        // 기존 설치는 이미 접근성을 허용해 쓰고 있었다 — 업데이트로 조용히 꺼지면 안 된다.
        assertTrue(CaptureToggles.webEnabled(null))
        assertTrue(CaptureToggles.webEnabled(true))
        assertFalse(CaptureToggles.webEnabled(false))
    }

    @Test
    fun mediaDefaultsOffForNewInstalls() {
        assertFalse(CaptureToggles.defaultMediaEnabled(isUpdateInstall = false, granted = false))
        // 새 설치가 시스템 설정에서 먼저 허용했어도 앱 안에서 켜기 전에는 꺼짐.
        assertFalse(CaptureToggles.defaultMediaEnabled(isUpdateInstall = false, granted = true))
        assertFalse(CaptureToggles.defaultMediaEnabled(isUpdateInstall = true, granted = false))
        // 알림 접근을 허용해 쓰던 기존 설치만 켜진 채로 남는다.
        assertTrue(CaptureToggles.defaultMediaEnabled(isUpdateInstall = true, granted = true))
    }

    @Test
    fun grantStateCombinations() {
        assertEquals(GrantState.ACTIVE, CaptureToggles.grantState(enabled = true, granted = true))
        assertEquals(GrantState.NEEDS_GRANT, CaptureToggles.grantState(enabled = true, granted = false))
        assertEquals(GrantState.OFF_STILL_GRANTED, CaptureToggles.grantState(enabled = false, granted = true))
        assertEquals(GrantState.OFF, CaptureToggles.grantState(enabled = false, granted = false))
    }

    // ── 접근성 이벤트 분류 ─────────────────────────────────────────

    @Test
    fun browserEventsAreBrowser() {
        assertEquals(WebEvent.BROWSER,
            CaptureToggles.classifyWebEvent("com.android.chrome", isWindowStateChanged = true, windowChanged = true, browsers = browsers))
        assertEquals(WebEvent.BROWSER,
            CaptureToggles.classifyWebEvent("org.mozilla.firefox", isWindowStateChanged = false, windowChanged = false, browsers = browsers))
    }

    @Test
    fun youtubeWindowStateChangeEndsBrowserSession() {
        // 창 id 가 우연히 같아도 다른 앱 화면이 앞에 떴으면 닫는다.
        assertEquals(WebEvent.LEFT_BROWSER,
            CaptureToggles.classifyWebEvent("com.google.android.youtube", isWindowStateChanged = true, windowChanged = false, browsers = browsers))
        assertEquals(WebEvent.LEFT_BROWSER,
            CaptureToggles.classifyWebEvent("com.google.android.youtube", isWindowStateChanged = false, windowChanged = true, browsers = browsers))
    }

    @Test
    fun youtubeContentChangeInSameWindowIsOther() {
        assertEquals(WebEvent.OTHER,
            CaptureToggles.classifyWebEvent("com.google.android.youtube", isWindowStateChanged = false, windowChanged = false, browsers = browsers))
    }

    @Test
    fun nullPackageSameWindowKeepsSession() {
        // Firefox 는 같은 창 안에서도 packageName 이 null 로 올 때가 있다 — 닫지 않는다.
        assertEquals(WebEvent.OTHER,
            CaptureToggles.classifyWebEvent(null, isWindowStateChanged = true, windowChanged = false, browsers = browsers))
        assertEquals(WebEvent.LEFT_BROWSER,
            CaptureToggles.classifyWebEvent(null, isWindowStateChanged = false, windowChanged = true, browsers = browsers))
    }
}
