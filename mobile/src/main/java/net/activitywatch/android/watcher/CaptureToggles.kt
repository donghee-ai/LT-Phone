/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.watcher

/**
 * 수집 스위치(웹·미디어)와 접근성 이벤트 분류의 **순수 규칙**.
 *
 * `Context`·`AccessibilityEvent` 를 받지 않는다 — 이 앱의 JVM 테스트에는 Robolectric 이
 * 없어서, 판단을 여기로 빼 둬야 단위 테스트가 된다 ([WatcherHealth] 와 같은 이유).
 */
object CaptureToggles {

    /**
     * 웹 주소·제목 기록(접근성) 스위치. **값을 저장한 적이 없으면 켜짐**이다.
     *
     * ★ 기존 설치는 이미 접근성을 허용해 두고 쓰고 있었다. 업데이트 한 번으로 조용히 꺼지면
     *   "왜 웹 기록이 없지" 가 된다. 권한이 없으면 서비스가 아예 안 뜨므로 켜짐 기본값이
     *   권한 없는 새 설치에서 무엇을 읽게 만들지는 않는다.
     */
    fun webEnabled(stored: Boolean?): Boolean = stored ?: true

    /**
     * 영상·음악 제목 기록(알림 접근) 스위치의 **처음 값**.
     *
     * 새 설치는 꺼짐이다. 알림 접근은 모든 알림을 읽을 수 있는 넓은 권한이라 사용자가 앱
     * 안에서 직접 켜야 한다. 다만 **이미 알림 접근을 허용해 쓰던 기존 설치**(업데이트로 들어온
     * 경우)는 켜진 채로 둔다 — 업데이트가 사용자의 선택을 몰래 뒤집으면 안 된다.
     *
     * ★ 이 값은 한 번 정해지면 저장한다 (`LTSettings.settleCaptureDefaults`). 저장하지 않으면
     *   새 설치가 다음 업데이트를 받는 순간 "업데이트 설치" 로 보여 저절로 켜진다.
     */
    fun defaultMediaEnabled(isUpdateInstall: Boolean, granted: Boolean): Boolean =
        isUpdateInstall && granted

    /** 설정 화면에 보일 스위치·권한 조합. */
    enum class GrantState {
        /** 켜짐 + 권한 있음 — 기록 중. */
        ACTIVE,
        /** 켜짐인데 권한이 없다 — 시스템 설정에서 허용해야 기록된다. */
        NEEDS_GRANT,
        /** 꺼짐 + 권한도 없음 — 완전히 내려놓은 상태. */
        OFF,
        /** 꺼짐인데 권한이 아직 목록에 남아 있다 — 기록은 안 하지만 시스템 설정에서 끄면 더 깔끔하다. */
        OFF_STILL_GRANTED
    }

    fun grantState(enabled: Boolean, granted: Boolean): GrantState = when {
        enabled && granted -> GrantState.ACTIVE
        enabled -> GrantState.NEEDS_GRANT
        granted -> GrantState.OFF_STILL_GRANTED
        else -> GrantState.OFF
    }

    /** 접근성 이벤트 한 건을 어떻게 다룰지. */
    enum class WebEvent {
        /** 추적하는 브라우저의 이벤트 — URL·제목을 읽는다. */
        BROWSER,
        /** 브라우저가 아닌 창으로 바뀌었다 — 열린 브라우저 세션을 닫고 쇼츠만 본다. */
        LEFT_BROWSER,
        /** 그 밖의 이벤트 — 쇼츠만 본다. */
        OTHER
    }

    /**
     * ★ 설정에 `packageNames` 를 걸었으므로 여기 오는 이벤트는 사실상 브라우저와 유튜브뿐이다.
     *   "런처·다른 앱으로 넘어갔다" 는 이벤트는 **오지 않는다** — 그래서 세션 끝내기는 화면
     *   꺼짐·언바인드·오래됨([BrowserSessionTracker.endIfStale])이 함께 맡는다.
     *
     * @param packageName 이벤트의 패키지. Firefox·유튜브에서 null 로 올 때가 있다
     * @param isWindowStateChanged `TYPE_WINDOW_STATE_CHANGED` 인가 (다른 화면이 앞에 떴다)
     * @param windowChanged 직전 이벤트와 창 id 가 다른가
     */
    fun classifyWebEvent(
        packageName: String?,
        isWindowStateChanged: Boolean,
        windowChanged: Boolean,
        browsers: Set<String>
    ): WebEvent {
        if (packageName != null && packageName in browsers) return WebEvent.BROWSER
        // null 패키지 + 같은 창은 브라우저 안일 수 있다 (Firefox). 예전 동작을 그대로 둔다.
        if (windowChanged) return WebEvent.LEFT_BROWSER
        // 유튜브 같은 브라우저 아닌 앱의 화면 전환. 창 id 가 우연히 같아도 닫는다.
        if (packageName != null && isWindowStateChanged) return WebEvent.LEFT_BROWSER
        return WebEvent.OTHER
    }
}
