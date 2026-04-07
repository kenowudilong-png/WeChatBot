package com.example.wechatbot

import android.app.Application
import com.ven.assists.AssistsCore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AssistsCore.init(this)
    }
}
