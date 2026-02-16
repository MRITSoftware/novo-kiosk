package com.gelafit.kiosk

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

data class DeviceState(
    val isActive: Boolean,
    val kioskMode: Boolean
)

data class RemoteCommand(
    val id: String,
    val command: String
)

class SupabaseRepository(private val config: OrchestratorConfig) {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun ensureDeviceRegistered() {
        val withSiteId = JSONObject()
            .put("device_id", config.deviceId)
            .put("site_id", config.siteId)
            .put("unit_name", config.siteId)
            .put("last_seen", Instant.now().toString())
            .toString()

        val upsertUrl = "${config.baseUrl}/rest/v1/devices"
        val firstTry = baseRequest(upsertUrl)
            .post(withSiteId.toRequestBody(jsonMediaType))
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .build()

        client.newCall(firstTry).execute().use { response ->
            if (response.isSuccessful) return
        }

        // Fallback: se a coluna site_id nao existir no schema, registra so com unit_name.
        val fallbackPayload = JSONObject()
            .put("device_id", config.deviceId)
            .put("unit_name", config.siteId)
            .put("last_seen", Instant.now().toString())
            .toString()
        val fallback = baseRequest(upsertUrl)
            .post(fallbackPayload.toRequestBody(jsonMediaType))
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .build()
        client.newCall(fallback).execute().close()
    }

    fun fetchDeviceState(): DeviceState? {
        val deviceId = encode(config.deviceId)
        val url =
            "${config.baseUrl}/rest/v1/devices?device_id=eq.$deviceId&select=is_active,kiosk_mode&limit=1"

        val request = baseRequest(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            val arr = JSONArray(body)
            if (arr.length() == 0) return null
            val obj = arr.getJSONObject(0)
            return DeviceState(
                isActive = obj.optBoolean("is_active", false),
                kioskMode = obj.optBoolean("kiosk_mode", false)
            )
        }
    }

    fun fetchPendingCommands(): List<RemoteCommand> {
        val deviceId = encode(config.deviceId)
        val url = buildString {
            append(config.baseUrl)
            append("/rest/v1/device_commands?")
            append("device_id=eq.")
            append(deviceId)
            append("&executed=is.false")
            append("&select=id,command")
            append("&order=created_at.asc")
            append("&limit=20")
        }

        val request = baseRequest(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            val arr = JSONArray(body)
            val output = mutableListOf<RemoteCommand>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                output.add(
                    RemoteCommand(
                        id = obj.getString("id"),
                        command = obj.getString("command")
                    )
                )
            }
            return output
        }
    }

    fun markCommandExecuted(commandId: String): Boolean {
        val id = encode(commandId)
        val url = "${config.baseUrl}/rest/v1/device_commands?id=eq.$id"
        val payload = JSONObject()
            .put("executed", true)
            .put("executed_at", Instant.now().toString())
            .toString()

        val request = baseRequest(url)
            .patch(payload.toRequestBody(jsonMediaType))
            .header("Prefer", "return=minimal")
            .build()

        client.newCall(request).execute().use { response ->
            return response.isSuccessful
        }
    }

    fun touchLastSeen() {
        val deviceId = encode(config.deviceId)
        val url = "${config.baseUrl}/rest/v1/devices?device_id=eq.$deviceId"
        val payload = JSONObject().put("last_seen", Instant.now().toString()).toString()
        val request = baseRequest(url)
            .patch(payload.toRequestBody(jsonMediaType))
            .header("Prefer", "return=minimal")
            .build()
        client.newCall(request).execute().close()
    }

    private fun baseRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("apikey", config.apiKey)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
        }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }
}
