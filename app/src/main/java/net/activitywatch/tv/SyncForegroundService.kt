package net.activitywatch.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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
import net.activitywatch.tv.sync.SyncEventBuffer
import net.activitywatch.tv.sync.SyncServer

private const val TAG = "SyncService"
private const val NOTIFICATION_ID = 1003
private const val CHANNEL_ID = "aw_sync_channel"

class SyncForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var server: SyncServer
    private lateinit var buffer: SyncEventBuffer
    private lateinit var nsdManager: NsdManager
    private var nsdListener: NsdManager.RegistrationListener? = null

    override fun onCreate() {
        super.onCreate()
        val db = AWDatabase.getDatabase(this)
        buffer = SyncEventBuffer()
        server = SyncServer(db, buffer)

        startForegroundCompat()

        try {
            server.start()
            Log.i(TAG, "NanoHTTPD started on :5606")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sync server", e)
        }

        registerNsd()

        scope.launch { pollLoop(db) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        unregisterNsd()
        server.stopServer()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun pollLoop(db: AWDatabase) {
        val lastSeenId = mutableMapOf<String, Long>()
        while (true) {
            try {
                val buckets = db.bucketDao().getAllBuckets()
                for (bucket in buckets) {
                    val afterId = lastSeenId[bucket.id] ?: 0L
                    val newEvents = db.eventDao().getEventsAfterIdForBucket(bucket.id, afterId)
                    for (event in newEvents) {
                        buffer.append(bucket.id, event)
                        if (event.id > (lastSeenId[bucket.id] ?: 0L)) lastSeenId[bucket.id] = event.id
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Poll error: ${e.message}")
            }
            delay(2_000)
        }
    }

    private fun registerNsd() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "ActivityWatch TV"
            serviceType = "_activitywatch-tv._tcp"
            port = 5606
            setAttribute("device", Build.MODEL)
            setAttribute("version", "1.0")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "mDNS registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "mDNS registration failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "mDNS unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "mDNS unregistration failed: $code")
            }
        }
        nsdManager = getSystemService(NsdManager::class.java)
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        nsdListener = listener
    }

    private fun unregisterNsd() {
        nsdListener?.let {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
            nsdListener = null
        }
    }

    private fun startForegroundCompat() {
        val channel = NotificationChannel(
            CHANNEL_ID, "ActivityWatch Sync", NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ActivityWatch Sync")
            .setContentText("Serving DB on port 5606")
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
