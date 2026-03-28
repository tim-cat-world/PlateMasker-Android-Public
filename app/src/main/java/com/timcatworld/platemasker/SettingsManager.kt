package com.timcatworld.platemasker

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun loadSettings(): AppSettings {
        val languageCode = prefs.getString("language", AppLanguage.JAPANESE.code) ?: AppLanguage.JAPANESE.code
        val language = AppLanguage.entries.find { it.code == languageCode } ?: AppLanguage.JAPANESE

        val regionLabel = prefs.getString("region", PlateRegion.JAPAN.label) ?: PlateRegion.JAPAN.label
        val region = PlateRegion.entries.find { it.label == regionLabel } ?: PlateRegion.JAPAN

        return AppSettings(
            language = language,
            region = region,
            saveFolderPath = prefs.getString("save_folder_path", "") ?: "",
            autoSaveWithIncrementalName = prefs.getBoolean("auto_save", true),
            askFileNameBeforeShare = prefs.getBoolean("ask_filename", false),
            touchHitRadius = prefs.getFloat("hit_radius", 40f),
            touchHitOffset = prefs.getFloat("hit_offset", 0f)
        )
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit().apply {
            putString("language", settings.language.code)
            putString("region", settings.region.label)
            putString("save_folder_path", settings.saveFolderPath)
            putBoolean("auto_save", settings.autoSaveWithIncrementalName)
            putBoolean("ask_filename", settings.askFileNameBeforeShare)
            putFloat("hit_radius", settings.touchHitRadius)
            putFloat("hit_offset", settings.touchHitOffset)
            apply()
        }
    }
}
