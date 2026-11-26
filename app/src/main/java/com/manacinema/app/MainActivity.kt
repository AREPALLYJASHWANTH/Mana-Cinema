package com.manacinema.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.manacinema.app.ui.OnboardingScreen
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.manacinema.app.localization.LocalAppStrings
import com.manacinema.app.localization.LocalizationManager
import com.manacinema.app.ui.MainAppContainer
import com.manacinema.app.ui.MovieViewModel
import com.manacinema.app.ui.theme.MovieHuntTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MovieViewModel by viewModels()
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var vpnCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // ==========================================
        // 1. APP START HOTE HI VPN CHECK
        // ==========================================
        if (com.manacinema.app.security.VpnDetector.isVpnActive(applicationContext)) {
            super.onCreate(savedInstanceState)
            com.manacinema.app.security.VpnDetector.blockAndExit(this)
            return
        }

        super.onCreate(savedInstanceState)

        // Keep the cinematic background continuous behind the compact home pill.
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = false

        // ==========================================
        // 2. BACKGROUND VPN MONITORING
        // ==========================================
        vpnCallback = com.manacinema.app.security.VpnDetector.startMonitoring(this)

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        fun hasValidatedInternet(): Boolean {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        val initialNetworkOnline = hasValidatedInternet()
        viewModel.setOfflineState(!initialNetworkOnline)

        var downloadsResumed = false
        fun tryResumeDownloadsOnce() {
            if (downloadsResumed) return
            downloadsResumed = true
            viewModel.resumeInterruptedDownloadsIfAny(applicationContext)
        }

        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                val online = hasValidatedInternet()
                viewModel.setOfflineState(!online)
                if (online) tryResumeDownloadsOnce()
            }
            override fun onLost(network: android.net.Network) {
                viewModel.setOfflineState(!hasValidatedInternet())
            }
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                capabilities: android.net.NetworkCapabilities
            ) {
                val online = capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                viewModel.setOfflineState(!online)
                if (online) tryResumeDownloadsOnce()
            }
        }
        networkCallback = callback
        val builder = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        cm.registerNetworkCallback(builder.build(), callback)

        if (initialNetworkOnline) tryResumeDownloadsOnce()

        setContent {
            // Dynamic thematic settings collection
            val sharedPrefs = remember { this@MainActivity.getSharedPreferences("infinity_movie_prefs", Context.MODE_PRIVATE) }
            var activeThemeName by remember { mutableStateOf(sharedPrefs.getString("active_theme", "classic-red") ?: "classic-red") }
            var isThemeDark by remember { mutableStateOf(sharedPrefs.getBoolean("is_theme_dark", true)) }

            // Listen for any theme preference changes
            DisposableEffect(sharedPrefs) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == "active_theme") {
                        activeThemeName = sharedPrefs.getString("active_theme", "classic-red") ?: "classic-red"
                    } else if (key == "is_theme_dark") {
                        isThemeDark = sharedPrefs.getBoolean("is_theme_dark", true)
                    }
                }
                sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose {
                    sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
                }
            }

            val appLanguage by viewModel.currentLanguage.collectAsState()
            val appStrings = remember(appLanguage) {
                LocalizationManager.getStringResources(appLanguage)
            }

            CompositionLocalProvider(LocalAppStrings provides appStrings) {
            MovieHuntTheme(themeName = activeThemeName, isDark = isThemeDark) {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    var hasCompletedOnboarding by remember {
                        mutableStateOf(sharedPrefs.getBoolean("has_completed_onboarding", false))
                    }

                    if (!hasCompletedOnboarding) {
                        OnboardingScreen(
                            onFinished = {
                                sharedPrefs.edit().putBoolean("has_completed_onboarding", true).apply()
                                hasCompletedOnboarding = true
                                com.manacinema.app.notifications.OneSignalManager.requestPermissionIfNeeded(this@MainActivity)
                            }
                        )
                    } else {
                        com.manacinema.app.notifications.OneSignalManager.requestPermissionIfNeeded(this@MainActivity)
                        MainAppContainer(
                            viewModel = viewModel,
                            onLogout = {
                                viewModel.handleLogout()
                            }
                        )
                    }
                }
            }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 3. VPN ON KIYA GAYA APP KE BAHAR? RESUME PAR BHI BLOCK
        if (com.manacinema.app.security.VpnDetector.isVpnActive(applicationContext)) {
            com.manacinema.app.security.VpnDetector.blockAndExit(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        com.manacinema.app.security.VpnDetector.stopMonitoring(applicationContext, vpnCallback)
        vpnCallback = null
        networkCallback?.let {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.unregisterNetworkCallback(it)
        }
    }
}
