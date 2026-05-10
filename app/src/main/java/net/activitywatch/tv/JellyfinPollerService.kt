package net.activitywatch.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import kotlinx.coroutines.withContext
import net.activitywatch.tv.data.AWDatabase
import net.activitywatch.tv.data.Bucket
import net.activitywatch.tv.data.Event
import net.activitywatch.tv.data.HeartbeatService
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "JellyfinPoller"
private const val NOTIFICATION_ID = 1003
private const val CHANNEL_ID = "aw_watcher_channel"
private const val POLL_MS = 10_000L
private const val PULSE_MS = 35_000L

class JellyfinPollerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var heartbeatSvc: HeartbeatService
    private lateinit var config: JellyfinConfig

    // Write into the shared media bucket alongside Spotify/YouTube
    private val bucketId = "aw-watcher-android-tv-media"

    override fun onCreate() {
        super.onCreate()
        config = JellyfinConfig(this)

        if (!config.isConfigured) {
            stopSelf()
            return
        }

        val db = AWDatabase.getDatabase(this)
        heartbeatSvc = HeartbeatService(db)

        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        scope.launch {
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
            pollLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun pollLoop() {
        while (true) {
            tick()
            delay(POLL_MS)
        }
    }

    private suspend fun tick() {
        val body = httpGet("${config.serverUrl}/Sessions?api_key=${config.apiKey}") ?: return

        val sessions = try { JSONArray(body) } catch (e: Exception) {
            Log.w(TAG, "Parse error: $e")
            return
        }

        val session = findOurSession(sessions) ?: return
        val item = session.optJSONObject("NowPlayingItem") ?: return
        val playState = session.optJSONObject("PlayState")

        // Don't heartbeat while paused — let the event's duration freeze, resume merges on play
        if (playState?.optBoolean("IsPaused", false) == true) return

        val data = buildEventData(item)
        Log.d(TAG, "Playing: $data")

        heartbeatSvc.insertHeartbeat(
            bucketId = bucketId,
            event = Event(timestamp = System.currentTimeMillis(), duration = 0L, bucketId = bucketId, data = data),
            pulseTimeMillis = PULSE_MS,
        )
    }

    private fun findOurSession(sessions: JSONArray): JSONObject? {
        val deviceName = Build.MODEL
        var fallback: JSONObject? = null
        for (i in 0 until sessions.length()) {
            val s = sessions.optJSONObject(i) ?: continue
            if (!s.has("NowPlayingItem")) continue
            if (s.optString("DeviceName").equals(deviceName, ignoreCase = true)) return s
            if (fallback == null) fallback = s
        }
        return fallback
    }

    private fun buildEventData(item: JSONObject): String {
        val type = item.optString("Type", "")
        return JSONObject().apply {
            put("title", item.optString("Name", ""))
            put("type", type)
            put("app", "Jellyfin")

            // Genres — already present in the Sessions NowPlayingItem
            item.optJSONArray("Genres")?.let { arr ->
                if (arr.length() > 0) put("genres", arr)
            }

            val rating = item.optDouble("CommunityRating", Double.NaN)
            if (!rating.isNaN()) put("rating", Math.round(rating * 10) / 10.0)

            when (type) {
                "Episode" -> {
                    item.optString("SeriesName").takeIf { it.isNotBlank() }?.let { put("series", it) }
                    item.optString("SeasonName").takeIf { it.isNotBlank() }?.let { put("seasonName", it) }
                    val season = item.optInt("ParentIndexNumber", -1)
                    val episode = item.optInt("IndexNumber", -1)
                    if (season >= 0) put("season", season)
                    if (episode >= 0) put("episode", episode)
                    // IMDb link for cross-referencing
                    item.optJSONObject("ProviderIds")?.optString("Imdb")
                        ?.takeIf { it.isNotBlank() }?.let { put("imdbId", it) }
                }
                "Movie" -> {
                    val year = item.optInt("ProductionYear", -1)
                    if (year > 0) put("year", year)
                    item.optJSONObject("ProviderIds")?.optString("Imdb")
                        ?.takeIf { it.isNotBlank() }?.let { put("imdbId", it) }
                }
                "Audio" -> {
                    item.optString("AlbumArtist").takeIf { it.isNotBlank() }?.let { put("artist", it) }
                    item.optString("Album").takeIf { it.isNotBlank() }?.let { put("album", it) }
                }
            }
        }.toString()
    }

    private suspend fun httpGet(urlString: String): String? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText()
            else { Log.w(TAG, "HTTP ${conn.responseCode}"); null }
        } catch (e: Exception) {
            Log.w(TAG, "Request failed: $e")
            null
        }
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(CHANNEL_ID, "ActivityWatch", NotificationManager.IMPORTANCE_MIN)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ActivityWatch")
            .setContentText("Tracking Jellyfin")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
