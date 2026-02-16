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
import androidx.lifecycle.lifecycleScope
import com.gelafit.kiosk.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(val label: String, val packageName: String) {
    override fun toString(): String = "$label ($packageName)"
}

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var installedApps: List<InstalledApp> = emptyList()
    private lateinit var appsAdapter: ArrayAdapter<InstalledApp>
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
            startFlow()
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

    override fun onResume() {
        super.onResume()
        enforceKioskLockIfNeeded()
    }

    private fun setupSearchableSelectors() {
        appsAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            installedApps
        )
        binding.actServidor.setAdapter(appsAdapter)
        binding.actGelaFitGo.setAdapter(appsAdapter)
        binding.actServidor.threshold = 1
        binding.actGelaFitGo.threshold = 1

        binding.actServidor.onItemClickListener =
            AdapterView.OnItemClickListener { parent, _, position, _ ->
                servidorSelecionado = parent.getItemAtPosition(position) as? InstalledApp
            }
        binding.actGelaFitGo.onItemClickListener =
            AdapterView.OnItemClickListener { parent, _, position, _ ->
                gelaFitGoSelecionado = parent.getItemAtPosition(position) as? InstalledApp
            }
    }

    private fun fillDefaults() {
        val generatedDeviceId = DeviceIdentity.stableDeviceId(this)
        binding.etDeviceIdGerado.setText(generatedDeviceId)
    }

    private fun loadConfig() {
        val config = AppPrefs.readConfig(this) ?: return
        binding.etSiteId.setText(config.siteId)
        binding.etDeviceIdGerado.setText(config.deviceId)

        servidorSelecionado = installedApps.firstOrNull { it.packageName == config.servidorPackage }
        gelaFitGoSelecionado = installedApps.firstOrNull { it.packageName == config.gelaFitGoPackage }
        servidorSelecionado?.let {
            binding.actServidor.setText(it.toString(), false)
        }
        gelaFitGoSelecionado?.let {
            binding.actGelaFitGo.setText(it.toString(), false)
        }
    }

    private fun saveConfig(): OrchestratorConfig? {
        if (installedApps.isEmpty()) {
            updateInfo("Nenhum app launchable encontrado.")
            return null
        }

        val servidor = servidorSelecionado ?: findAppBySearch(binding.actServidor.text.toString())
        val go = gelaFitGoSelecionado ?: findAppBySearch(binding.actGelaFitGo.text.toString())
        if (servidor == null || go == null) {
            updateInfo("Selecione apps validos para Servidor e GelaFit GO.")
            return null
        }

        val siteId = binding.etSiteId.text.toString().trim()
        if (siteId.isBlank()) {
            updateInfo("Informe o email da unidade para salvar em devices.site_id.")
            return null
        }

        val generatedDeviceId = DeviceIdentity.stableDeviceId(this)
        binding.etDeviceIdGerado.setText(generatedDeviceId)

        val config = OrchestratorConfig(
            baseUrl = AppPrefs.DEFAULT_BASE_URL,
            apiKey = AppPrefs.DEFAULT_ANON_KEY,
            siteId = siteId,
            deviceId = generatedDeviceId,
            servidorPackage = servidor.packageName,
            gelaFitGoPackage = go.packageName
        )
        AppPrefs.saveConfig(this, config)
        updateInfo(
            "Configuracao salva.\nsite_id: $siteId\ndevice_id: $generatedDeviceId\nServidor: ${servidor.packageName}\nGelaFit GO: ${go.packageName}"
        )
        return config
    }

    private fun startFlow() {
        val config = saveConfig() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val repo = SupabaseRepository(config)
            repo.ensureDeviceRegistered()
            repo.setDeviceState(isActive = true, kioskMode = true)
            withContext(Dispatchers.Main) {
                KioskOrchestratorService.start(this@MainActivity, forceStart = true)
                updateInfo(
                    "Iniciado: devices.is_active=true e devices.kiosk_mode=true.\n" +
                        "Servidor e GelaFit GO em execucao."
                )
            }
        }
    }

    private fun enforceKioskLockIfNeeded() {
        val config = AppPrefs.readConfig(this) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val state = SupabaseRepository(config).fetchDeviceState()
            val shouldLock = state?.isActive == true && state.kioskMode
            if (shouldLock) {
                withContext(Dispatchers.Main) {
                    val launchIntent =
                        packageManager.getLaunchIntentForPackage(config.gelaFitGoPackage)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                        moveTaskToBack(true)
                    }
                }
            }
        }
    }

    private fun findAppBySearch(text: String): InstalledApp? {
        val query = text.trim()
        if (query.isBlank()) return null
        val byPackage = installedApps.firstOrNull { it.packageName.equals(query, ignoreCase = true) }
        if (byPackage != null) return byPackage
        val exactFormatted = installedApps.firstOrNull {
            it.toString().equals(query, ignoreCase = true)
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
