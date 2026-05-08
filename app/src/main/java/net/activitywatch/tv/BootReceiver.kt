package net.activitywatch.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, WatcherForegroundService::class.java))
            if (UsageStatsWatcherService.hasPermission(context)) {
                context.startForegroundService(Intent(context, UsageStatsWatcherService::class.java))
            }
        }
    }
}
