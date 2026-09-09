/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.push

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 자가 호스팅 서버 push 설정. `device` · `secret` · URL · 주기 · 버킷별 커서.
 *
 * **시크릿을 소스에 넣지 않는다** — 커밋에 들어가고, 한 번 공개되면 히스토리에 남는다.
 * 사용자가 설정 화면에서 넣고, 여기서 **Android Keystore 로 감싸서** 저장한다.
 *
 * `EncryptedSharedPreferences` 를 쓰지 않는 이유: 수년째 alpha 이고 최근 릴리스에서
 * deprecated 로 표시됐다. 보안 경로에 alpha 의존성을 새로 들이는 것보다,
 * 하드웨어 지원 Keystore 의 AES/GCM 을 그대로 쓰는 편이 낫다 (minSdk 26 이라 항상 있다).
 *
 * 평문 prefs 는 클라우드 백업으로 샐 수 있는데, 이 앱은 `allowBackup="false"` +
 * `dataExtractionRules` 로 백업 자체를 막아 뒀다. Keystore 키는 애초에 기기를 못 벗어난다.
 */
class LTSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("LTPush", Context.MODE_PRIVATE)

    companion object {
        const val TAG = "LTSettings"
        private const val KEY_ALIAS = "lt_push_secret_key"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12

        const val DEFAULT_PUSH_INTERVAL_MIN = 5

        /** 한 요청에 담을 이벤트 상한. 서버 한도는 50,000 건 / 4 MB 다. */
        const val MAX_EVENTS_PER_PUSH = 5_000
    }

    // ── 평범한 설정값 ────────────────────────────────────────────────

    var deviceName: String
        get() = prefs.getString("device", "") ?: ""
        set(v) = prefs.edit().putString("device", v.trim()).apply()

    /** 예: https://sync.example.com — 끝의 / 는 떼서 보관한다. */
    var serverUrl: String
        get() = prefs.getString("url", "") ?: ""
        set(v) = prefs.edit().putString("url", v.trim().trimEnd('/')).apply()

    var pushIntervalMinutes: Int
        get() = prefs.getInt("intervalMin", DEFAULT_PUSH_INTERVAL_MIN)
        set(v) = prefs.edit().putInt("intervalMin", v.coerceIn(1, 24 * 60)).apply()

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /**
     * 서명·시각·nonce 문제로 401 을 받았다는 표시.
     *
     * **401 은 재시도해도 똑같다** — 설정이 틀린 것이라 배터리만 먹는다. 그래서 멈추고
     * 사유를 남긴다. 사용자가 설정을 고치면 지워진다.
     */
    var lastAuthError: String?
        get() = prefs.getString("lastAuthError", null)
        set(v) = prefs.edit().putString("lastAuthError", v).apply()

    fun isConfigured(): Boolean =
        deviceName.isNotEmpty() && serverUrl.isNotEmpty() && getSecret().isNotEmpty()

    // ── 버킷별 커서 (마지막으로 보낸 이벤트 시각, epoch millis) ────────
    //
    // 늦게 도착하는 것은 괜찮지만 빠지는 것은 안 된다. 그래서 커서는 **200 을 받은 뒤에만**
    // 전진시킨다.

    fun getCursor(bucketId: String): Long = prefs.getLong("cursor:$bucketId", 0L)

    /**
     * 커서 이벤트를 **마지막으로 보냈을 때의 길이** (밀리초). 없으면 -1.
     *
     * ★ 커서만으로는 부족하다. AW 는 하트비트로 **같은 timestamp 의 이벤트를 계속
     *   늘린다** — 재생 중인 곡, 이어지는 afk 구간이 그렇다. `ts` 만 보고 자르면 그
     *   이벤트의 **첫 스냅샷**만 서버에 남고, 이후 자란 만큼은 영영 안 간다.
     *   서버는 `ON CONFLICT(bucket_id, ts) DO UPDATE SET duration=MAX(...)` 로 긴 쪽을
     *   남기므로, **다시 보내기만 하면** 받아 준다.
     *
     *   기본값이 -1 인 것은 의도다 — 이 값을 모르는 기존 설치가 커서 이벤트를 **한 번**
     *   다시 보내 이미 잘려 있는 것을 메우게 한다.
     */
    fun getCursorDuration(bucketId: String): Long = prefs.getLong("cursordur:$bucketId", -1L)

    fun setCursor(bucketId: String, tsMillis: Long, durationMillis: Long) {
        prefs.edit()
            .putLong("cursor:$bucketId", tsMillis)
            .putLong("cursordur:$bucketId", durationMillis)
            .apply()
    }

    // ── 시크릿 (Keystore AES/GCM) ────────────────────────────────────

    fun setSecret(secret: String) {
        if (secret.isEmpty()) {
            prefs.edit().remove("secretCipher").apply()
            return
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            // iv || ciphertext 를 한 덩어리로 저장한다. IV 는 비밀이 아니다.
            val blob = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, blob, 0, iv.size)
            System.arraycopy(ct, 0, blob, iv.size, ct.size)
            prefs.edit()
                .putString("secretCipher", Base64.encodeToString(blob, Base64.NO_WRAP))
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "시크릿 암호화 실패")
        }
    }

    fun getSecret(): String {
        val b64 = prefs.getString("secretCipher", null) ?: return ""
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            if (blob.size <= IV_BYTES) return ""
            val iv = blob.copyOfRange(0, IV_BYTES)
            val ct = blob.copyOfRange(IV_BYTES, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            // 키가 사라졌거나(기기 초기화·지문 재등록 등) 데이터가 깨진 경우.
            // 조용히 빈 값을 돌려주면 원인 모를 401 로 이어지므로 로그를 남긴다.
            Log.e(TAG, "시크릿 복호화 실패 — 설정 화면에서 다시 입력해야 한다")
            ""
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // 잠금화면 없이도 백그라운드에서 5분마다 읽어야 한다.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }
}
