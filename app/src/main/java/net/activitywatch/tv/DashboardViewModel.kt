package net.activitywatch.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.activitywatch.tv.data.AppUsageSummary
import net.activitywatch.tv.data.AWDatabase
import org.json.JSONObject

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    enum class Range { DAY, WEEK, MONTH }

    private val db = AWDatabase.getDatabase(app)
    private val bucketId = "aw-watcher-android-tv-window"

    private val _selectedRange = MutableStateFlow(Range.DAY)
    val selectedRange: StateFlow<Range> = _selectedRange.asStateFlow()

    private val _topApps = MutableStateFlow<List<AppUsageSummary>>(emptyList())
    val topApps: StateFlow<List<AppUsageSummary>> = _topApps.asStateFlow()

    val totalDurationMs: StateFlow<Long> = _topApps
        .map { list -> list.sumOf { it.totalDurationMs } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

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
        val events = db.eventDao().getTimelineEvents(bucketId, startMs, now)
        _topApps.value = events
            .mapNotNull { event ->
                runCatching {
                    val json = JSONObject(event.data)
                    val label = json.optString("appLabel").ifBlank { json.optString("app", "Unknown") }
                    val pkg = json.optString("app", "")
                    Triple(label, pkg, event.duration)
                }.getOrNull()
            }
            .groupBy { (label, _, _) -> label }
            .map { (label, entries) ->
                AppUsageSummary(
                    appLabel = label,
                    packageName = entries.first().second,
                    totalDurationMs = entries.sumOf { it.third },
                )
            }
            .sortedByDescending { it.totalDurationMs }
            .take(15)
    }
}
