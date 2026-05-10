package net.activitywatch.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import net.activitywatch.tv.data.AppUsageSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DashboardScreen(
    onBack: () -> Unit,
    vm: DashboardViewModel = viewModel(),
) {
    BackHandler(onBack = onBack)

    val selectedRange by vm.selectedRange.collectAsState()
    val topApps by vm.topApps.collectAsState()
    val timeline by vm.timeline.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Text(
            text = "Usage Dashboard",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )

        Spacer(Modifier.height(20.dp))

        val ranges = DashboardViewModel.Range.values()
        val tabLabels = listOf("Today", "This Week", "This Month")
        val selectedIndex = ranges.indexOf(selectedRange)

        TabRow(selectedTabIndex = selectedIndex) {
            ranges.forEachIndexed { index, range ->
                Tab(
                    selected = selectedIndex == index,
                    onFocus = { vm.selectRange(range) },
                    onClick = { vm.selectRange(range) },
                ) {
                    Text(
                        text = tabLabels[index],
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        fontSize = 16.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Top Apps panel
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
                    .padding(20.dp),
            ) {
                Text(
                    text = "TOP APPS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(16.dp))

                if (topApps.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No data", color = Color.White.copy(alpha = 0.3f), fontSize = 16.sp)
                    }
                } else {
                    val maxDuration = topApps.maxOf { it.totalDurationMs }.coerceAtLeast(1L)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(topApps) { app ->
                            AppUsageRow(app = app, maxDurationMs = maxDuration)
                        }
                    }
                }
            }

            // Timeline panel
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
                    .padding(20.dp),
            ) {
                Text(
                    text = "TIMELINE",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(16.dp))

                if (timeline.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No data", color = Color.White.copy(alpha = 0.3f), fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(timeline) { item ->
                            TimelineRow(item)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppUsageRow(app: AppUsageSummary, maxDurationMs: Long) {
    val fraction = (app.totalDurationMs.toFloat() / maxDurationMs).coerceIn(0f, 1f)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = app.appLabel,
                fontSize = 15.sp,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatDurationHm(app.totalDurationMs),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TimelineRow(item: DashboardViewModel.TimelineItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = formatTime(item.startMs),
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.5f),
        )
        Text(
            text = item.appLabel,
            fontSize = 14.sp,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatDurationHm(item.durationMs),
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.5f),
        )
    }
}

private fun formatDurationHm(ms: Long): String {
    if (ms <= 0) return "<1m"
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        m > 0 -> "${m}m"
        else -> "<1m"
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
