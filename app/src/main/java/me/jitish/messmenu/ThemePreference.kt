package me.jitish.messmenu

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ======== THEME MODE ENUM (LIGHT/DARK ONLY) ========
enum class ThemeMode {
    LIGHT,
    DARK
}

// ======== DATASTORE SETUP ========
private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "theme_prefs"
)

// ======== THEME PREFERENCE CLASS ========
class ThemePreference(private val context: Context) {

    companion object {
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
    }

    /**
     * Reads the theme mode from DataStore.
     *
     * Default is LIGHT if nothing is stored yet.
     */
    val themeModeFlow: Flow<ThemeMode> = context.themeDataStore.data.map { preferences ->
        val storedValue = preferences[THEME_MODE_KEY]

        // Be tolerant to corrupt/unknown values.
        runCatching { storedValue?.let(ThemeMode::valueOf) }.getOrNull() ?: ThemeMode.LIGHT
    }

    /**
     * Save the user's theme choice to DataStore.
     */
    suspend fun setThemeMode(mode: ThemeMode) {
        context.themeDataStore.edit { preferences ->
            preferences[THEME_MODE_KEY] = mode.name
        }
    }

    /**
     * Flip between LIGHT and DARK.
     */
    fun toggled(current: ThemeMode): ThemeMode {
        return when (current) {
            ThemeMode.DARK -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
        }
    }
}
