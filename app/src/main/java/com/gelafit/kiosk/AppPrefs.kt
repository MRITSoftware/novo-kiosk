package com.gelafit.kiosk

import android.content.Context

data class OrchestratorConfig(
    val baseUrl: String,
    val apiKey: String,
    val deviceId: String,
    val servidorPackage: String,
    val gelaFitGoPackage: String
)

object AppPrefs {
    private const val PREFS = "gelafit_kiosk_prefs"
    private const val BASE_URL = "base_url"
    private const val API_KEY = "api_key"
    private const val DEVICE_ID = "device_id"
    private const val SERVIDOR_PACKAGE = "servidor_package"
    private const val GELAFIT_GO_PACKAGE = "gelafit_go_package"

    fun saveConfig(context: Context, config: OrchestratorConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(BASE_URL, config.baseUrl.trim().trimEnd('/'))
            .putString(API_KEY, config.apiKey.trim())
            .putString(DEVICE_ID, config.deviceId.trim())
            .putString(SERVIDOR_PACKAGE, config.servidorPackage)
            .putString(GELAFIT_GO_PACKAGE, config.gelaFitGoPackage)
            .apply()
    }

    fun readConfig(context: Context): OrchestratorConfig? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val baseUrl = prefs.getString(BASE_URL, "").orEmpty()
        val apiKey = prefs.getString(API_KEY, "").orEmpty()
        val deviceId = prefs.getString(DEVICE_ID, "").orEmpty()
        val servidor = prefs.getString(SERVIDOR_PACKAGE, "").orEmpty()
        val gelaFitGo = prefs.getString(GELAFIT_GO_PACKAGE, "").orEmpty()

        if (baseUrl.isBlank() || apiKey.isBlank() || deviceId.isBlank() ||
            servidor.isBlank() || gelaFitGo.isBlank()
        ) {
            return null
        }
        return OrchestratorConfig(baseUrl, apiKey, deviceId, servidor, gelaFitGo)
    }
}
