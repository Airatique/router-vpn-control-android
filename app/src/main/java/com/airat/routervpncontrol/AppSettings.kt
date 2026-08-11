package com.airat.routervpncontrol

import android.content.Context
import com.google.gson.GsonBuilder
import java.io.File
import java.util.UUID

private fun newId(): String = UUID.randomUUID().toString().replace("-", "")

class RouterBackendProfile {
    var id: String = newId()
    var name: String = "VPN backend"
    var serviceName: String = ""
    var kind: String = "service"
    var configPath: String = ""
    var outboundTag: String = ""
    var ports: String = ""
    var running: Boolean = false

    val displayName: String
        get() {
            val state = if (running) "running" else "available"
            val portsPart = if (ports.isBlank()) "" else ", ports $ports"
            return "$name ($state$portsPart)"
        }

    fun normalize() {
        if (id.isBlank()) {
            id = newId()
        }
        serviceName = serviceName.trim()
        kind = kind.trim().ifBlank { "service" }
        configPath = configPath.trim()
        outboundTag = outboundTag.trim()
        name = name.trim().ifBlank { serviceName }
        ports = ports.trim()
    }

    fun isTechnicalSingBoxEntry(): Boolean {
        if (!kind.equals("sing-box-outbound", ignoreCase = true)) {
            return false
        }
        val tag = outboundTag.trim()
        return tag.startsWith("mixed-", ignoreCase = true) ||
            tag.startsWith("redirect-", ignoreCase = true) ||
            tag.equals("direct", ignoreCase = true) ||
            tag.equals("auto", ignoreCase = true) ||
            tag.equals("selector", ignoreCase = true) ||
            tag.equals("urltest", ignoreCase = true) ||
            tag.equals("dns", ignoreCase = true) ||
            tag.equals("dns-out", ignoreCase = true)
    }
}

enum class SshAuthMethod {
    PASSWORD,
    KEY;

    companion object {
        fun fromStored(value: String?): SshAuthMethod =
            if (value.equals("key", ignoreCase = true)) KEY else PASSWORD

        fun toStored(method: SshAuthMethod): String =
            if (method == KEY) "key" else "password"
    }
}

class RouterProfile {
    var id: String = newId()
    var name: String = "Router"
    var host: String = ""
    var port: Int = 22
    var login: String = ""
    /** "password" (default) or "key". Missing field in old settings = password. */
    var authMethod: String = SshAuthMethod.toStored(SshAuthMethod.PASSWORD)
    var protectedPassword: String = ""
    var protectedPrivateKey: String = ""
    var protectedPrivateKeyPassphrase: String = ""
    var backends: MutableList<RouterBackendProfile> = mutableListOf()
    var selectedBackendId: String = ""

    val displayName: String
        get() = if (host.isBlank()) name else "$name ($host)"

    fun sshAuthMethod(): SshAuthMethod = SshAuthMethod.fromStored(authMethod)

    fun setSshAuthMethod(method: SshAuthMethod) {
        authMethod = SshAuthMethod.toStored(method)
    }

    fun normalize() {
        if (id.isBlank()) {
            id = newId()
        }

        // Users often paste an address the way they see it in a browser/panel
        // ("http://1.2.3.4", "ssh://root@1.2.3.4:2222/", "1.2.3.4:2222").
        // sshj needs a bare hostname, so strip scheme/userinfo/path and lift an
        // embedded port into the port field. Otherwise the connection fails and
        // the whole Scan/Status flow errors out.
        var cleanedHost = host.trim()
            .replace(Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://"), "")
            .substringBefore('/')
            .substringBefore('?')
            .substringAfterLast('@')
        if (cleanedHost.count { it == ':' } == 1 && !cleanedHost.startsWith("[")) {
            val (h, p) = cleanedHost.split(':', limit = 2)
            val parsedPort = p.trim().toIntOrNull()
            if (parsedPort != null && parsedPort in 1..65535) {
                cleanedHost = h
                port = parsedPort
            }
        }
        host = cleanedHost.trim()
        name = name.trim().ifBlank { host.ifBlank { "Router" } }
        if (port <= 0) {
            port = 22
        }
        login = login.trim()
        authMethod = SshAuthMethod.toStored(sshAuthMethod())

        backends = backends.filter { !it.isTechnicalSingBoxEntry() }.toMutableList()
        backends.forEach { it.normalize() }

        if (backends.isNotEmpty() && (selectedBackendId.isBlank() || backends.none { it.id == selectedBackendId })) {
            selectedBackendId = backends[0].id
        }
    }

    fun getPassword(): String = CryptoBox.unprotect(protectedPassword)

    fun setPassword(password: String) {
        protectedPassword = if (password.isEmpty()) "" else CryptoBox.protect(password)
    }

    fun getPrivateKey(): String = CryptoBox.unprotect(protectedPrivateKey)

    fun setPrivateKey(privateKey: String) {
        protectedPrivateKey = if (privateKey.isBlank()) "" else CryptoBox.protect(privateKey)
    }

    fun getPrivateKeyPassphrase(): String = CryptoBox.unprotect(protectedPrivateKeyPassphrase)

    fun setPrivateKeyPassphrase(passphrase: String) {
        protectedPrivateKeyPassphrase =
            if (passphrase.isEmpty()) "" else CryptoBox.protect(passphrase)
    }
}

class AppSettings {
    var routers: MutableList<RouterProfile> = mutableListOf()
    var selectedRouterId: String = ""
    var preferredBackend: BackendMode = BackendMode.HY2_194

    val selectedRouter: RouterProfile
        get() {
            normalizeRouters()
            return routers.first { it.id == selectedRouterId }
        }

    fun addRouter(): RouterProfile {
        val router = RouterProfile().apply {
            name = "Router ${routers.size + 1}"
        }
        routers.add(router)
        selectedRouterId = router.id
        return router
    }

    fun normalizeRouters() {
        if (routers.isEmpty()) {
            routers.add(RouterProfile().apply { name = "Router 1" })
        }
        routers.forEach { it.normalize() }
        if (selectedRouterId.isBlank() || routers.none { it.id == selectedRouterId }) {
            selectedRouterId = routers[0].id
        }
    }
}

class SettingsStore(context: Context) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file = File(context.filesDir, "settings.json")

    fun load(): AppSettings {
        val settings = if (file.exists()) {
            try {
                gson.fromJson(file.readText(), AppSettings::class.java) ?: AppSettings()
            } catch (_: Exception) {
                AppSettings()
            }
        } else {
            AppSettings()
        }
        settings.normalizeRouters()
        return settings
    }

    fun save(settings: AppSettings) {
        settings.normalizeRouters()
        file.writeText(gson.toJson(settings))
    }
}
