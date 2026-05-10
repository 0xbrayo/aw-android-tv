package net.activitywatch.tv

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import net.activitywatch.tv.ui.theme.AwandroidtvTheme
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, WatcherForegroundService::class.java))
        if (UsageStatsWatcherService.hasPermission(this)) {
            startForegroundService(Intent(this, UsageStatsWatcherService::class.java))
        }
        if (JellyfinConfig(this).isConfigured) {
            startForegroundService(Intent(this, JellyfinPollerService::class.java))
        }
        setContent {
            AwandroidtvTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                ) {
                    NowPlayingScreen()
                }
            }
        }
    }
}

private val RectangleShape = androidx.compose.ui.graphics.RectangleShape

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NowPlayingScreen(vm: NowPlayingViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var showDashboard by remember { mutableStateOf(false) }

    if (showDashboard) {
        DashboardScreen(onBack = { showDashboard = false })
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            !state.hasPermission -> PermissionScreen(
                title = "Notification Access Required",
                body = "This app needs Notification Access permission to monitor active media sessions on your TV.",
                buttonLabel = "Open Notification Access Settings",
                onOpenSettings = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            )

            !state.hasUsageStatsPermission -> PermissionScreen(
                title = "Usage Access Required",
                body = "Grant Usage Access so ActivityWatch can track which app is active on your TV (e.g. Netflix, Jellyfin).",
                buttonLabel = "Open Usage Access Settings",
                onOpenSettings = {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            )

            !state.hasActiveSession -> IdleScreen(
                isJellyfinConfigured = state.isJellyfinConfigured,
                currentServerUrl = state.jellyfinServerUrl,
                currentApiKey = state.jellyfinApiKey,
                onSaveJellyfin = { url, key -> vm.saveJellyfinConfig(url, key) },
                onOpenDashboard = { showDashboard = true },
            )

            else -> MediaInfoScreen(
                state = state,
                isJellyfinConfigured = state.isJellyfinConfigured,
                currentServerUrl = state.jellyfinServerUrl,
                currentApiKey = state.jellyfinApiKey,
                onSaveJellyfin = { url, key -> vm.saveJellyfinConfig(url, key) },
                onOpenDashboard = { showDashboard = true },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PermissionScreen(
    title: String,
    body: String,
    buttonLabel: String,
    onOpenSettings: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(48.dp),
    ) {
        Text(
            text = title,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = body,
            fontSize = 18.sp,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onOpenSettings) {
            Text(buttonLabel, fontSize = 16.sp)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun IdleScreen(
    isJellyfinConfigured: Boolean,
    currentServerUrl: String,
    currentApiKey: String,
    onSaveJellyfin: (url: String, apiKey: String) -> Unit,
    onOpenDashboard: () -> Unit,
) {
    var showJellyfinSetup by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(48.dp),
    ) {
        Text(
            text = "Nothing Playing",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Start playing media on your Android TV to see it here.",
            fontSize = 18.sp,
            color = Color.White.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(32.dp))

        Button(onClick = onOpenDashboard) {
            Text("Dashboard", fontSize = 16.sp)
        }

        Spacer(Modifier.height(16.dp))

        if (showJellyfinSetup) {
            JellyfinSetupForm(
                initialServerUrl = currentServerUrl,
                initialApiKey = currentApiKey,
                onSave = { url, key ->
                    onSaveJellyfin(url, key)
                    showJellyfinSetup = false
                },
                onCancel = { showJellyfinSetup = false },
            )
        } else {
            JellyfinStatusRow(
                isConfigured = isJellyfinConfigured,
                serverUrl = currentServerUrl,
                onEdit = { showJellyfinSetup = true },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun JellyfinStatusRow(
    isConfigured: Boolean,
    serverUrl: String,
    onEdit: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isConfigured) {
            Text(
                text = "Jellyfin: $serverUrl",
                fontSize = 14.sp,
                color = Color(0xFF4CAF50),
            )
        }
        Button(onClick = onEdit) {
            Text(if (isConfigured) "Edit Jellyfin" else "Setup Jellyfin", fontSize = 16.sp)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun JellyfinSetupForm(
    initialServerUrl: String,
    initialApiKey: String,
    onSave: (url: String, apiKey: String) -> Unit,
    onCancel: () -> Unit,
) {
    var serverUrl by remember { mutableStateOf(initialServerUrl) }
    var apiKey by remember { mutableStateOf(initialApiKey) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(24.dp)
            .fillMaxWidth(0.5f),
    ) {
        Text("Jellyfin", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "Create an API key in Jellyfin Dashboard → API Keys",
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(20.dp))

        LabeledField("Server URL (e.g. http://192.168.1.x:8096)", serverUrl) { serverUrl = it }
        Spacer(Modifier.height(12.dp))
        LabeledField("API Key", apiKey) { apiKey = it }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { onSave(serverUrl, apiKey) }) { Text("Save") }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LabeledField(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MediaInfoScreen(
    state: NowPlayingState,
    isJellyfinConfigured: Boolean,
    currentServerUrl: String,
    currentApiKey: String,
    onSaveJellyfin: (url: String, apiKey: String) -> Unit,
    onOpenDashboard: () -> Unit,
) {
    var showJellyfinSetup by remember { mutableStateOf(false) }
    var livePosition by remember(state.position) { mutableLongStateOf(state.position) }

    LaunchedEffect(state.position, state.isPlaying) {
        livePosition = state.position
        if (state.isPlaying) {
            while (true) {
                delay(1000)
                livePosition += 1000L
            }
        }
    }

    val progress = if (state.duration > 0) (livePosition.toFloat() / state.duration).coerceIn(0f, 1f) else 0f

    Box(modifier = Modifier.fillMaxSize()) {
        if (showJellyfinSetup) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                JellyfinSetupForm(
                    initialServerUrl = currentServerUrl,
                    initialApiKey = currentApiKey,
                    onSave = { url, key -> onSaveJellyfin(url, key); showJellyfinSetup = false },
                    onCancel = { showJellyfinSetup = false },
                )
            }
        } else {

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 64.dp, vertical = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(48.dp),
        ) {
        // Album art
        Box(
            modifier = Modifier
                .fillMaxHeight(0.75f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            if (state.albumArt != null) {
                Image(
                    bitmap = state.albumArt.asImageBitmap(),
                    contentDescription = "Album art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "♪",
                    fontSize = 64.sp,
                    color = Color.White.copy(alpha = 0.3f),
                )
            }
        }

        // Track info + progress
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            // App name
            state.appName?.let { app ->
                Text(
                    text = app.uppercase(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                )
                Spacer(Modifier.height(8.dp))
            }

            // Title
            Text(
                text = state.title ?: "Unknown Title",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(8.dp))

            // Artist / Album
            val subtitle = listOfNotNull(state.artist, state.album).joinToString(" • ")
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 20.sp,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(32.dp))

            // Playback state badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (state.isPlaying) Color(0xFF4CAF50) else Color(0xFFFF9800),
                            shape = androidx.compose.foundation.shape.CircleShape,
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.isPlaying) "Playing" else "Paused",
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }

            if (state.duration > 0) {
                Spacer(Modifier.height(24.dp))

                // Progress bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.15f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                                )
                            ),
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatDuration(livePosition),
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                    Text(
                        text = formatDuration(state.duration),
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                }
            }
        }
        }

        // Top-right buttons — Jellyfin settings + Dashboard
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onOpenDashboard) {
                Text("Stats", fontSize = 14.sp)
            }
            Button(onClick = { showJellyfinSetup = true }) {
                Text("⚙", fontSize = 16.sp)
            }
        }

        } // end else
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
