package net.activitywatch.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.activitywatch.tv.data.AppUsageSummary
import net.activitywatch.tv.data.AWDatabase
import org.json.JSONObject

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    enum class Range { DAY, WEEK, MONTH }

    data class TimelineItem(
        val startMs: Long,
        val durationMs: Long,
        val appLabel: String,
    )

    private val db = AWDatabase.getDatabase(app)
    private val bucketId = "aw-watcher-android-tv-window"

    private val _selectedRange = MutableStateFlow(Range.DAY)
    val selectedRange: StateFlow<Range> = _selectedRange.asStateFlow()

    private val _topApps = MutableStateFlow<List<AppUsageSummary>>(emptyList())
    val topApps: StateFlow<List<AppUsageSummary>> = _topApps.asStateFlow()

    private val _timeline = MutableStateFlow<List<TimelineItem>>(emptyList())
    val timeline: StateFlow<List<TimelineItem>> = _timeline.asStateFlow()

    init { load(Range.DAY) }

    fun selectRange(range: Range) {
        _selectedRange.value = range
        load(range)
    }

    private fun load(range: Range) = viewModelScope.launch(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val startMs = now - when (range) {
            Range.DAY   -> 86_400_000L
            Range.WEEK  -> 7 * 86_400_000L
            Range.MONTH -> 30 * 86_400_000L
        }
        _topApps.value = db.eventDao().getTopApps(bucketId, startMs)
        val events = db.eventDao().getTimelineEvents(bucketId, startMs, now)
        _timeline.value = events.mapNotNull { event ->
            runCatching {
                val json = JSONObject(event.data)
                TimelineItem(
                    startMs = event.timestamp,
                    durationMs = event.duration,
                    appLabel = json.optString("appLabel").ifBlank { json.optString("app", "Unknown") },
                )
            }.getOrNull()
        }
    }
}
