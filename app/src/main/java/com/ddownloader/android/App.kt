package com.ddownloader.android

import android.app.Application
import com.ddownloader.android.core.AppLog
import com.ddownloader.android.data.ServiceLocator

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        AppLog.i("App", "started v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }
}
