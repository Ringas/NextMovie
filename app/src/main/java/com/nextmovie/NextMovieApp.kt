package com.nextmovie

import android.app.Application

class NextMovieApp : Application() {
    override fun onCreate() {
        super.onCreate()
        listOf("play-raw.txt", "detail-raw.txt").forEach { java.io.File(filesDir, it).delete() }
    }
}
