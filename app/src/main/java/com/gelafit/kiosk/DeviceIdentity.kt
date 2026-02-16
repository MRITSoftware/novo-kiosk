package com.gelafit.kiosk

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

object DeviceIdentity {
    fun stableDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        val raw = "${Build.BRAND}|${Build.MODEL}|$androidId"
        return "dev-${sha256(raw).take(24)}"
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
