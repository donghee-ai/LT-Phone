/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.push

import android.util.Base64
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 서버 `/ingest/…` 의 `LT1` 서명. **한 곳에만 둔다.**
 *
 * 서버 `web/auth.py` 의 `sign_ingest` 와 **글자 단위로 같아야 한다.**
 *
 *     Authorization: LT1 device:ts:nonce:sig
 *     sig = b64url_nopad( HMAC-SHA256(secret, "device\nts\nnonce\nsha256(body) hex") )
 *
 * `/ingest/aw`(POST)와 `/ingest/private`(GET·POST)이 같은 규격을 쓴다. 두 벌로 나뉘면
 * 언젠가 한쪽만 고쳐지고, 그 결과는 **원인이 안 보이는 401** 이다.
 */
object LTAuth {

    private const val SCHEME = "LT1"

    /**
     * @param body 실제로 보낼 바이트 그대로. 서명 후 한 바이트도 바꾸면 안 된다.
     *             ★ **GET 처럼 본문이 없으면 빈 배열을 넘긴다** — 서버는 `sha256("")` 을 기대한다.
     */
    fun header(device: String, secret: String, body: ByteArray): String {
        val ts = System.currentTimeMillis() / 1000 // ★ 정수 초. 밀리초·소수점 금지
        val nonce = UUID.randomUUID().toString() // ★ 요청마다 새로

        val bodyHex = MessageDigest.getInstance("SHA-256")
            .digest(body)
            .joinToString("") { String.format("%02x", it) } // ★ 소문자 hex

        val msg = device + "\n" + ts + "\n" + nonce + "\n" + bodyHex

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))

        // ★ NO_WRAP 이 빠지면 안드로이드가 줄바꿈을 넣어 헤더가 깨지고,
        //   원인이 보이지 않는 401 이 난다. URL_SAFE·NO_PADDING 도 전부 필수다.
        val sig = Base64.encodeToString(
            mac.doFinal(msg.toByteArray(Charsets.UTF_8)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        return SCHEME + " " + device + ":" + ts + ":" + nonce + ":" + sig
    }
}
