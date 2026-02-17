package com.gelafit.kiosk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.ActivityOptions
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
import kotlinx.coroutines.withContext

class KioskOrchestratorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionStarted = false
    private var lastKnownActive = false
    private var lastKnownKioskMode = false
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
        val forceStart = intent?.getBooleanExtra(EXTRA_FORCE_START, false) ?: false
        val restartSequence = intent?.getBooleanExtra(EXTRA_RESTART_SEQUENCE, false) ?: false

        serviceScope.launch {
            if (forceStart) {
                val repo = repository
                if (repo != null) {
                    repo.ensureDeviceRegistered()
                    repo.setDeviceState(isActive = true, kioskMode = true)
                }
                AppPrefs.setLocalKioskLock(this@KioskOrchestratorService, true)
                if (restartSequence) {
                    sessionStarted = false
                }
                ensureAppsRunning(localConfig, kioskMode = true)
                updateNotification("Iniciado manualmente: apps em execucao")
            }
            runLoop(localConfig)
        }
        return START_STICKY
    }

    private suspend fun runLoop(localConfig: OrchestratorConfig) {
        while (serviceScope.isActive) {
            var nextDelay = POLL_INTERVAL_MS
            try {
                val repo = repository ?: break
                repo.ensureDeviceRegistered()
                val state = repo.fetchDeviceState()
                if (state != null) {
                    repo.touchLastSeen()
                    lastKnownActive = state.isActive
                    lastKnownKioskMode = state.kioskMode
                    AppPrefs.setLocalKioskLock(this, lastKnownActive && lastKnownKioskMode)
                }

                val shouldRun = if (state != null) {
                    state.isActive
                } else {
                    lastKnownActive || AppPrefs.isLocalKioskLockEnabled(this)
                }
                val kioskEnabled = if (state != null) {
                    state.kioskMode
                } else {
                    lastKnownKioskMode || AppPrefs.isLocalKioskLockEnabled(this)
                }

                if (shouldRun) {
                    ensureAppsRunning(localConfig, kioskEnabled)
                    ensureKioskForeground(localConfig, kioskEnabled)
                    checkRemoteCommands(localConfig, repo)
                    updateNotification("Ativo: Servidor + GelaFit GO monitorados")
                    if (kioskEnabled) {
                        nextDelay = KIOSK_RELAUNCH_INTERVAL_MS
                    }
                } else {
                    sessionStarted = false
                    lastKnownActive = false
                    lastKnownKioskMode = false
                    AppPrefs.setLocalKioskLock(this, false)
                    KioskPolicyManager.clearKioskPolicies(this)
                    updateNotification("Inativo: aguardando devices.is_active = true")
                }
            } catch (_: Throwable) {
                // Mantem o kiosk localmente mesmo durante intermitencia de rede.
                if (lastKnownActive && lastKnownKioskMode) {
                    ensureKioskForeground(localConfig, kioskMode = true)
                }
                updateNotification("Falha de comunicacao. Tentando novamente...")
            }
            delay(nextDelay)
        }
    }

    private suspend fun ensureAppsRunning(localConfig: OrchestratorConfig, kioskMode: Boolean) {
        if (!sessionStarted) {
            launchApp(localConfig.servidorPackage)
            delay(SERVIDOR_TO_GO_DELAY_MS)
            launchKioskApp(localConfig.gelaFitGoPackage, kioskMode)
            sessionStarted = true
        } else if (kioskMode) {
            launchKioskApp(localConfig.gelaFitGoPackage, true)
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
                    delay(SERVIDOR_TO_GO_DELAY_MS)
                    launchKioskApp(localConfig.gelaFitGoPackage, true)
                    if (repo.markCommandExecuted(remote.id)) {
                        updateNotification("Comando restart_apps executado")
                    }
                }
                "start_kiosk" -> {
                    KioskPolicyManager.applyKioskPolicies(this, localConfig.gelaFitGoPackage)
                    launchKioskApp(localConfig.gelaFitGoPackage, true)
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

    private suspend fun ensureKioskForeground(localConfig: OrchestratorConfig, kioskMode: Boolean) {
        if (!kioskMode) return
        launchKioskApp(localConfig.gelaFitGoPackage, kioskMode = true)
    }

    private suspend fun launchApp(packageName: String): Boolean {
        if (!isInstalled(packageName)) return false
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        withContext(Dispatchers.Main) {
            startActivity(launchIntent)
        }
        return true
    }

    private suspend fun launchKioskApp(packageName: String, kioskMode: Boolean): Boolean {
        if (!isInstalled(packageName)) return false
        if (kioskMode) {
            KioskPolicyManager.applyKioskPolicies(this, packageName)
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

        if (kioskMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            KioskPolicyManager.isDeviceOwner(this)
        ) {
            val options = ActivityOptions.makeBasic()
            withContext(Dispatchers.Main) {
                runCatching {
                    options.setLockTaskEnabled(true)
                    startActivity(launchIntent, options.toBundle())
                }.onFailure {
                    startActivity(launchIntent)
                }
            }
            return true
        }

        withContext(Dispatchers.Main) {
            startActivity(launchIntent)
        }
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
        private const val POLL_INTERVAL_MS = 5_000L
        private const val KIOSK_RELAUNCH_INTERVAL_MS = 2_000L
        private const val SERVIDOR_TO_GO_DELAY_MS = 5_000L
        private const val EXTRA_FORCE_START = "extra_force_start"
        private const val EXTRA_RESTART_SEQUENCE = "extra_restart_sequence"

        fun start(
            context: android.content.Context,
            forceStart: Boolean = false,
            restartSequence: Boolean = false
        ) {
            val intent = Intent(context, KioskOrchestratorService::class.java)
            intent.putExtra(EXTRA_FORCE_START, forceStart)
            intent.putExtra(EXTRA_RESTART_SEQUENCE, restartSequence)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, KioskOrchestratorService::class.java))
        }
    }
}
