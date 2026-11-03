package io.github.purplepalm.custommaps

import android.app.Application

class CustomMapsApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this).also { it.start() }
    }
}
