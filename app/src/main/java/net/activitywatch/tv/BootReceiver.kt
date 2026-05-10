package net.activitywatch.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> startServices(context)
        }
    }

    private fun startServices(context: Context) {
        context.startForegroundService(Intent(context, WatcherForegroundService::class.java))
        if (UsageStatsWatcherService.hasPermission(context)) {
            context.startForegroundService(Intent(context, UsageStatsWatcherService::class.java))
        }
        if (JellyfinConfig(context).isConfigured) {
            context.startForegroundService(Intent(context, JellyfinPollerService::class.java))
        }
    }
}
