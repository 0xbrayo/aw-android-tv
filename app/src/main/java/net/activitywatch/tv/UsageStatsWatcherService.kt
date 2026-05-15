package net.activitywatch.tv

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.activitywatch.tv.data.AWDatabase
import net.activitywatch.tv.data.Bucket
import net.activitywatch.tv.data.Event
import net.activitywatch.tv.data.HeartbeatService
import org.json.JSONObject

private const val TAG = "UsageStatsWatcher"
private const val NOTIFICATION_ID = 1002
private const val CHANNEL_ID = "aw_watcher_channel"
private const val POLL_MS = 5_000L
private const val PULSE_MS = 20_000L

class UsageStatsWatcherService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var heartbeatSvc: HeartbeatService

    private val bucketId = "aw-watcher-android-tv-window"
    private var currentApp: String? = null
    private var lastQueryTime: Long = 0L

    companion object {
        fun hasPermission(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (!hasPermission(this)) {
            stopSelf()
            return
        }

        val db = AWDatabase.getDatabase(this)
        heartbeatSvc = HeartbeatService(db)
        usageStatsManager = getSystemService(UsageStatsManager::class.java)

        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        scope.launch {
            heartbeatSvc.ensureBucketExists(
                Bucket(
                    id = bucketId,
                    type = "currentwindow",
                    client = "aw-android-tv",
                    hostname = Build.MODEL,
                    created = System.currentTimeMillis(),
                    name = "Android TV Window",
                )
            )
            // Seed current foreground app by looking back one hour
            lastQueryTime = System.currentTimeMillis() - 3_600_000L
            pollLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun pollLoop() {
        while (true) {
            tick()
            delay(POLL_MS)
        }
    }

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(lastQueryTime, now)
        lastQueryTime = now

        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                currentApp = ev.packageName
            }
        }

        val pkg = currentApp ?: return
        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }

        val data = JSONObject().apply {
            put("app", pkg)
            put("appLabel", appLabel)
        }.toString()

        heartbeatSvc.insertHeartbeat(
            bucketId = bucketId,
            event = Event(timestamp = now, duration = 0L, bucketId = bucketId, data = data),
            pulseTimeMillis = PULSE_MS,
        )
        Log.d(TAG, "Window → $appLabel ($pkg)")
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(CHANNEL_ID, "ActivityWatch", NotificationManager.IMPORTANCE_MIN)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ActivityWatch")
            .setContentText("Tracking active app")
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }
}
