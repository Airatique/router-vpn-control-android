package com.airat.routervpncontrol

data class RouterStatus(
    val routingEnabled: Boolean,
    val xrayRunning: Boolean,
    val singBoxRunning: Boolean,
    val backend: BackendMode,
    val rawOutput: String
)

data class RouterVpnScanResult(
    val rawOutput: String,
    val backends: List<RouterBackendProfile>
)
