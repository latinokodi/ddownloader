package pro.tordownloader.android

import android.app.Application
import pro.tordownloader.android.core.AppLog
import pro.tordownloader.android.data.ServiceLocator

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        AppLog.i("App", "started v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }
}
