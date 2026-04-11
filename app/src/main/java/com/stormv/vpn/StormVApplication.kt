package com.stormv.vpn

import android.app.Application
import com.stormv.vpn.util.AppLogger
import com.tencent.mmkv.MMKV

class StormVApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
        AppLogger.init(filesDir)
    }
}
