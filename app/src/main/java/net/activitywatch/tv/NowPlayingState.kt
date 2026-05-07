package net.activitywatch.tv

import android.graphics.Bitmap

data class NowPlayingState(
    val hasPermission: Boolean = false,
    val hasActiveSession: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArt: Bitmap? = null,
    val appName: String? = null,
    val duration: Long = 0L,
    val position: Long = 0L,
)
