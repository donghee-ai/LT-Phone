/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.push

import android.content.Context
import android.util.Log
import net.activitywatch.android.RustInterface
import org.json.JSONArray
import org.json.JSONObject
import org.threeten.bp.OffsetDateTime
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 폰의 ActivityWatch 데이터를 서버(`POST /ingest/aw`)으로 밀어 넣는다.
 *
 * 본문은 **ActivityWatch export 형식 그대로**다. 우리 봉투를 새로 만들지 않는 이유는
 * adb 로 뽑은 파일(`lt import`)과 이 경로가 서버에서 같은 코드로 처리되기 때문이다.
 */
class PushSender(private val context: Context) {

    private val ri = RustInterface(context)
    private val settings = LTSettings(context)

    companion object {
        const val TAG = "PushSender"

        /**
         * 한 버킷에서 한 번에 조회할 이벤트 수. 부족하면 늘려가며 다시 조회한다.
         *
         * ★ getEventsJSON 은 **시간 범위를 못 받는다** (JNI 가 start/end 를 None 으로 고정).
         *   대신 `ORDER BY starttime DESC` 라 최신 N 건을 받아 커서로 잘라낸다.
         */
        private const val FETCH_CHUNK = 500
        private const val FETCH_MAX = 20_000

        /**
         * ★ User-Agent 를 반드시 명시한다.
         *
         * 서버가 Cloudflare Tunnel 뒤에 있는데, Cloudflare 의 Browser Integrity Check 가
         * **UA 가 없거나 수상한 요청을 오리진에 닿기도 전에 차단한다** (`error code: 1010`).
         * 실측: UA 없음 → 403/1010, 안드로이드 기본 Dalvik UA → 통과.
         *
         * HttpURLConnection 의 기본 UA 로도 지금은 통과하지만, 그건 Cloudflare 의
         * 판단에 기대는 것이라 언제든 바뀔 수 있다. 우리 이름을 박아 두면 그 의존이
         * 사라지고, 차단 로그에서도 우리 트래픽을 식별할 수 있다.
         *
         * 기기 모델 등은 넣지 않는다 — Cloudflare 로그에 남길 이유가 없다.
         */
        private const val USER_AGENT = "LTPhone/1.0"
    }

    sealed class Result {
        object Skipped : Result()
        object NothingToSend : Result()
        data class Ok(val buckets: Int, val events: Int, val skipped: List<String>) : Result()

        /** 재시도해도 똑같다 — 설정·시계 문제. */
        data class Fatal(val code: Int, val reason: String) : Result()

        /** 일시적 — 백오프 후 재시도. */
        data class Retry(val reason: String) : Result()
    }

    fun pushOnce(): Result {
        // 조용히 리턴하지 않는다 — 아무 로그도 없으면 "왜 안 보내는지" 를 알 방법이 없다.
        if (!settings.enabled) {
            Log.i(TAG, "전송이 꺼져 있다 — 건너뜀 (설정 화면의 스위치)")
            return Result.Skipped
        }
        if (!settings.isConfigured()) {
            Log.i(TAG, "전송 설정이 비어 있다")
            return Result.Skipped
        }
        Log.i(TAG, "전송 시도")
        // 401 을 한 번 받았으면 설정이 고쳐지기 전까지 재시도하지 않는다.
        val prevError = settings.lastAuthError
        if (prevError != null) {
            Log.w(TAG, "이전 인증 실패로 전송이 멈춰 있다")
            return Result.Skipped
        }

        val pending = collectPending() ?: return Result.NothingToSend
        if (pending.eventCount == 0) return Result.NothingToSend

        // ★ 보낼 바이트를 여기서 **한 번만** 만든다. 서명은 이 바이트에 대해 계산하고
        //   그대로 전송한다. 다시 직렬화하거나 압축을 나중에 걸면 해시가 어긋난다.
        val body = pending.payload.toString().toByteArray(Charsets.UTF_8)

        val result = post(body)
        if (result is Result.Ok) {
            // 200 을 받은 뒤에만 커서를 전진시킨다.
            for (entry in pending.cursors) {
                settings.setCursor(entry.key, entry.value.ts, entry.value.durationMs)
            }
            if (result.skipped.isNotEmpty()) {
                // 서버은 모르는 버킷을 거부하지 않고 건너뛴 뒤 알려준다.
                Log.w(TAG, "서버가 일부 버킷을 건너뛰었다")
            }
            Log.i(TAG, "전송 성공")
        }
        return result
    }

    // ── 본문 조립 ────────────────────────────────────────────────────

    /** 마지막으로 보낸 이벤트의 시각과 **그때의 길이**. 길이까지 들고 있어야 자란 것을 안다. */
    private data class Cursor(val ts: Long, val durationMs: Long)

    /** 조회 결과 한 건. `durationMs` 는 커서 이벤트가 자랐는지 판단하는 데만 쓴다. */
    private data class Fetched(val ts: Long, val durationMs: Long, val json: JSONObject)

    private data class Pending(
        val payload: JSONObject,
        val cursors: Map<String, Cursor>,
        val eventCount: Int
    )

