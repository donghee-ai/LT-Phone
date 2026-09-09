/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.privacy

import android.content.Context
import android.util.Log
import net.activitywatch.android.push.LTAuth
import net.activitywatch.android.push.LTSettings
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 자가 호스팅 서버의 `/ingest/private` 와 맞춘다.
 *
 * | | |
 * |---|---|
 * | 조회 | `GET /ingest/private` |
 * | 토글 | `POST /ingest/private` — `{"minutes": 60}` 또는 `{"end": true}` |
 *
 * 인증은 `/ingest/aw` 와 **같은 `LT1` HMAC** 이다 ([LTAuth]).
 * ★ GET 은 본문이 없으므로 **`sha256("")` 을 서명한다** — 빈 바이트 배열을 넘기면 된다.
 *
 * 터널 인그레스가 `/ingest/` 접두사만 통과시키는데 이 경로도 거기 걸리므로
 * **셀룰러에서도 된다.**
 */
class PrivateClient(context: Context) {

    private val settings = LTSettings(context)

    /** 서버가 말하는 상태. 못 물어봤으면 null (로컬을 건드리지 않는다). */
    data class ServerState(
        val active: Boolean,
        val untilMs: Long,
        /** `server_ts − now`. 폰 시계가 틀어져 있으면 이만큼 보정한다. */
        val skewMs: Long,
        val pollSec: Int
    )

    fun isConfigured(): Boolean = settings.isConfigured()

    /**
     * 서버 상태를 읽는다.
     *
     * ★ `until_ts` 는 **절대 시각**이지 "남은 초"가 아니다. 폰이 서버를 못 만나도
     *   자기 시계로 언제 워처를 되살릴지 알아야 하기 때문이다. 다만 폰 시계가 틀어져
     *   있을 수 있으므로 `server_ts` 로 차이를 재서 [ServerState.skewMs] 로 넘긴다.
     */
    fun fetch(): ServerState? {
        val body = ByteArray(0) // ★ GET — sha256("") 을 서명한다
        val json = request("GET", body) ?: return null
        val nowMs = System.currentTimeMillis()
        val serverTsMs = (json.optDouble("server_ts", 0.0) * 1000.0).toLong()
        val untilMs = (json.optDouble("until_ts", 0.0) * 1000.0).toLong()
        return ServerState(
            active = json.optBoolean("active", false),
            untilMs = untilMs,
            skewMs = if (serverTsMs > 0L) serverTsMs - nowMs else 0L,
            pollSec = json.optInt("poll_sec", DEFAULT_POLL_SEC)
        )
    }

    /** 폰에서 켠 것을 서버에 알린다. 실패해도 로컬 상태는 이미 확정돼 있다. */
    fun notifyBegin(minutes: Int): Boolean =
        request("POST", JSONObject().put("minutes", minutes).toString().toByteArray()) != null

    /**
     * 폰에서 끈 것을 서버에 알린다.
     *
     * ★ 키는 **`off`** 다. `private-mode.md` 는 `{"end": true}` 라고 적어 뒀지만 서버
     *   코드는 `body.get("off")` 만 본다 — 그리고 **`off` 가 없으면 전부 "켜기"로 떨어진다**:
     *
     *       if body.get("off"): privacy.end_now(conn)
     *       else:               privacy.begin(conn, _parse_minutes(body, ...))
     *
     *   그래서 `{"end": true}` 를 보내면 **끄려던 것이 새 60분 켜기가 된다.**
     */
    fun notifyEnd(): Boolean =
        request("POST", JSONObject().put("off", true).toString().toByteArray()) != null

    private fun request(method: String, body: ByteArray): JSONObject? {
        if (!settings.isConfigured()) return null
        val url = settings.serverUrl.trimEnd('/') + PATH
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            Log.w(TAG, "프라이버시 서버 URL이 올바르지 않다")
            return null
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            // Cloudflare 의 Browser Integrity Check 가 UA 없는 요청을 오리진에 닿기도 전에
            // 403(error code 1010)으로 끊는다. PushSender 와 같은 이유로 명시한다.
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty(
                "Authorization",
                LTAuth.header(settings.deviceName, settings.getSecret(), body)
            )
            if (method == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                // 401 은 추측으로 고치지 않는다 — 서버가 사유를 본문에 그대로 돌려준다.
                Log.w(TAG, "프라이버시 서버 요청 실패: HTTP $code")
                return null
            }
            val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            return JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "프라이버시 서버 요청 실패")
            return null
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "PrivateClient"
        private const val PATH = "/ingest/private"
        private const val USER_AGENT = "LTPhone/1.0"
        const val DEFAULT_POLL_SEC = 15
    }
}
