package net.activitywatch.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.activitywatch.tv.data.AppUsageSummary
import java.util.concurrent.TimeUnit

private val RankColors = listOf(
    Color(0xFFFFD700), // #1 gold
    Color(0xFFB0BEC5), // #2 silver
    Color(0xFFCD7F32), // #3 bronze
)
private val DefaultBarColor = Color(0xFF5C6BC0)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DashboardScreen(
    onBack: () -> Unit,
    vm: DashboardViewModel = viewModel(),
) {
    BackHandler(onBack = onBack)

    val selectedRange by vm.selectedRange.collectAsState()
    val topApps by vm.topApps.collectAsState()
    val totalMs by vm.totalDurationMs.collectAsState()

    val backFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { backFocusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Back || event.key == Key.Escape)
                ) {
                    onBack()
                    true
                } else false
            }
            .padding(horizontal = 56.dp, vertical = 36.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onBack,
                modifier = Modifier.focusRequester(backFocusRequester),
            ) {
                Text("← Back", fontSize = 14.sp)
            }
            Spacer(Modifier.width(20.dp))
            Text(
                text = "Usage Dashboard",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.weight(1f))
            if (totalMs > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "TOTAL",
                        fontSize = 10.sp,
                        letterSpacing = 2.sp,
                        color = Color.White.copy(alpha = 0.4f),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = formatDurationHm(totalMs),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        RangeSlider(
            selected = selectedRange,
            onSelect = vm::selectRange,
        )

        Spacer(Modifier.height(28.dp))

        if (topApps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No data for this period",
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 18.sp,
                )
            }
        } else {
            val maxDuration = topApps.maxOf { it.totalDurationMs }.coerceAtLeast(1L)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(topApps) { index, app ->
                    AppRankRow(rank = index + 1, app = app, maxDurationMs = maxDuration)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RangeSlider(
    selected: DashboardViewModel.Range,
    onSelect: (DashboardViewModel.Range) -> Unit,
) {
    val ranges = DashboardViewModel.Range.entries.toTypedArray()
    val labels = listOf("Today", "This Week", "This Month")
    val selectedIndex = ranges.indexOf(selected)

    val focusRequesters = remember { Array(ranges.size) { FocusRequester() } }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth(0.55f)
            .height(48.dp)
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(4.dp),
    ) {
        val segWidth = maxWidth / ranges.size
        val indicatorOffset by animateDpAsState(
            targetValue = segWidth * selectedIndex,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label = "slider",
        )

        // Sliding pill indicator
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp)),
        )

        // Focusable segment labels
        Row(modifier = Modifier.fillMaxSize()) {
            ranges.forEachIndexed { i, range ->
                val isSelected = selectedIndex == i
                Box(
                    modifier = Modifier
                        .width(segWidth)
                        .fillMaxHeight()
                        .focusRequester(focusRequesters[i])
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionRight -> {
                                        val next = (i + 1).coerceAtMost(ranges.lastIndex)
                                        if (next != i) {
                                            onSelect(ranges[next])
                                            focusRequesters[next].requestFocus()
                                        }
                                        true
                                    }
                                    Key.DirectionLeft -> {
                                        val prev = (i - 1).coerceAtLeast(0)
                                        if (prev != i) {
                                            onSelect(ranges[prev])
                                            focusRequesters[prev].requestFocus()
                                        }
                                        true
                                    }
                                    Key.Enter, Key.DirectionCenter -> {
                                        onSelect(range)
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = labels[i],
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppRankRow(rank: Int, app: AppUsageSummary, maxDurationMs: Long) {
    val fraction = (app.totalDurationMs.toFloat() / maxDurationMs).coerceIn(0f, 1f)
    val barColor = RankColors.getOrElse(rank - 1) { DefaultBarColor }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Rank badge
        Text(
            text = "#$rank",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = barColor,
            modifier = Modifier.width(32.dp),
        )

        // App name + bar
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appLabel,
                fontSize = 16.sp,
                color = Color.White,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(barColor.copy(alpha = 0.85f)),
                )
            }
        }

        // Duration
        Text(
            text = formatDurationHm(app.totalDurationMs),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
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
