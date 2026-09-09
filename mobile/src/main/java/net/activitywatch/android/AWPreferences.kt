package net.activitywatch.android

import android.content.Context
import android.content.SharedPreferences

class AWPreferences(context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("AWPreferences", Context.MODE_PRIVATE)

    // To check if it is the first time the app is being run
    // Set to false when user finishes onboarding
    fun isFirstTime(): Boolean {
        return sharedPreferences.getBoolean("isFirstTime", true)
    }

    // To set the first time flag to false after the first run
    fun setFirstTimeRunFlag() {
        val editor = sharedPreferences.edit()
        editor.putBoolean("isFirstTime", false)
        editor.apply()
    }

    // Optional: To reset the first time flag to true (for debugging, perhaps)
    fun resetFirstTimeRunFlag() {
        val editor = sharedPreferences.edit()
        editor.putBoolean("isFirstTime", true)
        editor.apply()
    }

    // To check if the hostname migration has already been run
    fun hasMigratedHostname(): Boolean {
        return sharedPreferences.getBoolean("hasMigratedHostname", false)
    }

    // To mark the hostname migration as done so it won't run again
    fun setHostnameMigrated() {
        sharedPreferences.edit().putBoolean("hasMigratedHostname", true).apply()
    }

    fun hasRequestedNotificationPermission(): Boolean {
        return sharedPreferences.getBoolean("hasRequestedNotificationPermission", false)
    }

    fun setNotificationPermissionRequested() {
        sharedPreferences.edit().putBoolean("hasRequestedNotificationPermission", true).apply()
    }

    // Sync is off by default; user must explicitly enable it once a sync directory
    // is configured (e.g. via Storage Access Framework).
    fun isSyncEnabled(): Boolean {
        return sharedPreferences.getBoolean("syncEnabled", false)
    }

    fun setSyncEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean("syncEnabled", enabled).apply()
    }

    // SAF URI for the user-chosen sync directory (content:// URI string, or null if not configured).
    // The URI has persisted permission granted via contentResolver.takePersistableUriPermission().
    fun getSyncDirUri(): String? {
        return sharedPreferences.getString("syncDirUri", null)
    }

    fun setSyncDirUri(uri: String?) {
        sharedPreferences.edit().putString("syncDirUri", uri).apply()
    }

    // Dashboard authentication. Defaults to true so first-run gets a key generated
    // automatically. Set to false when the user explicitly disables auth in settings;
    // ensureDashboardApiKey() checks this before generating a new key so that the
    // "disabled" setting survives app restarts.
    fun isDashboardAuthEnabled(): Boolean {
        return sharedPreferences.getBoolean("dashboardAuthEnabled", true)
    }

    fun setDashboardAuthEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean("dashboardAuthEnabled", enabled).commit()
    }

    // LT: UsageStatsWatcher 의 수집 주기(분). 원본은 1시간 고정이었다.
    //
    // OS 가 사용 통계를 자체적으로 계속 기록하고 우리는 커서로 따라잡는 구조라,
    // 이 값은 *정확도*가 아니라 **도착이 얼마나 늦는지**만 정한다. 그래서
    // setInexactRepeating 을 그대로 쓴다 — 정확한 알람은 배터리만 먹고 Doze 와 싸운다.
    fun getCollectIntervalMinutes(): Int {
        return sharedPreferences.getInt("collectIntervalMinutes", DEFAULT_COLLECT_INTERVAL_MIN)
    }

    fun setCollectIntervalMinutes(minutes: Int) {
        sharedPreferences.edit()
            .putInt("collectIntervalMinutes", minutes.coerceIn(1, 24 * 60))
            .apply()
    }

    companion object {
        const val DEFAULT_COLLECT_INTERVAL_MIN = 5
    }
}
