package net.activitywatch.tv.data

data class AppUsageSummary(
    val appLabel: String,
    val packageName: String,
    val totalDurationMs: Long,
)
