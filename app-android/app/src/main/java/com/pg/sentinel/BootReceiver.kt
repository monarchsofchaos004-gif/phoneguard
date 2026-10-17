package com.pg.sentinel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 開機自啟守門常駐服務。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            GuardForegroundService.start(context)
        }
    }
}
