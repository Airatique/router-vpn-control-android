package com.airat.routervpncontrol

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.IOException
import java.util.concurrent.TimeUnit

class RouterClient(private val context: Context, private val settings: AppSettings) {

    suspend fun getStatus(): RouterStatus = withContext(Dispatchers.IO) {
        val output = run(settings.selectedRouter, loadScript("status.sh"))
        parseStatus(output, settings.preferredBackend)
    }

    suspend fun enable(backend: BackendMode) = withContext(Dispatchers.IO) {
        switchBackendCore(backend)
        run(settings.selectedRouter, buildEnableCommand(backend))
    }

    suspend fun disable() = withContext(Dispatchers.IO) {
        run(settings.selectedRouter, loadScript("disable.sh"))
    }

    suspend fun switchBackend(backend: BackendMode) = withContext(Dispatchers.IO) {
        switchBackendCore(backend)
    }

    suspend fun enable(backend: RouterBackendProfile) = withContext(Dispatchers.IO) {
        run(settings.selectedRouter, buildServiceBackendCommand(backend, stopOtherVpn = false))
    }

    suspend fun switchBackend(backend: RouterBackendProfile) = withContext(Dispatchers.IO) {
        run(settings.selectedRouter, buildServiceBackendCommand(backend, stopOtherVpn = true))
    }

    suspend fun scanVpn(): RouterVpnScanResult = withContext(Dispatchers.IO) {
        val output = run(settings.selectedRouter, loadScript("scan.sh"))
        RouterVpnScanResult(output, parseBackends(output))
    }

    private fun switchBackendCore(backend: BackendMode) {
        run(settings.selectedRouter, buildSwitchBackendCommand(backend))
    }