    private fun collectPending(): Pending? {
        val buckets = try {
            ri.getBucketsJSON()
        } catch (e: Exception) {
            Log.w(TAG, "버킷 목록을 못 읽었다 (서버가 아직 안 떴을 수 있다)")
            return null
        }

        val out = JSONObject()
        val cursors = HashMap<String, Cursor>()
        var total = 0

        for (bucketId in buckets.keys()) {
            if (total >= LTSettings.MAX_EVENTS_PER_PUSH) break
            val meta = buckets.optJSONObject(bucketId) ?: continue
            val cursor = settings.getCursor(bucketId)
            val sentDuration = settings.getCursorDuration(bucketId)

            // 커서 **이상**을 가져온 뒤, 커서 이벤트는 **자랐을 때만** 남긴다.
            // (자라지 않았으면 같은 것을 매번 다시 보내는 셈이라 서버 롤업만 깨운다)
            val fresh = fetchSince(bucketId, cursor, LTSettings.MAX_EVENTS_PER_PUSH - total)
                .filterNot { it.ts == cursor && it.durationMs <= sentDuration }
            if (fresh.isEmpty()) continue

            val arr = JSONArray()
            var newest = fresh[0]
            for (f in fresh) {
                arr.put(f.json)
                if (f.ts > newest.ts) newest = f
            }

            // events 를 뺀 메타만 넘긴다 — 서버은 type/client/hostname 만 본다.
            val entry = JSONObject()
            for (k in meta.keys()) {
                if (k != "events") entry.put(k, meta.get(k))
            }
            entry.put("events", arr)

            out.put(bucketId, entry)
            cursors[bucketId] = Cursor(newest.ts, newest.durationMs)
            total += arr.length()
        }

        if (out.length() == 0) return null
        return Pending(JSONObject().put("buckets", out), cursors, total)
    }

    /**
     * 커서 **이상**의 이벤트를 시간 오름차순으로 돌려준다.
     *
     * 최신순으로 받아 커서까지 잘라내고 뒤집는다. 받은 것이 **전부** 커서 이후면 더 오래된
     * 미전송분이 남아 있을 수 있으므로 조회 폭을 넓혀 다시 본다.
     *
     * ★ 경계가 `>` 가 아니라 `>=` 인 것이 핵심이다. 하트비트로 **자라는 중인 이벤트**는
     *   timestamp 가 그대로라, `>` 로 자르면 첫 스냅샷만 나가고 그 뒤로 자란 만큼이
     *   영영 안 간다 (실측은 [LTSettings.getCursorDuration] 주석에 있다).
     *   자라지 않은 경계 이벤트는 호출부가 길이를 비교해 걸러낸다.
     */
    private fun fetchSince(
        bucketId: String,
        cursor: Long,
        room: Int
    ): List<Fetched> {
        var limit = FETCH_CHUNK
        while (true) {
            // ★ limit 을 반드시 명시한다 — 기본값 0 은 SQL `LIMIT 0` 이라 0 건이 온다.
            val events = try {
                ri.getEventsJSON(bucketId, limit = limit)
            } catch (e: Exception) {
                Log.w(TAG, "버킷 이벤트 조회 실패")
                return emptyList()
            }
            if (events.length() == 0) return emptyList()

            val picked = ArrayList<Fetched>()
            var sawOld = false
            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                val ts = parseTs(ev.optString("timestamp"))
                if (ts == null) continue
                if (ts >= cursor) {
                    val durMs = (ev.optDouble("duration", 0.0) * 1000.0).toLong()
                    picked.add(Fetched(ts, durMs, ev))
                } else {
                    sawOld = true
                    break
                }
            }

            // 오래된 것이 하나라도 보였으면 커서까지 다 훑은 것이다.
            if (sawOld || events.length() < limit || limit >= FETCH_MAX) {
                picked.reverse() // 최신순 → 시간순
                return if (picked.size > room) picked.subList(0, room) else picked
            }
            limit = minOf(limit * 4, FETCH_MAX) // 전부 새 것이었다 — 더 넓게 본다
        }
    }

    /** aw-server-rust 는 RFC3339(UTC, 소수초 자릿수 가변)로 준다. 문자열은 손대지 않는다. */
    private fun parseTs(s: String?): Long? {
        if (s == null || s.isEmpty()) return null
        return try {
            OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (e: Exception) {
            Log.w(TAG, "이벤트 timestamp 해석 실패")
            null
        }
    }

    // ── 서명 + 전송 ──────────────────────────────────────────────────

    private fun post(body: ByteArray): Result {
        val url = settings.serverUrl + "/ingest/aw"
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Result.Retry("URL 이 올바르지 않다: $url (${e.message})")
        }

        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty(
                "Authorization",
                LTAuth.header(settings.deviceName, settings.getSecret(), body)
            )
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""

            return when (code) {
                200 -> {
                    val j = JSONObject(text)
                    val arr = j.optJSONArray("skipped")
                    val skipped = ArrayList<String>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) skipped.add(arr.optString(i))
                    }
                    Result.Ok(j.optInt("buckets"), j.optInt("events"), skipped)
                }
                // 서버가 실패 사유를 본문에 그대로 돌려준다. 검증 순서가
                // 서명 → 시계 → nonce 라, "시각" 이야기가 나오면 **서명은 이미 통과한 것** =
                // 시크릿은 맞고 시계만 틀렸다는 뜻이다. 추측하지 말고 이 문장을 읽는다.
                401 -> {
                    settings.lastAuthError = text.take(300)
                    Result.Fatal(401, text)
                }
                404 -> Result.Fatal(404, "서버에서 수신이 꺼져 있다 (ingest.enabled=false)")
                413 -> Result.Fatal(413, "본문이 너무 크다 — 더 잘게 보내야 한다")
                400 -> Result.Fatal(400, text)
                else -> Result.Retry("HTTP " + code + ": " + text.take(200))
            }
        } catch (e: Exception) {
            return Result.Retry("전송 실패: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }
}
