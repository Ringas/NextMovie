package com.nextmovie

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nextmovie.core.BootWarm

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.nextmovie.WARM"
        ) return
        BootWarm.run(context, "boot")
    }
}
