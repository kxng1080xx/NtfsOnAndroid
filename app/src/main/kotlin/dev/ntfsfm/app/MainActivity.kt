package dev.ntfsfm.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.ntfsfm.app.data.AppSettings
import dev.ntfsfm.app.data.ThemeMode
import dev.ntfsfm.app.ui.design.AppBackground
import dev.ntfsfm.app.ui.nav.NtfsFileManagerNavHost
import dev.ntfsfm.app.ui.theme.LocalToggleDarkTheme
import dev.ntfsfm.app.ui.theme.NtfsFileManagerTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var appSettings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by appSettings.settings.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status/nav bar icons follow our theme, not the system's.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            CompositionLocalProvider(
                LocalToggleDarkTheme provides { appSettings.setThemeMode(if (dark) ThemeMode.LIGHT else ThemeMode.DARK) },
            ) {
                NtfsFileManagerTheme(darkTheme = dark) {
                    AppBackground {
                        NtfsFileManagerNavHost()
                    }
                }
            }
        }
    }
}
