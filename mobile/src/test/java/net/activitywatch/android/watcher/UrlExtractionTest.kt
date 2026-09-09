package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlExtractionTest {

    @Test
    fun `extracts url from typical content description`() {
        val cd = " example.com/page. Search or enter address"
        assertEquals("example.com/page", parseFirefoxAddressBarContentDescription(cd))
    }

    @Test
    fun `returns null for empty address bar placeholder`() {
        val cd = ". Search or enter address"
        assertNull(parseFirefoxAddressBarContentDescription(cd))
    }

    @Test
    fun `returns null for null content description`() {
        assertNull(parseFirefoxAddressBarContentDescription(null))
    }

    @Test
    fun `returns null when content description has no separator at all`() {
        assertNull(parseFirefoxAddressBarContentDescription("Search or enter address"))
    }

    @Test
    fun `does not truncate a url that itself contains a period-space sequence`() {
        // Regression test: a non-greedy regex would previously split at the FIRST ". "
        // it found, truncating urls whose own content contains ". ".
        val cd = " example.com/search?q=World. Cup. Search or enter address"
        assertEquals("example.com/search?q=World. Cup", parseFirefoxAddressBarContentDescription(cd))
    }

    @Test
    fun `does not require the hint text to start with an ascii capital letter`() {
        // Regression test: locales where the hint text isn't Latin-script (or doesn't
        // capitalize) must not disable Firefox tracking entirely.
        val cd = " example.com. 搜索或输入网址"
        assertEquals("example.com", parseFirefoxAddressBarContentDescription(cd))
    }

    @Test
    fun `rejects a bare placeholder that parses as the hint text itself`() {
        assertNull(parseFirefoxAddressBarContentDescription("Search or enter address. Search or enter address"))
    }

    @Test
    fun `processExtractedText filters blank text`() {
        assertNull(processExtractedText(""))
        assertNull(processExtractedText("   "))
        assertNull(processExtractedText(null))
        assertEquals("example.com", processExtractedText("example.com"))
    }

    @Test
    fun `stripProtocol removes http and https prefixes only`() {
        assertEquals("example.com", stripProtocol("http://example.com"))
        assertEquals("example.com", stripProtocol("https://example.com"))
        assertEquals("example.com", stripProtocol("example.com"))
    }

    // ── 주소창 안내 문구 ─────────────────────────────────────────────
    //
    // 주소창이 비면 접근성 노드에 안내 문구가 남는다. 그것이 url 자리에 128회 들어갔다.

    @Test
    fun `주소창 안내 문구는 url 이 아니다`() {
        assertNull(processExtractedText("Google 검색 또는 URL 입력"))
        assertNull(processExtractedText("Search or type URL"))
    }

    @Test
    fun `주소창에 친 검색어도 url 이 아니다`() {
        assertNull(processExtractedText("테스트 검색어"))
    }

    @Test
    fun `진짜 url 은 그대로 통과한다`() {
        assertEquals("m.example-forum.com/best", processExtractedText("m.example-forum.com/best"))
        assertEquals(
            "google.com/search?q=test+query",
            processExtractedText("google.com/search?q=test+query")
        )
        assertEquals(
            "example-blog.net/제품-후기-매장안내/",
            processExtractedText("example-blog.net/제품-후기-매장안내/")
        )
    }
}
