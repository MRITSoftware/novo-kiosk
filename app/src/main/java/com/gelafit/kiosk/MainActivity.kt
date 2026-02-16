package com.gelafit.kiosk

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.gelafit.kiosk.databinding.ActivityMainBinding

data class InstalledApp(val label: String, val packageName: String)

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var installedApps: List<InstalledApp> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        installedApps = readLaunchableApps()
        setupSpinners()
        loadConfig()

        binding.btnSalvar.setOnClickListener { saveConfig() }
        binding.btnIniciar.setOnClickListener {
            saveConfig()
            KioskOrchestratorService.start(this)
            updateInfo("Orquestrador iniciado.")
        }
        binding.btnParar.setOnClickListener {
            KioskOrchestratorService.stop(this)
            updateInfo("Orquestrador parado.")
        }
        binding.btnPermissaoAdmin.setOnClickListener {
            startActivity(KioskPolicyManager.adminIntent(this))
        }
        binding.btnPermissaoBateria.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        updateDeviceOwnerStatus()
    }

    private fun setupSpinners() {
        val labels = installedApps.map { "${it.label} (${it.packageName})" }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        binding.spServidor.adapter = adapter
        binding.spGelaFitGo.adapter = adapter
    }

    private fun loadConfig() {
        val config = AppPrefs.readConfig(this) ?: return
        binding.etBaseUrl.setText(config.baseUrl)
        binding.etApiKey.setText(config.apiKey)
        binding.etDeviceId.setText(config.deviceId)

        val servidorPos = installedApps.indexOfFirst { it.packageName == config.servidorPackage }
        val goPos = installedApps.indexOfFirst { it.packageName == config.gelaFitGoPackage }
        if (servidorPos >= 0) binding.spServidor.setSelection(servidorPos)
        if (goPos >= 0) binding.spGelaFitGo.setSelection(goPos)
    }

    private fun saveConfig() {
        if (installedApps.isEmpty()) {
            updateInfo("Nenhum app launchable encontrado.")
            return
        }
        val servidor = installedApps[binding.spServidor.selectedItemPosition]
        val go = installedApps[binding.spGelaFitGo.selectedItemPosition]

        val config = OrchestratorConfig(
            baseUrl = binding.etBaseUrl.text.toString(),
            apiKey = binding.etApiKey.text.toString(),
            deviceId = binding.etDeviceId.text.toString(),
            servidorPackage = servidor.packageName,
            gelaFitGoPackage = go.packageName
        )
        AppPrefs.saveConfig(this, config)
        updateInfo(
            "Configuracao salva.\nServidor: ${servidor.packageName}\nGelaFit GO: ${go.packageName}"
        )
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !pm.isIgnoringBatteryOptimizations(packageName)
        ) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun updateDeviceOwnerStatus() {
        val isAdmin = KioskPolicyManager.isAdminActive(this)
        val isOwner = KioskPolicyManager.isDeviceOwner(this)
        val status = buildString {
            append("Admin ativo: $isAdmin\n")
            append("Device Owner: $isOwner\n")
            append("Para kiosk completo (sem barra/botoes), use Device Owner.")
        }
        updateInfo(status)
    }

    private fun readLaunchableApps(): List<InstalledApp> {
        val launchIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = packageManager.queryIntentActivities(launchIntent, 0)
            .map {
                InstalledApp(
                    label = it.loadLabel(packageManager).toString(),
                    packageName = it.activityInfo.packageName
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        return apps
    }

    private fun updateInfo(text: String) {
        binding.tvInfo.text = text
    }
}
