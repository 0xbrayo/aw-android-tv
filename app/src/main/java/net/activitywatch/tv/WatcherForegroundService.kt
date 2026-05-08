package net.activitywatch.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.activitywatch.tv.data.AWDatabase
import net.activitywatch.tv.data.Bucket
import net.activitywatch.tv.data.Event
import net.activitywatch.tv.data.HeartbeatService
import org.json.JSONObject

private const val TAG = "WatcherService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "aw_watcher_channel"

class WatcherForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var heartbeatSvc: HeartbeatService

    private val bucketId = "aw-watcher-android-tv-media"
    private var activeController: MediaController? = null
    private var trackedData: String? = null
    private var isCurrentlyPlaying = false
    private var heartbeatJob: Job? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refreshFromController()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state?.state != PlaybackState.STATE_PLAYING) {
                heartbeatJob?.cancel()
                heartbeatJob = null
                trackedData?.let { scope.launch(Dispatchers.IO) { sendHeartbeat(it) } }
            }
            refreshFromController()
        }
        override fun onSessionDestroyed() = bindToController(null)
    }

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        pickBestController(controllers.orEmpty())
    }

    override fun onCreate() {
        super.onCreate()
        val db = AWDatabase.getDatabase(this)
        heartbeatSvc = HeartbeatService(db)
        mediaSessionManager = getSystemService(MediaSessionManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        scope.launch(Dispatchers.IO) {
            heartbeatSvc.ensureBucketExists(
                Bucket(
                    id = bucketId,
                    type = "currentwindow",
                    client = "aw-android-tv",
                    hostname = Build.MODEL,
                    created = System.currentTimeMillis(),
                    name = "Android TV Media",
                )
            )
        }

        scope.launch {
            MediaSessionListenerService.isConnected.collectLatest { connected ->
                if (connected) startMonitoring() else stopMonitoring()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        trackedData?.let { runBlocking(Dispatchers.IO) { sendHeartbeat(it) } }
        stopMonitoring()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitoring() {
        val cn = ComponentName(this, MediaSessionListenerService::class.java)
        try {
            pickBestController(mediaSessionManager.getActiveSessions(cn))
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionListener, cn, mainHandler)
        } catch (_: SecurityException) {
            Log.w(TAG, "Notification listener permission not granted")
        }
    }

    private fun stopMonitoring() {
        try { mediaSessionManager.removeOnActiveSessionsChangedListener(sessionListener) } catch (_: Exception) {}
        bindToController(null)
    }

    private fun pickBestController(controllers: List<MediaController>) {
        val best = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()
        bindToController(best)
    }

    private fun bindToController(controller: MediaController?) {
        if (activeController?.sessionToken == controller?.sessionToken) return
        activeController?.unregisterCallback(controllerCallback)
        activeController = controller
        controller?.registerCallback(controllerCallback, mainHandler)
        refreshFromController()
    }

    private fun refreshFromController() {
        val ctrl = activeController
        val newPlaying = ctrl?.playbackState?.state == PlaybackState.STATE_PLAYING

        val newData = ctrl?.let {
            val meta = it.metadata
            val appLabel = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(it.packageName, 0)
                ).toString()
            } catch (_: Exception) { it.packageName }

            JSONObject().apply {
                meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.let { t -> put("title", t) }
                (meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))
                    ?.let { a -> put("artist", a) }
                meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.let { al -> put("album", al) }
                put("app", appLabel)
            }.toString()
        }

        if (newData != trackedData) {
            trackedData?.let { scope.launch(Dispatchers.IO) { sendHeartbeat(it) } }
            trackedData = newData
        }
        isCurrentlyPlaying = newPlaying
        restartHeartbeatJob()
    }

    private suspend fun sendHeartbeat(data: String) {
        heartbeatSvc.insertHeartbeat(
            bucketId = bucketId,
            event = Event(timestamp = System.currentTimeMillis(), duration = 0L, bucketId = bucketId, data = data),
            pulseTimeMillis = 90_000L,
        )
    }

    private fun restartHeartbeatJob() {
        heartbeatJob?.cancel()
        val data = trackedData ?: return
        if (!isCurrentlyPlaying) return
        heartbeatJob = scope.launch(Dispatchers.IO) {
            sendHeartbeat(data)
            while (true) {
                delay(60_000L)
                sendHeartbeat(data)
            }
        }
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ActivityWatch",
            NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ActivityWatch")
            .setContentText("Tracking media activity")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
