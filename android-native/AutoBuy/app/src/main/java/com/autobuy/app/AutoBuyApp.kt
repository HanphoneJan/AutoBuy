package com.autobuy.app

import android.app.Application
import com.autobuy.app.core.FileLogger

class AutoBuyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
    }
}
