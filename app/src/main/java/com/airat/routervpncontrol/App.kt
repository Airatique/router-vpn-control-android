package com.airat.routervpncontrol

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android ships a stripped-down BouncyCastle; replace it with the full
        // bundled one so sshj can negotiate modern key exchange / signatures.
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        } catch (_: Exception) {
        }
    }
}
