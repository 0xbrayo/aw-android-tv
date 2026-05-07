package net.activitywatch.tv

import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaSessionListenerService : NotificationListenerService() {

    companion object {
        private val _isConnected = MutableStateFlow(false)
        val isConnected = _isConnected.asStateFlow()
    }

    override fun onListenerConnected() {
        _isConnected.value = true
    }

    override fun onListenerDisconnected() {
        _isConnected.value = false
    }
}
