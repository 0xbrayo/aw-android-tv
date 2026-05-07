package net.activitywatch.tv

import android.app.Application
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NowPlayingViewModel(app: Application) : AndroidViewModel(app) {

    private val mediaSessionManager = app.getSystemService(MediaSessionManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(NowPlayingState())
    val state: StateFlow<NowPlayingState> = _state.asStateFlow()

    private var activeController: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refreshFromController()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshFromController()
        override fun onSessionDestroyed() = bindToController(null)
    }

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        pickBestController(controllers.orEmpty())
    }

    init {
        _state.update { it.copy(hasPermission = isNotificationListenerEnabled()) }
        viewModelScope.launch {
            MediaSessionListenerService.isConnected.collect { connected ->
                if (connected) startMonitoring() else handleDisconnect()
            }
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            getApplication<Application>().contentResolver,
            "enabled_notification_listeners"
        )
        return enabled?.contains(getApplication<Application>().packageName) == true
    }

    private fun startMonitoring() {
        _state.update { it.copy(hasPermission = true) }
        val cn = ComponentName(getApplication(), MediaSessionListenerService::class.java)
        try {
            pickBestController(mediaSessionManager.getActiveSessions(cn))
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionListener, cn, mainHandler)
        } catch (_: SecurityException) {
            _state.update { it.copy(hasPermission = false) }
        }
    }

    private fun handleDisconnect() {
        try { mediaSessionManager.removeOnActiveSessionsChangedListener(sessionListener) } catch (_: Exception) {}
        bindToController(null)
        _state.update { it.copy(hasPermission = isNotificationListenerEnabled()) }
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
        if (ctrl == null) {
            _state.update { it.copy(hasActiveSession = false, isPlaying = false, title = null, artist = null, album = null, albumArt = null, appName = null, duration = 0L, position = 0L) }
            return
        }
        val meta = ctrl.metadata
        val ps = ctrl.playbackState
        val appLabel = try {
            getApplication<Application>().packageManager
                .getApplicationLabel(getApplication<Application>().packageManager.getApplicationInfo(ctrl.packageName, 0))
                .toString()
        } catch (_: Exception) { ctrl.packageName }

        _state.update {
            it.copy(
                hasPermission = true,
                hasActiveSession = true,
                isPlaying = ps?.state == PlaybackState.STATE_PLAYING,
                title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                albumArt = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                appName = appLabel,
                duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                position = ps?.position ?: 0L,
            )
        }
    }

    override fun onCleared() = handleDisconnect()
}
