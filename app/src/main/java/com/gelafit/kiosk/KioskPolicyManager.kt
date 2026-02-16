package com.gelafit.kiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

object KioskPolicyManager {
    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, KioskAdminReceiver::class.java)
        return dpm.isAdminActive(admin)
    }

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun applyKioskPolicies(context: Context, kioskPackage: String) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, KioskAdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) return

        // Lock task e bloqueio de barra/status sao confiaveis quando o app esta como Device Owner.
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            dpm.setLockTaskPackages(admin, arrayOf(kioskPackage, context.packageName))
            runCatching { dpm.setStatusBarDisabled(admin, true) }
            runCatching { dpm.setKeyguardDisabled(admin, true) }
        }
    }

    fun clearKioskPolicies(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, KioskAdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) return
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            runCatching { dpm.setStatusBarDisabled(admin, false) }
            runCatching { dpm.setKeyguardDisabled(admin, false) }
            dpm.setLockTaskPackages(admin, emptyArray())
        }
    }

    fun adminIntent(context: Context): Intent {
        val admin = ComponentName(context, KioskAdminReceiver::class.java)
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Necessario para o modo kiosk e controle 24/7."
            )
        }
    }
}
