/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.push

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.activitywatch.android.BackgroundService
import net.activitywatch.android.R

/**
 * 서버 push 설정 화면.
 *
 * **시크릿을 소스에 하드코딩하지 않으려고 존재한다.** 값은 [LTSettings] 가
 * Android Keystore 로 감싸서 저장한다.
 */
private const val TAG = "LTPushSettings"

class LTPushSettingsActivity : AppCompatActivity() {

    private lateinit var settings: LTSettings

    private lateinit var switchEnabled: SwitchCompat
    private lateinit var etDevice: EditText
    private lateinit var etUrl: EditText
    private lateinit var etSecret: EditText
    private lateinit var etInterval: EditText
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lt_push_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.lt_push_title)

        settings = LTSettings(this)

        switchEnabled = findViewById(R.id.switch_push_enabled)
        etDevice = findViewById(R.id.et_device)
        etUrl = findViewById(R.id.et_url)
        etSecret = findViewById(R.id.et_secret)
        etInterval = findViewById(R.id.et_interval)
        tvStatus = findViewById(R.id.tv_push_status)

        etDevice.setText(settings.deviceName)
        etUrl.setText(settings.serverUrl)
        etInterval.setText(settings.pushIntervalMinutes.toString())
        switchEnabled.isChecked = settings.enabled
        // 시크릿은 다시 보여주지 않는다. 비워 두면 기존 값을 유지한다.
        etSecret.setText("")

        findViewById<Button>(R.id.btn_save).setOnClickListener { save(showToast = true) }
        findViewById<Button>(R.id.btn_test).setOnClickListener { testNow() }

        refreshStatus()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun save(showToast: Boolean) {
        settings.deviceName = etDevice.text.toString()
        settings.serverUrl = etUrl.text.toString()
        settings.pushIntervalMinutes =
            etInterval.text.toString().toIntOrNull() ?: LTSettings.DEFAULT_PUSH_INTERVAL_MIN

        val typed = etSecret.text.toString()
        if (typed.isNotEmpty()) {
            settings.setSecret(typed)
            etSecret.setText("")
        }
        settings.enabled = switchEnabled.isChecked

        // 설정을 고쳤으니 이전 401 기록을 지운다 — 그러지 않으면 전송이 계속 멈춰 있다.
        settings.lastAuthError = null

        // 돌고 있는 BackgroundService 에 알려 스케줄러를 즉시 반영시킨다.
        // 이게 없으면 전송을 켜도 서비스가 재시작될 때까지 자동 전송이 시작되지 않는다.
        //
        // 실패해도 저장 자체는 살린다 — 여기서 예외가 나가면 화면이 아무 반응도 없이
        // 끝나 버려서, 사용자는 무엇이 잘못됐는지 알 수 없게 된다.
        try {
            startService(Intent(this, BackgroundService::class.java).apply {
                action = BackgroundService.ACTION_PUSH_SETTINGS_CHANGED
            })
        } catch (e: Exception) {
            Log.w(TAG, "BackgroundService 통지 실패")
        }

        if (showToast) {
            Toast.makeText(this, getString(R.string.lt_push_saved), Toast.LENGTH_SHORT).show()
        }
        refreshStatus()
    }

    /**
     * 지금 한 번 보내 본다.
     *
     * 결과를 **그대로** 보여준다. 특히 401 은 서버가 사유를 본문에 돌려주는데,
     * 검증 순서가 서명 → 시각 → nonce 라 "시각" 이야기가 나오면 시크릿은 맞고
     * 폰 시계만 틀린 것이다. 추측하지 않고 이 문장을 읽으면 된다.
     */
    private fun testNow() {
        // ★ 어떤 예외가 나도 화면에는 반드시 무언가 남아야 한다.
        //   그러지 않으면 "눌러도 아무것도 안 뜬다" 가 되어 진단이 불가능해진다.
        try {
            save(showToast = false)
            if (!settings.isConfigured()) {
                show(getString(R.string.lt_push_status_incomplete))
                return
            }
            tvStatus.text = getString(R.string.lt_push_testing)
            CoroutineScope(Dispatchers.Main).launch {
                val text = try {
                    when (val r = withContext(Dispatchers.IO) {
                        PushSender(this@LTPushSettingsActivity).pushOnce()
                    }) {
                        is PushSender.Result.Ok ->
                            "OK — 버킷 ${r.buckets}개 / 이벤트 ${r.events}건" +
                                if (r.skipped.isEmpty()) "" else "\n건너뜀: ${r.skipped}"
                        is PushSender.Result.NothingToSend -> "보낼 새 이벤트가 없다 (연결은 정상)"
                        is PushSender.Result.Skipped ->
                            "건너뜀 — 전송이 꺼져 있거나 설정이 비어 있다" +
                                (settings.lastAuthError?.let { "\n이전 인증 실패: $it" } ?: "")
                        is PushSender.Result.Fatal -> "실패 HTTP ${r.code}\n${r.reason}"
                        is PushSender.Result.Retry -> "일시적 실패\n${r.reason}"
                    }
                } catch (e: Throwable) {
                    // 네이티브 라이브러리 로드 실패 등도 여기로 온다.
                    Log.e(TAG, "전송 시도 중 예외")
                    "예외: ${e.javaClass.simpleName}\n${e.message ?: "(메시지 없음)"}"
                }
                show(text)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "testNow 실패")
            show("예외: ${e.javaClass.simpleName}\n${e.message ?: "(메시지 없음)"}")
        }
    }

    /** 상태 텍스트 + Toast 를 함께 띄운다. 작은 회색 글씨만으로는 놓치기 쉽다. */
    private fun show(text: String) {
        tvStatus.text = text
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun refreshStatus() {
        val err = settings.lastAuthError
        tvStatus.text = when {
            err != null -> "인증 실패로 멈춰 있다:\n$err"
            !settings.isConfigured() -> getString(R.string.lt_push_status_incomplete)
            !settings.enabled -> getString(R.string.lt_push_status_off)
            else -> getString(R.string.lt_push_status_on, settings.pushIntervalMinutes)
        }
    }
}
