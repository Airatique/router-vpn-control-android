package com.airat.routervpncontrol

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var store: SettingsStore
    private lateinit var settings: AppSettings
    private lateinit var router: RouterClient

    private lateinit var tabs: TabLayout
    private lateinit var tabApp: View
    private lateinit var tabVpn: View
    private lateinit var appRouterSpinner: Spinner
    private lateinit var settingsRouterSpinner: Spinner
    private lateinit var backendSpinner: Spinner
    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView
    private lateinit var backendText: TextView
    private lateinit var scanOutput: TextView
    private lateinit var footerStatus: TextView
    private lateinit var toggleButton: Button
    private lateinit var applyBackendButton: Button
    private lateinit var scanButton: Button
    private lateinit var refreshButton: Button
    private lateinit var addRouterButton: Button
    private lateinit var deleteRouterButton: Button
    private lateinit var saveSettingsButton: Button
    private lateinit var nameEdit: EditText
    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var loginEdit: EditText
    private lateinit var passwordEdit: EditText

    private var lastStatus: RouterStatus? = null
    private var loadingRouterProfile = false

    private data class BackendChoice(
        val mode: BackendMode? = null,
        val profile: RouterBackendProfile? = null,
        val text: String
    ) {
        override fun toString(): String = text
    }

    private val backendOrder = listOf(BackendMode.HY2_89, BackendMode.HY2_194, BackendMode.VLESS_194)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = SettingsStore(this)
        settings = store.load()
        router = RouterClient(this, settings)

        bindViews()
        wireEvents()
        populateRouterSpinners()
        loadSelectedRouterProfile()

        if (settings.selectedRouter.host.isBlank()) {
            // First launch / no router configured yet: don't try to connect
            // (that would raise an error). Send the user to VPN Settings to set
            // up the router first.
            tabs.getTabAt(1)?.select()
        } else {
            lifecycleScope.launch { refreshStatus() }
        }
    }

    private fun bindViews() {
        tabs = findViewById(R.id.tabs)
        tabApp = findViewById(R.id.tabApp)
        tabVpn = findViewById(R.id.tabVpn)
        appRouterSpinner = findViewById(R.id.appRouterSpinner)
        settingsRouterSpinner = findViewById(R.id.settingsRouterSpinner)
        backendSpinner = findViewById(R.id.backendSpinner)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        backendText = findViewById(R.id.backendText)
        scanOutput = findViewById(R.id.scanOutput)
        footerStatus = findViewById(R.id.footerStatus)
        toggleButton = findViewById(R.id.toggleButton)
        applyBackendButton = findViewById(R.id.applyBackendButton)
        scanButton = findViewById(R.id.scanButton)
        refreshButton = findViewById(R.id.refreshButton)
        addRouterButton = findViewById(R.id.addRouterButton)
        deleteRouterButton = findViewById(R.id.deleteRouterButton)
        saveSettingsButton = findViewById(R.id.saveSettingsButton)
        nameEdit = findViewById(R.id.nameEdit)
        hostEdit = findViewById(R.id.hostEdit)
        portEdit = findViewById(R.id.portEdit)
        loginEdit = findViewById(R.id.loginEdit)
        passwordEdit = findViewById(R.id.passwordEdit)
    }

    private fun wireEvents() {
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tabApp.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                tabVpn.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        toggleButton.setOnClickListener {
            lifecycleScope.launch {
                if (lastStatus?.routingEnabled == true) disableVpn() else enableVpn()
            }
        }
        applyBackendButton.setOnClickListener { lifecycleScope.launch { switchSelectedBackend() } }
        scanButton.setOnClickListener { lifecycleScope.launch { scanVpn() } }
        refreshButton.setOnClickListener { lifecycleScope.launch { refreshStatus() } }
        addRouterButton.setOnClickListener { addRouter() }
        deleteRouterButton.setOnClickListener { deleteSelectedRouter() }
        saveSettingsButton.setOnClickListener { saveSettings(showMessage = true) }

        val routerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onRouterSpinnerChanged(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        appRouterSpinner.onItemSelectedListener = routerListener
        settingsRouterSpinner.onItemSelectedListener = routerListener
    }

    // ----- Router profiles -----

    private fun populateRouterSpinners() {
        loadingRouterProfile = true
        try {
            fillRouterSpinner(appRouterSpinner)
            fillRouterSpinner(settingsRouterSpinner)
            selectRouterSpinnerItem(appRouterSpinner, settings.selectedRouterId)
            selectRouterSpinnerItem(settingsRouterSpinner, settings.selectedRouterId)
        } finally {
            loadingRouterProfile = false
        }
    }

    private fun fillRouterSpinner(spinner: Spinner) {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            settings.routers.map { it.displayName }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun selectRouterSpinnerItem(spinner: Spinner, routerId: String) {
        val index = settings.routers.indexOfFirst { it.id == routerId }
        spinner.setSelection(if (index >= 0) index else 0, false)
    }

    private fun onRouterSpinnerChanged(position: Int) {
        if (loadingRouterProfile || position !in settings.routers.indices) {
            return
        }
        val selected = settings.routers[position]
        if (selected.id == settings.selectedRouterId) {
            return
        }

        saveCurrentRouterProfileFields()
        settings.selectedRouterId = selected.id
        lastStatus = null

        loadingRouterProfile = true
        try {
            selectRouterSpinnerItem(appRouterSpinner, selected.id)
            selectRouterSpinnerItem(settingsRouterSpinner, selected.id)
            loadSelectedRouterProfile()
        } finally {
            loadingRouterProfile = false
        }

        store.save(settings)
        statusDot.setTextColor(ContextCompat.getColor(this, R.color.status_gray))
        statusText.text = getString(R.string.status_unknown)
        toggleButton.text = getString(R.string.btn_turn_on)
        backendText.text = "Selected router: ${settings.selectedRouter.displayName}"
        footerStatus.text = "Selected router: ${settings.selectedRouter.displayName}"
    }

    private fun loadSelectedRouterProfile() {
        val routerProfile = settings.selectedRouter
        loadingRouterProfile = true
        try {
            nameEdit.setText(routerProfile.name)
            hostEdit.setText(routerProfile.host)
            portEdit.setText(routerProfile.port.toString())
            loginEdit.setText(routerProfile.login)
            passwordEdit.setText(routerProfile.getPassword())
            backendText.text = "Selected router: ${routerProfile.displayName}"
            reloadBackendSpinner()
        } finally {
            loadingRouterProfile = false
        }
    }

    private fun saveCurrentRouterProfileFields() {
        val routerProfile = settings.selectedRouter
        routerProfile.name = nameEdit.text.toString().trim()
        routerProfile.host = hostEdit.text.toString().trim()
        routerProfile.port = portEdit.text.toString().trim().toIntOrNull()?.coerceIn(1, 65535) ?: 22
        routerProfile.login = loginEdit.text.toString().trim()
        routerProfile.setPassword(passwordEdit.text.toString())
        routerProfile.normalize()
    }

    private fun addRouter() {
        saveCurrentRouterProfileFields()
        val routerProfile = settings.addRouter()
        store.save(settings)
        populateRouterSpinners()
        loadSelectedRouterProfile()
        footerStatus.text = "Added router: ${routerProfile.displayName}"
    }

    private fun deleteSelectedRouter() {
        if (settings.routers.size <= 1) {
            showInfo("At least one router profile is required.")
            return
        }
        val routerProfile = settings.selectedRouter
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage("Delete router profile \"${routerProfile.displayName}\"?")
            .setPositiveButton("Yes") { _, _ ->
                settings.routers.remove(routerProfile)
                settings.selectedRouterId = settings.routers[0].id
                store.save(settings)
                populateRouterSpinners()
                loadSelectedRouterProfile()
                footerStatus.text = "Router profile deleted"
            }
            .setNegativeButton("No", null)
            .show()
    }

    // ----- Backend selection -----

    private fun reloadBackendSpinner() {
        val routerProfile = settings.selectedRouter
        val choices = mutableListOf<BackendChoice>()

        if (routerProfile.backends.isNotEmpty()) {
            routerProfile.backends.forEach {
                choices.add(BackendChoice(profile = it, text = it.displayName))
            }
        } else {
            backendOrder.forEach {
                choices.add(BackendChoice(mode = it, text = getBackendName(it)))
            }
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, choices)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        backendSpinner.adapter = adapter

        val selectedIndex = if (routerProfile.backends.isNotEmpty()) {
            choices.indexOfFirst { it.profile?.id == routerProfile.selectedBackendId }
        } else {
            choices.indexOfFirst { it.mode == settings.preferredBackend }
        }
        backendSpinner.setSelection(if (selectedIndex >= 0) selectedIndex else 0, false)
    }

    private fun selectBackendProfile(backendId: String) {
        val adapter = backendSpinner.adapter ?: return
        for (i in 0 until adapter.count) {
            val choice = adapter.getItem(i) as? BackendChoice ?: continue
            if (choice.profile?.id == backendId) {
                backendSpinner.setSelection(i, false)
                return
            }
        }
    }

    private fun getSelectedBackendChoice(): BackendChoice {
        val choice = backendSpinner.selectedItem as? BackendChoice
        return choice ?: BackendChoice(mode = BackendMode.HY2_194, text = getBackendName(BackendMode.HY2_194))
    }

    private fun saveCurrentBackendSelection() {
        val choice = getSelectedBackendChoice()
        if (choice.profile != null) {
            settings.selectedRouter.selectedBackendId = choice.profile.id
            return
        }
        choice.mode?.let { settings.preferredBackend = it }
    }

    // ----- Router actions -----

    private suspend fun refreshStatus() = runUiTask("Refreshing status...") {
        lastStatus = router.getStatus()
        applyStatus(lastStatus!!)
        footerStatus.text = "Status refreshed"
    }

    private suspend fun scanVpn() = runUiTask("Scanning VPN services...") {
        val scan = router.scanVpn()
        scanOutput.text = formatScanOutput(scan.rawOutput)

        if (scan.backends.isNotEmpty()) {
            val routerProfile = settings.selectedRouter
            val previousBackendId = routerProfile.selectedBackendId
            routerProfile.backends = scan.backends.toMutableList()
            routerProfile.selectedBackendId = scan.backends.firstOrNull { it.running }?.id
                ?: scan.backends.firstOrNull { it.id == previousBackendId }?.id
                ?: scan.backends[0].id
            store.save(settings)
            reloadBackendSpinner()
        }

        footerStatus.text = if (scan.backends.isNotEmpty()) {
            "VPN scan completed, found ${scan.backends.size} backend(s)"
        } else {
            "VPN scan completed, no switchable backend found"
        }

        lastStatus = router.getStatus()
        applyStatus(lastStatus!!)
    }

    private suspend fun enableVpn() = runUiTask("Enabling VPN routing...") {
        val backend = getSelectedBackendChoice()
        if (backend.profile != null) {
            settings.selectedRouter.selectedBackendId = backend.profile.id
            router.enable(backend.profile)
        } else {
            router.enable(backend.mode ?: BackendMode.HY2_194)
        }
        lastStatus = router.getStatus()
        applyStatus(lastStatus!!)
        footerStatus.text = "VPN routing enabled"
    }

    private suspend fun disableVpn() = runUiTask("Disabling VPN routing...") {
        router.disable()
        lastStatus = router.getStatus()
        applyStatus(lastStatus!!)
        footerStatus.text = "VPN routing disabled"
    }

    private suspend fun switchSelectedBackend() = runUiTask("Switching backend...") {
        val backend = getSelectedBackendChoice()
        if (backend.profile != null) {
            settings.selectedRouter.selectedBackendId = backend.profile.id
            store.save(settings)
            router.switchBackend(backend.profile)
            footerStatus.text = "Backend switched to ${backend.profile.name}"
        } else {
            val mode = backend.mode ?: BackendMode.HY2_194
            settings.preferredBackend = mode
            store.save(settings)
            router.switchBackend(mode)
            footerStatus.text = "Backend switched to ${getBackendName(mode)}"
        }
        lastStatus = router.getStatus()
        applyStatus(lastStatus!!)
    }

    private suspend fun runUiTask(busyText: String, action: suspend () -> Unit) {
        setBusy(true, busyText)
        try {
            saveSettings(showMessage = false)
            action()
        } catch (e: Exception) {
            footerStatus.text = "Error"
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.app_name))
                .setMessage(e.message ?: e.toString())
                .setPositiveButton("OK", null)
                .show()
        } finally {
            setBusy(false, footerStatus.text.toString())
        }
    }

    // ----- UI state -----

    private fun applyStatus(status: RouterStatus) {
        val colorId = if (status.routingEnabled) R.color.status_green else R.color.status_red
        statusDot.setTextColor(ContextCompat.getColor(this, colorId))
        statusText.text = getString(if (status.routingEnabled) R.string.status_on else R.string.status_off)
        toggleButton.text = getString(if (status.routingEnabled) R.string.btn_turn_off else R.string.btn_turn_on)

        val backend = status.backend
        val routerProfile = settings.selectedRouter
        val controlMode = if (status.rawOutput.contains("router-control: service", ignoreCase = true)) {
            "service"
        } else {
            "/jffs/scripts/nat-start"
        }
        val selectedProfile = routerProfile.backends.firstOrNull { it.id == routerProfile.selectedBackendId }
            ?: routerProfile.backends.firstOrNull { it.running }
        if (selectedProfile != null) {
            backendText.text =
                "${routerProfile.displayName}: ${selectedProfile.name}, $controlMode -> ${getBackendPorts(selectedProfile)}"
            selectBackendProfile(selectedProfile.id)
        } else {
            backendText.text =
                "${routerProfile.displayName}: ${getBackendName(backend)}, $controlMode -> ${getBackendPort(backend)}"
            val adapter = backendSpinner.adapter
            if (adapter != null) {
                for (i in 0 until adapter.count) {
                    val choice = adapter.getItem(i) as? BackendChoice ?: continue
                    if (choice.mode == backend) {
                        backendSpinner.setSelection(i, false)
                        break
                    }
                }
            }
        }
    }

    private fun setBusy(busy: Boolean, text: String) {
        footerStatus.text = text
        listOf(
            toggleButton, refreshButton, scanButton, applyBackendButton,
            addRouterButton, saveSettingsButton,
            appRouterSpinner, settingsRouterSpinner, backendSpinner,
            nameEdit, hostEdit, portEdit, loginEdit, passwordEdit
        ).forEach { it.isEnabled = !busy }
        deleteRouterButton.isEnabled = !busy && settings.routers.size > 1
    }

    private fun saveSettings(showMessage: Boolean) {
        saveCurrentRouterProfileFields()
        saveCurrentBackendSelection()
        store.save(settings)
        populateRouterSpinners()
        loadSelectedRouterProfile()
        if (showMessage) {
            footerStatus.text = "Settings saved"
        }
    }

    private fun showInfo(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    companion object {
        private fun formatScanOutput(rawOutput: String): String =
            rawOutput
                .split('\r', '\n')
                .filter { it.isNotEmpty() && !it.startsWith("router-control-backend|") }
                .joinToString("\n")

        private fun getBackendName(backend: BackendMode): String = when (backend) {
            BackendMode.HY2_89 -> "HY2-89 / sing-box"
            BackendMode.HY2_194 -> "HY2-194 / sing-box"
            else -> "VLESS-194 / Xray"
        }

        private fun getBackendPort(backend: BackendMode): String =
            if (backend == BackendMode.VLESS_194) "12345" else "12346"

        private fun getBackendPorts(backend: RouterBackendProfile): String =
            backend.ports.ifBlank { backend.serviceName }
    }
}
