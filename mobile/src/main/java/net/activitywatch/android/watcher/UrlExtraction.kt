package net.activitywatch.android.watcher

// Firefox (Compose toolbar): URL is in content-desc of ADDRESSBAR_URL_BOX as
// " {url}. Search or enter address". Greedy so we split at the LAST ". " (URLs can
// themselves contain ". "), and we don't assume the hint text starts with an ASCII
// capital letter since it's localized and may start with a non-Latin character.
private val FIREFOX_SUFFIX_PATTERN = Regex("""^\s*(.+)\.\s+\S""")

// Pure parsing logic, unit-testable (mobile/src/test) without any Android/Accessibility
// framework dependency.
internal fun parseFirefoxAddressBarContentDescription(contentDescription: String?): String? =
    contentDescription
        ?.let { FIREFOX_SUFFIX_PATTERN.find(it)?.groupValues?.get(1) }
        ?.takeIf { it.isNotBlank() && !it.equals("Search or enter address", ignoreCase = true) }

// Strips the URI scheme so the same page is represented identically regardless of which
// browser/UI-variant's view happened to include it (e.g. Samsung Internet's regular vs.
// custom-tab toolbar previously disagreed on this, splitting one continuous visit in two).
internal val stripProtocol: (String) -> String = { url ->
    url.removePrefix("http://").removePrefix("https://")
}

// Pure post-processing of text read off an accessibility node: blank text (e.g. a
// momentarily-cleared address bar) is treated as "no url", not as a real value.
//
// ★ 빈 문자열만으로는 부족하다. 주소창이 비어 있을 때 접근성 노드에 남는 것은
//   **안내 문구**다 — 한국어 크롬에서는 `"Google 검색 또는 URL 입력"` 이고,
//   그것이 url 자리에 그대로 들어갈 수 있다.
//
//   **공백이 있으면 url 이 아니다.** URL 에는 문자 그대로의 공백이 올 수 없다
//   (공백은 `%20`·`+` 로 인코딩된다). 이 규칙은 **언어에 안 묶인다** — 안내 문구는
//   지역마다 다르지만 어느 언어에서도 띄어쓰기가 있다. 문구 목록을 박아 두면
//   기기 언어를 바꾸는 순간 조용히 다시 새기 시작한다.
//
//   주소창에 친 **검색어**도 같이 걸러진다. 그것도 url 이 아니므로 맞다.
internal fun processExtractedText(rawText: String?): String? =
    rawText?.takeIf { it.isNotBlank() && !it.any(Char::isWhitespace) }
