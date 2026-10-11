package dev.ntfsfm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import dev.ntfsfm.app.ui.nav.NtfsFileManagerNavHost
import dev.ntfsfm.app.ui.theme.NtfsFileManagerTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NtfsFileManagerTheme {
                Surface(Modifier.fillMaxSize()) {
                    NtfsFileManagerNavHost()
                }
            }
        }
    }
}
