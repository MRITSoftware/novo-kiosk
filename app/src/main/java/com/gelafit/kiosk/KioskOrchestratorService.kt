package com.gelafit.kiosk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class KioskOrchestratorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionStarted = false
    private var repository: SupabaseRepository? = null
    private var config: OrchestratorConfig? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Orquestrador iniciando..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        config = AppPrefs.readConfig(this)
        val localConfig = config
        if (localConfig == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        repository = SupabaseRepository(localConfig)

        serviceScope.launch {
            runLoop(localConfig)
        }
        return START_STICKY
    }

    private suspend fun runLoop(localConfig: OrchestratorConfig) {
        while (serviceScope.isActive) {
            try {
                val repo = repository ?: break
                val state = repo.fetchDeviceState()
                repo.touchLastSeen()

                if (state?.isActive == true) {
                    ensureAppsRunning(localConfig, state.kioskMode)
                    checkRemoteCommands(localConfig, repo)
                    updateNotification("Ativo: Servidor + GelaFit GO monitorados")
                } else {
                    sessionStarted = false
                    KioskPolicyManager.clearKioskPolicies(this)
                    updateNotification("Inativo: aguardando devices.is_active = true")
                }
            } catch (_: Throwable) {
                updateNotification("Falha de comunicacao. Tentando novamente...")
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun ensureAppsRunning(localConfig: OrchestratorConfig, kioskMode: Boolean) {
        if (!sessionStarted) {
            launchApp(localConfig.servidorPackage)
            delay(4000)
            launchApp(localConfig.gelaFitGoPackage)
            sessionStarted = true
        } else if (kioskMode) {
            launchApp(localConfig.gelaFitGoPackage)
        }

        if (kioskMode) {
            KioskPolicyManager.applyKioskPolicies(this, localConfig.gelaFitGoPackage)
        }
    }

    private suspend fun checkRemoteCommands(localConfig: OrchestratorConfig, repo: SupabaseRepository) {
        val pending = repo.fetchPendingCommands()
        if (pending.isEmpty()) return

        pending.forEach { remote ->
            when (remote.command.trim().lowercase()) {
                "restart_apps" -> {
                    sessionStarted = false
                    launchApp(localConfig.servidorPackage)
                    delay(3000)
                    launchApp(localConfig.gelaFitGoPackage)
                    if (repo.markCommandExecuted(remote.id)) {
                        updateNotification("Comando restart_apps executado")
                    }
                }
                "start_kiosk" -> {
                    KioskPolicyManager.applyKioskPolicies(this, localConfig.gelaFitGoPackage)
                    launchApp(localConfig.gelaFitGoPackage)
                    repo.markCommandExecuted(remote.id)
                }
                "stop_kiosk" -> {
                    KioskPolicyManager.clearKioskPolicies(this)
                    repo.markCommandExecuted(remote.id)
                }
                else -> {
                    // Marca como executado para nao ficar em loop com comando desconhecido.
                    repo.markCommandExecuted(remote.id)
                }
            }
        }
    }

    private fun launchApp(packageName: String): Boolean {
        if (!isInstalled(packageName)) return false
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        startActivity(launchIntent)
        return true
    }

    private fun isInstalled(packageName: String): Boolean {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0)
        }.isSuccess
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GelaFit Kiosk",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Controle 24/7 do Servidor e GelaFit GO"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("GelaFit Kiosk Orchestrator")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "gelafit_kiosk_channel"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 15_000L

        fun start(context: android.content.Context) {
            val intent = Intent(context, KioskOrchestratorService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, KioskOrchestratorService::class.java))
        }
    }
}
