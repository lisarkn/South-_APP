package net.southplus.client

import android.app.Application

class SouthPlusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
