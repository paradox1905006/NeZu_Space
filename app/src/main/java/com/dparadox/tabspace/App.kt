package com.dparadox.tabspace

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DownloadEngine.init(this)
        ThemePrefs.applyNightMode(ThemePrefs.mode(this))
        // Material You — wallpaper colours on Android 12+, can be switched off in Settings.
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                .setPrecondition { _, _ -> ThemePrefs.dynamicColor(this) }
                .build()
        )
    }
}
