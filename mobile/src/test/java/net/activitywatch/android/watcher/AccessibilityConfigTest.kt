/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * ★ 접근성 설정의 `packageNames` 와 [WebWatcher.ACCESSIBILITY_PACKAGES] 가 어긋나면 그 브라우저의
 *   이벤트는 **조용히 안 온다.** xml 은 상수를 참조할 수 없어서 이 테스트가 둘을 맞춰 본다.
 */
class AccessibilityConfigTest {

    private fun configText(): String {
        // Gradle 단위 테스트의 작업 디렉터리는 모듈(mobile/)이다. 저장소 루트에서 돌려도 찾게 한다.
        val rel = "src/main/res/xml/accessibility_service_config.xml"
        val f = listOf(File(rel), File("mobile/$rel")).firstOrNull { it.isFile }
        assertNotNull("accessibility_service_config.xml 을 못 찾았다", f)
        // xml 주석 안의 설명 글은 빼고 속성만 본다.
        return f!!.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }

    private fun attr(text: String, name: String): String {
        val m = Regex("android:$name=\"([^\"]*)\"").find(text)
        assertNotNull("android:$name 이 없다", m)
        return m!!.groupValues[1]
    }

    @Test
    fun packageNamesMatchWebWatcher() {
        val inXml = attr(configText(), "packageNames").split(',').map { it.trim() }.toSet()
        assertEquals(WebWatcher.ACCESSIBILITY_PACKAGES, inXml)
    }

    @Test
    fun youtubeIsListedButIsNotABrowser() {
        assertFalse(WebWatcher.YOUTUBE_PACKAGE in WebWatcher.KNOWN_BROWSER_PACKAGES)
        assert(WebWatcher.YOUTUBE_PACKAGE in WebWatcher.ACCESSIBILITY_PACKAGES)
    }

    @Test
    fun onlyNeededFlagsAndRelaxedTimeout() {
        val text = configText()
        assertEquals(
            setOf("flagReportViewIds", "flagRetrieveInteractiveWindows"),
            attr(text, "accessibilityFlags").split('|').map { it.trim() }.toSet()
        )
        assertEquals("500", attr(text, "notificationTimeout"))
        assertEquals(
            setOf("typeWindowContentChanged", "typeWindowsChanged", "typeWindowStateChanged"),
            attr(text, "accessibilityEventTypes").split('|').map { it.trim() }.toSet()
        )
    }
}
