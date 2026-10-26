package com.ddownloader.android

import com.ddownloader.android.core.LogL10n
import android.app.Application
import com.ddownloader.android.core.AppLog
import com.ddownloader.android.data.ServiceLocator

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        AppLog.i("App", LogL10n.of("started v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", "iniciado v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
    }
}
