/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.privacy

import android.content.Context
import android.util.Log
import net.activitywatch.android.ConfigManager
import net.activitywatch.android.RustInterface
import net.activitywatch.android.baseURL
import org.threeten.bp.OffsetDateTime
import java.net.HttpURLConnection
import java.net.URL

/**
 * 프라이빗을 켠 시점과 워처가 실제로 멈춘 시점 사이에 들어간 이벤트를 **앱이 자기
 * DB 에서** 지운다.
 *
 * ## 왜 앱이 하나
 *
 * PC 는 서버가 `DELETE /api/0/buckets/{b}/events/{id}` 로 대신 정리한다. 폰은 못 한다 —
 * 폰의 aw-server 가 **`127.0.0.1` 바인딩**이라 외부 서버가 닿지 못한다.
 * 그래서 앱이 자기 자신에게 같은 요청을 보낸다.
 *
 * ★ **JNI 가 아니라 로컬 HTTP 를 쓴다.** `RustInterface` 에는 삭제 바인딩이 없고, 늘리려면
 *   Rust 서브모듈과 `.so` 를 다시 빌드해야 한다. 서버는 어차피 같은 프로세스 안에서
 *   `127.0.0.1:5600` 으로 떠 있으므로 망을 타지 않는다.
 *
 * ## 구간에 **완전히 들어간 것만** 지운다
 *
 * 걸친 이벤트를 통째로 지우면 **프라이빗 밖의 정상 시간까지 날아간다.** 그리고 걸친
 * 이벤트의 제목은 프라이빗을 켜기 전에 이미 보이던 것이라 새로 새는 정보가 없다.
 *
 * ## afk 는 건드리지 않는다
 *
 * 가리는 것은 *무엇을* 했는지지 *폰을 썼는지*가 아니다.
 * (unlock 은 애초에 [PrivateMode.skipCursor] 단계에서 안 만들어진다.)
 */
class PrivateSweeper(private val context: Context) {

    private val ri by lazy { RustInterface(context) }
    private val configManager by lazy { ConfigManager(context) }

    /** 지나간 프라이빗 구간을 훑어 지운다. 지운 이벤트 수. */
    fun sweep(mode: PrivateMode = PrivateMode(context)): Int {
        val spans = mode.spans()
        if (spans.isEmpty()) return 0

        val apiKey = configManager.readAuthConfig().apiKey
        var removed = 0
        for (span in spans) {
            val (fromMs, toMs) = span
            var spanRemoved = 0
            var failed = false
            for (bucketId in SWEPT_BUCKETS) {
                val r = sweepBucket(bucketId, fromMs, toMs, apiKey)
                if (r < 0) failed = true else spanRemoved += r
            }
            // ★ 실패한 구간은 **잊지 않는다.** 다음 회차에 다시 시도한다 — 한 번 놓치면
            //   그 이벤트는 영영 남고, 그게 이 기능이 약속한 것과 정면으로 어긋난다.
            if (!failed) mode.forgetSpan(span)
            removed += spanRemoved
            if (spanRemoved > 0 || failed) {
                Log.i(TAG, "프라이빗 지연분 정리 완료")
            }
        }
        return removed
    }

    /** 지운 개수. 조회 자체가 실패하면 -1 (구간을 잊지 않기 위해). */
    private fun sweepBucket(bucketId: String, fromMs: Long, toMs: Long, apiKey: String?): Int {
        val events = try {
            ri.getEventsJSON(bucketId, limit = FETCH_LIMIT)
        } catch (e: Exception) {
            Log.w(TAG, "버킷 조회 실패")
            return -1
        }
        var n = 0
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val id = ev.optInt("id", -1)
            if (id < 0) continue
            val startMs = parseTs(ev.optString("timestamp")) ?: continue
            val endMs = startMs + (ev.optDouble("duration", 0.0) * 1000.0).toLong()
            // 완전히 안에 들어간 것만.
            if (startMs < fromMs || endMs > toMs) continue
            if (deleteEvent(bucketId, id, apiKey)) n++ else return -1
        }
        return n
    }

    private fun deleteEvent(bucketId: String, eventId: Int, apiKey: String?): Boolean {
        val conn = try {
            URL("$baseURL/api/0/buckets/$bucketId/events/$eventId")
                .openConnection() as HttpURLConnection
        } catch (e: Exception) {
            Log.w(TAG, "삭제 URL 오류")
            return false
        }
        return try {
            conn.requestMethod = "DELETE"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            if (!apiKey.isNullOrEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            val code = conn.responseCode
            // 404 는 이미 없다는 뜻이라 성공으로 친다.
            val ok = code in 200..299 || code == 404
            if (!ok) Log.w(TAG, "이벤트 삭제 실패: HTTP $code")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "이벤트 삭제 실패")
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun parseTs(s: String?): Long? {
        if (s.isNullOrEmpty()) return null
        return try {
            OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "PrivateSweeper"
        private const val FETCH_LIMIT = 2_000

        /** **무엇을** 했는지가 담긴 버킷만. afk 는 뺀다 — 위 주석 참조. */
        private val SWEPT_BUCKETS = listOf(
            "aw-watcher-android",
            "aw-watcher-android-web",
            "aw-watcher-android-media"
        )
    }
}
