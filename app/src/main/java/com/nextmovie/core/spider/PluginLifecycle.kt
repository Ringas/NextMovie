package com.nextmovie.core.spider

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A plugin loaded after onResume still needs to learn which Activity can own its dialogs. */
internal object PluginLifecycle {
    fun snapshot(context: Context): List<Application.ActivityLifecycleCallbacks> = runCatching {
        val app = context.applicationContext as Application
        val field = Application::class.java.getDeclaredField("mActivityLifecycleCallbacks").apply { isAccessible = true }
        val callbacks = field.get(app) as ArrayList<*>
        synchronized(callbacks) { callbacks.filterIsInstance<Application.ActivityLifecycleCallbacks>() }
    }.getOrDefault(emptyList())

    fun catchUp(context: Context, before: List<Application.ActivityLifecycleCallbacks>) {
        val added = snapshot(context).filter { current -> before.none { it === current } }
        if (added.isEmpty()) return
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                PluginDialogs.current()?.let { host ->
                    added.forEach { callback ->
                        runCatching {
                            callback.onActivityCreated(host, null)
                            callback.onActivityStarted(host)
                            callback.onActivityResumed(host)
                        }
                    }
                }
            } finally { done.countDown() }
        }
        done.await(3, TimeUnit.SECONDS)
    }
}
