package it.squarciagola

import android.app.Application

class SquarciagolaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
    }
}
