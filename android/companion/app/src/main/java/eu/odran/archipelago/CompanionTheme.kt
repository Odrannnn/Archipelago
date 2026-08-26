package eu.odran.archipelago

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity

internal enum class CompanionThemeMode(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark"),
}

internal object CompanionThemePreferences {
    private const val PREFERENCES = "companion_appearance"
    private const val KEY_MODE = "theme_mode"

    fun load(context: Context): CompanionThemeMode {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null)
        return CompanionThemeMode.entries.firstOrNull { it.name == stored }
            ?: CompanionThemeMode.SYSTEM
    }

    fun save(context: Context, mode: CompanionThemeMode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }

    fun usesDarkPalette(context: Context, mode: CompanionThemeMode = load(context)): Boolean =
        when (mode) {
            CompanionThemeMode.SYSTEM ->
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
            CompanionThemeMode.LIGHT -> false
            CompanionThemeMode.DARK -> true
        }
}

/** Applies the selected native widget theme before each companion activity is created. */
abstract class CompanionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = CompanionThemePreferences.usesDarkPalette(this)
        CompanionUi.configure(dark)
        setTheme(if (dark) R.style.AppTheme_Dark else R.style.AppTheme_Light)
        super.onCreate(savedInstanceState)
    }
}
