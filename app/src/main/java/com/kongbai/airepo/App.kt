package com.kongbai.airepo

import android.app.Application
import com.kongbai.airepo.core.CrashHandler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class App : Application() {

    @Inject lateinit var crash: CrashHandler

    override fun onCreate() {
        super.onCreate()
        crash.install()
    }
}
