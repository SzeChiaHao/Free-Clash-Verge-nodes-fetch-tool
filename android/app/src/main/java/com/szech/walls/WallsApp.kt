package com.szech.walls

import android.app.Application
import com.szech.walls.pipeline.Runner
import com.szech.walls.store.Prefs
import com.szech.walls.store.Repo

class WallsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Repo.init(this)
        Runner.schedule(this, Prefs.autoDaily)
    }

    companion object {
        lateinit var instance: WallsApp
            private set
    }
}
