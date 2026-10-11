package dev.ntfsfm.app.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val showHiddenFiles: Boolean = false,
    val gridViewByDefault: Boolean = false,
)

/** User preferences, persisted in SharedPreferences and observable app-wide. */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        Settings(
            themeMode = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null)!!) }.getOrDefault(ThemeMode.SYSTEM),
            showHiddenFiles = prefs.getBoolean(KEY_HIDDEN, false),
            gridViewByDefault = prefs.getBoolean(KEY_GRID, false),
        ),
    )
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _settings.value = _settings.value.copy(themeMode = mode)
    }

    fun setShowHiddenFiles(show: Boolean) {
        prefs.edit { putBoolean(KEY_HIDDEN, show) }
        _settings.value = _settings.value.copy(showHiddenFiles = show)
    }

    fun setGridViewByDefault(grid: Boolean) {
        prefs.edit { putBoolean(KEY_GRID, grid) }
        _settings.value = _settings.value.copy(gridViewByDefault = grid)
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_HIDDEN = "show_hidden"
        const val KEY_GRID = "grid_view"
    }
}
