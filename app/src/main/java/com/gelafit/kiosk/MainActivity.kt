package com.gelafit.kiosk

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.gelafit.kiosk.databinding.ActivityMainBinding

data class InstalledApp(val label: String, val packageName: String)

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var installedApps: List<InstalledApp> = emptyList()
    private var servidorSelecionado: InstalledApp? = null
    private var gelaFitGoSelecionado: InstalledApp? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        installedApps = readLaunchableApps()
        setupSearchableSelectors()
        fillDefaults()
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

    private fun setupSearchableSelectors() {
        val labels = installedApps.map { "${it.label} (${it.packageName})" }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        binding.actServidor.setAdapter(adapter)
        binding.actGelaFitGo.setAdapter(adapter)
        binding.actServidor.threshold = 1
        binding.actGelaFitGo.threshold = 1

        binding.actServidor.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                servidorSelecionado = installedApps.getOrNull(position)
            }
        binding.actGelaFitGo.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                gelaFitGoSelecionado = installedApps.getOrNull(position)
            }
    }

    private fun fillDefaults() {
        val generatedDeviceId = DeviceIdentity.stableDeviceId(this)
        binding.etDeviceIdGerado.setText(generatedDeviceId)
        if (binding.etBaseUrl.text.isNullOrBlank()) {
            binding.etBaseUrl.setText(AppPrefs.DEFAULT_BASE_URL)
        }
        if (binding.etApiKey.text.isNullOrBlank()) {
            binding.etApiKey.setText(AppPrefs.DEFAULT_ANON_KEY)
        }
    }

    private fun loadConfig() {
        val config = AppPrefs.readConfig(this) ?: return
        binding.etBaseUrl.setText(config.baseUrl)
        binding.etApiKey.setText(config.apiKey)
        binding.etSiteId.setText(config.siteId)
        binding.etDeviceIdGerado.setText(config.deviceId)

        servidorSelecionado = installedApps.firstOrNull { it.packageName == config.servidorPackage }
        gelaFitGoSelecionado = installedApps.firstOrNull { it.packageName == config.gelaFitGoPackage }
        servidorSelecionado?.let {
            binding.actServidor.setText("${it.label} (${it.packageName})", false)
        }
        gelaFitGoSelecionado?.let {
            binding.actGelaFitGo.setText("${it.label} (${it.packageName})", false)
        }
    }

    private fun saveConfig() {
        if (installedApps.isEmpty()) {
            updateInfo("Nenhum app launchable encontrado.")
            return
        }

        val servidor = servidorSelecionado ?: findAppBySearch(binding.actServidor.text.toString())
        val go = gelaFitGoSelecionado ?: findAppBySearch(binding.actGelaFitGo.text.toString())
        if (servidor == null || go == null) {
            updateInfo("Selecione apps validos para Servidor e GelaFit GO.")
            return
        }

        val siteId = binding.etSiteId.text.toString().trim()
        if (siteId.isBlank()) {
            updateInfo("Informe o email da unidade para salvar em devices.site_id.")
            return
        }

        val generatedDeviceId = DeviceIdentity.stableDeviceId(this)
        binding.etDeviceIdGerado.setText(generatedDeviceId)

        val config = OrchestratorConfig(
            baseUrl = binding.etBaseUrl.text.toString(),
            apiKey = binding.etApiKey.text.toString(),
            siteId = siteId,
            deviceId = generatedDeviceId,
            servidorPackage = servidor.packageName,
            gelaFitGoPackage = go.packageName
        )
        AppPrefs.saveConfig(this, config)
        updateInfo(
            "Configuracao salva.\nsite_id: $siteId\ndevice_id: $generatedDeviceId\nServidor: ${servidor.packageName}\nGelaFit GO: ${go.packageName}"
        )
    }

    private fun findAppBySearch(text: String): InstalledApp? {
        val query = text.trim()
        if (query.isBlank()) return null
        val byPackage = installedApps.firstOrNull { it.packageName.equals(query, ignoreCase = true) }
        if (byPackage != null) return byPackage
        val exactFormatted = installedApps.firstOrNull {
            val full = "${it.label} (${it.packageName})"
            full.equals(query, ignoreCase = true)
        }
        if (exactFormatted != null) return exactFormatted

        val partial = installedApps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
        return when (partial.size) {
            1 -> partial.first()
            else -> null
        }
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
