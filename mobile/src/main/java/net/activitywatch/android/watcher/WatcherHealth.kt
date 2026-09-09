/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.watcher

import android.content.Context
import android.util.Log
import net.activitywatch.android.RustInterface
import org.json.JSONObject
import org.threeten.bp.OffsetDateTime

/**
 * **워처 하나가 혼자 조용한가**를 앱이 스스로 본다.
 *
 * ## 왜 권한이 아니라 결과를 보나
 *
 * 앱을 업데이트하면 접근성·알림 접근이 **목록에는 남은 채 다시 바인딩되지 않는** 경우가 있다.
 * 이때 설정 화면에는 "켜짐"으로 보인다.
 *
 * 그래서 권한 API 로는 못 잡는다 — [MediaWatcher.isNotificationAccessGranted] 는
 * `Settings.Secure` 목록의 문자열 일치를, [UsageStatsWatcher.isAccessibilityAllowed] 는
 * `getEnabledAccessibilityServiceList` 를 볼 뿐이고 **둘 다 "목록에 있나"** 다.
 * 이 사고는 *목록엔 있는데 안 붙은* 상태다.
 *
 * **결과를 본다.** 그 워처의 버킷에 마지막 이벤트가 언제 들어왔나.
 *
 * ## 왜 절대 시간으로 안 재나
 *
 * 밤에는 모든 워처가 같이 조용하다. "N시간째 조용" 으로 걸면 매일 아침 운다.
 * 그래서 **앱 세션 버킷과 비교**한다 — 폰이 쓰이고 있는데 이 워처만 뒤처지면 고장이고,
 * 폰을 안 쓰면(밤·꺼짐·프라이빗) 세션도 같이 끊겨 조건이 성립하지 않는다.
 *
 * ★ 호환 서버의 "워처 침묵" 검사와 **같은 규칙·같은 숫자**다. 두 곳이 다른
 *   답을 내면 어느 쪽을 믿어야 하는지가 새 문제가 된다.
 */
object WatcherHealth {

    private const val TAG = "WatcherHealth"

    /** 폰이 "쓰이는 중"이라고 볼 기준. 이보다 오래 조용하면 판정 자체를 안 한다. */
    const val SESSION_FRESH_MS = 2L * 60 * 60 * 1000

    /**
     * 미디어 워처가 이만큼 뒤처지면 고장으로 본다.
     *
     * 짧은 일시 중단을 오탐하지 않으면서 장시간 침묵을 알리도록 12시간으로 둔다.
     */
    const val MEDIA_LAG_WARN_MS = 12L * 60 * 60 * 1000

    /** 웹 watcher는 브라우저 사용 빈도 차이가 커서 신뢰할 수 있는 공통 임계값을 아직 두지 않는다. */
    val WEB_LAG_WARN_MS: Long? = null

    /**
     * 판정. **순수 함수라 단위 테스트가 된다** — 이 앱의 JVM 테스트에는 Robolectric 이
     * 없어 `Context` 를 받는 것은 테스트할 수 없다.
     *
     * @param sessionEndMs 앱 세션 버킷의 마지막 이벤트 끝. 없으면 0
     * @param targetEndMs  볼 워처의 마지막 이벤트 끝. **없으면 null** (0 과 다르다)
     * @param lagWarnMs    임계값. null 이면 판정하지 않는다
     * @return 조용하면 true
     */
    fun isQuiet(
        sessionEndMs: Long,
        targetEndMs: Long?,
        nowMs: Long,
        lagWarnMs: Long?
    ): Boolean {
        if (lagWarnMs == null) return false
        // 폰이 조용하다 — 워처 탓이 아니다.
        if (sessionEndMs <= 0L || nowMs - sessionEndMs > SESSION_FRESH_MS) return false
        // ★ 한 번도 안 튼 워처를 고장으로 세지 않는다. **옳지만 아직 증명 못 한 상태**다.
        if (targetEndMs == null) return false
        return sessionEndMs - targetEndMs > lagWarnMs
    }

    /** 조용한 워처들의 사람이 읽을 이름. 비어 있으면 전부 정상이다. */
    fun quietWatchers(
        sessionEndMs: Long,
        mediaEndMs: Long?,
        webEndMs: Long?,
        nowMs: Long = System.currentTimeMillis()
    ): List<String> {
        val out = ArrayList<String>(2)
        if (isQuiet(sessionEndMs, mediaEndMs, nowMs, MEDIA_LAG_WARN_MS)) out.add("미디어")
        if (isQuiet(sessionEndMs, webEndMs, nowMs, WEB_LAG_WARN_MS)) out.add("웹")
        return out
    }

    // ── 버킷에서 읽기 ────────────────────────────────────────────────

    /**
     * 버킷의 마지막 이벤트 끝 시각. 이벤트가 없으면 null, 못 읽으면 null.
     *
     * ★ **버킷 메타의 `last_updated` 는 쓸 수 없다** — 서버가 항상 `null` 로 준다
     *   (`aw-datastore` 가 하드코딩). 마지막 이벤트를 직접 읽는 것이 저장소의 관행이고,
     *   [InteractionWatcher] 의 `lastEventEnd()` 가 같은 모양이다.
     */
    fun lastEventEnd(ri: RustInterface, bucketId: String): Long? {
        // ★ limit 를 반드시 명시한다. 기본값 0 은 SQL `LIMIT 0` 이라 0건이 온다.
        val events = try {
            ri.getEventsJSON(bucketId, limit = 1)
        } catch (e: Exception) {
            Log.w(TAG, "버킷 조회 실패")
            return null
        }
        if (events.length() == 0) return null
        return try {
            val e = events[0] as JSONObject
            val start = OffsetDateTime.parse(e.getString("timestamp")).toInstant().toEpochMilli()
            start + (e.optDouble("duration", 0.0) * 1000.0).toLong()
        } catch (e: Exception) {
            Log.w(TAG, "버킷의 마지막 이벤트 시각을 읽지 못했다")
            null
        }
    }

    /** 지금 조용한 워처들. 서버가 아직 안 떴으면 빈 목록(모른다 ≠ 고장). */
    fun check(context: Context, nowMs: Long = System.currentTimeMillis()): List<String> {
        val ri = try {
            RustInterface(context)
        } catch (e: Throwable) {
            Log.w(TAG, "RustInterface 를 못 만들었다")
            return emptyList()
        }
        val session = lastEventEnd(ri, SESSION_BUCKET_ID) ?: return emptyList()
        return quietWatchers(
            sessionEndMs = session,
            mediaEndMs = lastEventEnd(ri, MEDIA_BUCKET_ID),
            webEndMs = lastEventEnd(ri, WEB_BUCKET_ID),
            nowMs = nowMs
        )
    }

    // 웹·미디어 버킷 id 는 각 워처 안에 private 으로 숨어 있고 미디어는 두 파일에
    // 중복돼 있다. 여기서 한 벌로 모아 둔다 — 늘어나면 한 곳만 고치면 된다.
    const val MEDIA_BUCKET_ID = "aw-watcher-android-media"
    const val WEB_BUCKET_ID = "aw-watcher-android-web"
}
