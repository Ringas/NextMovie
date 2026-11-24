package com.nextmovie.core.spider

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Compatibility state read by some CatVod plugins. Verified query:
 * SELECT url FROM Config WHERE type = 0 ORDER BY time DESC LIMIT 1
 * This stores the real configuration selected by the user, not a bundled source.
 */
object PluginHostState {
    @Synchronized
    fun publishConfig(context: Context, origin: String) {
        if (origin.isBlank()) return
        val path = context.getDatabasePath("tv")
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS Config (id INTEGER PRIMARY KEY, url TEXT NOT NULL, type INTEGER NOT NULL, time INTEGER NOT NULL)")
            db.execSQL("INSERT OR REPLACE INTO Config(id,url,type,time) VALUES(1,?,0,?)", arrayOf(origin, System.currentTimeMillis()))
        }
    }
}