    private fun run(router: RouterProfile, command: String): String {
        router.normalize()
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connectTimeout = 12_000
        ssh.timeout = 30_000
        try {
            ssh.connect(router.host, router.port)

            // Return a fresh char[] on each call: sshj zeroes the array after an
            // attempt, and we feed the same secret to several auth methods.
            val password = router.getPassword()
            val passwordFinder = object : PasswordFinder {
                override fun reqPassword(resource: Resource<*>?): CharArray = password.toCharArray()
                override fun shouldRetry(resource: Resource<*>?): Boolean = false
            }
            // Routers differ in what they advertise: ASUS-Merlin usually accepts
            // the plain "password" method, OpenWrt/Dropbear often only offers
            // "keyboard-interactive". Try both so auth doesn't get "exhausted".
            try {
                ssh.auth(
                    router.login,
                    AuthPassword(passwordFinder),
                    AuthKeyboardInteractive(PasswordResponseProvider(passwordFinder))
                )
            } catch (e: UserAuthException) {
                throw IOException(
                    "SSH authentication failed for ${router.login}@${router.host}:${router.port}. " +
                        "Check the login and password on the VPN Settings tab.",
                    e
                )
            }

            ssh.startSession().use { session ->
                // POSIX shells on the router (ash/dash/bash) choke on Windows
                // CRLF endings: a trailing '\r' turns "echo" into "echo\r" ("not
                // found") and "do" into "do\r" ("unexpected word"). Force LF.
                val normalizedCommand = command.replace("\r\n", "\n").replace("\r", "\n")
                val cmd = session.exec(normalizedCommand)
                val stdout = cmd.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val stderr = cmd.errorStream.bufferedReader(Charsets.UTF_8).readText()
                cmd.join(60, TimeUnit.SECONDS)
                val output = (stdout + stderr).trim()
                val exitStatus = cmd.exitStatus
                if (exitStatus != null && exitStatus != 0) {
                    throw IOException(
                        if (output.isEmpty()) "Router command failed with exit status $exitStatus." else output
                    )
                }
                return output
            }
        } finally {
            try {
                ssh.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun loadScript(name: String): String =
        context.assets.open("scripts/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun buildSwitchBackendCommand(backend: BackendMode): String {
        val natStartCommand = getRouterBackendCommand(backend)
        return buildServiceFallbackCommand(
            "/jffs/scripts/nat-start $natStartCommand",
            if (isSingBoxBackend(backend)) "sing-box" else "xray",
            stopOtherVpn = true
        )
    }

    private fun buildEnableCommand(backend: BackendMode): String =
        buildServiceFallbackCommand(
            "/jffs/scripts/nat-start start",
            if (isSingBoxBackend(backend)) "sing-box" else "xray",
            stopOtherVpn = false
        )

    private fun buildServiceFallbackCommand(
        natStartCommand: String,
        serviceName: String,
        stopOtherVpn: Boolean
    ): String {
        val otherServiceName = if (serviceName == "sing-box") "xray" else "sing-box"
        val stopOther = if (stopOtherVpn) "router_vpn_service stop $otherServiceName" else ":"
        val activeBackend = if (serviceName == "sing-box") "sing-box" else "vless-194"
        return loadScript("service_fallback.sh")
            .replace("{{NAT_START_COMMAND}}", natStartCommand)
            .replace("{{STOP_OTHER}}", stopOther)
            .replace("{{SERVICE_NAME}}", serviceName)
            .replace("{{ACTIVE_BACKEND}}", activeBackend)
    }

    private fun buildServiceBackendCommand(backend: RouterBackendProfile, stopOtherVpn: Boolean): String {
        backend.normalize()
        if (backend.kind.equals("nat-start", ignoreCase = true)) {
            return buildNatStartBackendCommand(backend.outboundTag, backend.name)
        }
        if (backend.kind.equals("sing-box-outbound", ignoreCase = true)) {
            return buildSingBoxOutboundCommand(backend.configPath, backend.outboundTag, backend.name)
        }
        return buildServiceOnlyCommand(backend.serviceName, backend.name, stopOtherVpn)
    }

    private fun buildServiceOnlyCommand(serviceName: String, backendName: String, stopOtherVpn: Boolean): String {
        val stopOther = if (stopOtherVpn) {
            """
            for other in sing-box xray hysteria naive wg openvpn wireguard-go; do
              [ "${'$'}other" = "$serviceName" ] && continue
              router_vpn_service stop "${'$'}other" >/dev/null 2>&1 || true
            done
            """.trimIndent()
        } else {
            ":"
        }
        return loadScript("service_only.sh")
            .replace("{{STOP_OTHER}}", stopOther)
            .replace("{{SERVICE_NAME}}", serviceName)
            .replace("{{BACKEND_NAME}}", backendName)
    }

    private fun buildNatStartBackendCommand(command: String, backendName: String): String =
        loadScript("natstart_backend.sh")
            .replace("{{QUOTED_CMD}}", shQuote(command))
            .replace("{{QUOTED_LABEL}}", shQuote(backendName))

    private fun buildSingBoxOutboundCommand(configPath: String, outboundTag: String, backendName: String): String =
        loadScript("singbox_outbound.sh")
            .replace("{{CFG}}", shQuote(configPath.ifBlank { "/opt/etc/sing-box/config.json" }))
            .replace("{{TAG}}", shQuote(outboundTag))
            .replace("{{LABEL}}", shQuote(backendName))

    companion object {
        fun parseStatus(output: String, preferredBackend: BackendMode): RouterStatus {
            val normalized = output.lowercase()
            val xray = normalized.contains("xray: on")
            val singBox = normalized.contains("sing-box: on") || normalized.contains("singbox: on")
            val otherVpn = normalized.contains("hysteria: on") ||
                normalized.contains("naive: on") ||
                normalized.contains("wg: on") ||
                normalized.contains("openvpn: on") ||
                normalized.contains("wireguard-go: on")
            val routing = normalized.contains("routing: on") ||
                (normalized.contains("router-control: service-fallback") && (xray || singBox || otherVpn)) ||
                (normalized.contains("router-control: service-backend") && (xray || singBox || otherVpn))

            val backend = when {
                normalized.contains("active-backend: hy2-89") -> BackendMode.HY2_89
                normalized.contains("active-backend: hy2-194") -> BackendMode.HY2_194
                normalized.contains("active-backend: vless-194") -> BackendMode.VLESS_194
                normalized.contains("active-backend: sing-box") -> BackendMode.HY2_194
                normalized.contains("backend: hysteria2") -> BackendMode.HY2_194
                singBox && (preferredBackend == BackendMode.HY2_89 || preferredBackend == BackendMode.HY2_194) -> preferredBackend
                singBox && normalized.contains(":12346") -> BackendMode.HY2_194
                singBox -> BackendMode.HY2_194
                xray -> BackendMode.VLESS_194
                else -> BackendMode.VLESS_194
            }

            return RouterStatus(routing, xray, singBox, backend, output)
        }

        fun parseBackends(output: String): List<RouterBackendProfile> {
            val backends = mutableListOf<RouterBackendProfile>()
            for (line in output.split('\r', '\n')) {
                if (line.isEmpty() || !line.startsWith("router-control-backend|")) {
                    continue
                }
                val parts = line.split('|')
                if (parts.size < 6) {
                    continue
                }
                val backendId = parts[1].trim()
                val serviceName = parts[3].trim()
                if (backendId.isBlank() || serviceName.isBlank() || backends.any { it.id == backendId }) {
                    continue
                }
                backends.add(RouterBackendProfile().apply {
                    id = backendId
                    this.serviceName = serviceName
                    name = parts[2].trim()
                    ports = parts[4].trim()
                    running = parts[5].trim().equals("on", ignoreCase = true)
                    kind = if (parts.size > 6) parts[6].trim() else "service"
                    configPath = if (parts.size > 7) parts[7].trim() else ""
                    outboundTag = if (parts.size > 8) parts[8].trim() else ""
                })
            }
            return backends
        }

        fun getRouterBackendCommand(backend: BackendMode): String = when (backend) {
            BackendMode.HY2_89 -> "use-hy2-89"
            BackendMode.HY2_194 -> "use-hy2-194"
            else -> "use-vless-194"
        }

        fun isSingBoxBackend(backend: BackendMode): Boolean =
            backend == BackendMode.HY2_89 || backend == BackendMode.HY2_194

        fun shQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
