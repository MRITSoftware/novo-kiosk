package com.gelafit.kiosk

import android.content.Context

data class OrchestratorConfig(
    val baseUrl: String,
    val apiKey: String,
    val siteId: String,
    val deviceId: String,
    val servidorPackage: String,
    val gelaFitGoPackage: String
)

object AppPrefs {
    const val DEFAULT_BASE_URL = "https://kihyhoqbrkwbfudttevo.supabase.co"
    const val DEFAULT_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImtpaHlob3Ficmt3YmZ1ZHR0ZXZvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3MTU1NTUwMjcsImV4cCI6MjAzMTEzMTAyN30.XtBTlSiqhsuUIKmhAMEyxofV-dRst7240n912m4O4Us"

    private const val PREFS = "gelafit_kiosk_prefs"
    private const val BASE_URL = "base_url"
    private const val API_KEY = "api_key"
    private const val SITE_ID = "site_id"
    private const val DEVICE_ID = "device_id"
    private const val SERVIDOR_PACKAGE = "servidor_package"
    private const val GELAFIT_GO_PACKAGE = "gelafit_go_package"
    private const val LOCAL_KIOSK_LOCK = "local_kiosk_lock"

    fun saveConfig(context: Context, config: OrchestratorConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(BASE_URL, config.baseUrl.trim().trimEnd('/'))
            .putString(API_KEY, config.apiKey.trim())
            .putString(SITE_ID, config.siteId.trim())
            .putString(DEVICE_ID, config.deviceId.trim())
            .putString(SERVIDOR_PACKAGE, config.servidorPackage)
            .putString(GELAFIT_GO_PACKAGE, config.gelaFitGoPackage)
            .apply()
    }

    fun readConfig(context: Context): OrchestratorConfig? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val baseUrl = prefs.getString(BASE_URL, DEFAULT_BASE_URL).orEmpty()
        val apiKey = prefs.getString(API_KEY, DEFAULT_ANON_KEY).orEmpty()
        val siteId = prefs.getString(SITE_ID, "").orEmpty()
        val deviceId = prefs.getString(DEVICE_ID, "").orEmpty()
        val servidor = prefs.getString(SERVIDOR_PACKAGE, "").orEmpty()
        val gelaFitGo = prefs.getString(GELAFIT_GO_PACKAGE, "").orEmpty()

        if (baseUrl.isBlank() || apiKey.isBlank() || siteId.isBlank() || deviceId.isBlank() ||
            servidor.isBlank() || gelaFitGo.isBlank()
        ) {
            return null
        }
        return OrchestratorConfig(baseUrl, apiKey, siteId, deviceId, servidor, gelaFitGo)
    }

    fun setLocalKioskLock(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(LOCAL_KIOSK_LOCK, value)
            .apply()
    }

    fun isLocalKioskLockEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(LOCAL_KIOSK_LOCK, false)
    }
}
