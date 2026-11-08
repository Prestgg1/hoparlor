// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.prestgg.hoparlor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var discovery: Discovery

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Hardware volume keys control media volume even when nothing is playing yet.
        volumeControlStream = AudioManager.STREAM_MUSIC
        discovery = Discovery(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        setContent { HoparlorTheme { HoparlorScreen(discovery) } }
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
    }

    override fun onStop() {
        discovery.stop()
        super.onStop()
    }
}

@Composable
private fun HoparlorTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

private class Settings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("hoparlor", Context.MODE_PRIVATE)
    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(v) = prefs.edit { putString("host", v) }
    var port: Int
        get() = prefs.getInt("port", Discovery.DEFAULT_PORT)
        set(v) = prefs.edit { putInt("port", v) }
    var targetMs: Int
        get() = prefs.getInt("target", 20)
        set(v) = prefs.edit { putInt("target", v) }
    var adaptive: Boolean
        get() = prefs.getBoolean("adaptive", true)
        set(v) = prefs.edit { putBoolean("adaptive", v) }
    /** Name of the PC we last played from; it is reconnected to automatically. */
    var lastServer: String
        get() = prefs.getString("last_server", "") ?: ""
        set(v) = prefs.edit { putString("last_server", v) }
    var autoConnect: Boolean
        get() = prefs.getBoolean("auto_connect", true)
        set(v) = prefs.edit { putBoolean("auto_connect", v) }
}

@Composable
private fun HoparlorScreen(discovery: Discovery) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    var host by remember { mutableStateOf(settings.host) }
    var port by remember { mutableStateOf(settings.port.toString()) }
    var target by remember { mutableStateOf(settings.targetMs.toFloat()) }
    var adaptive by remember { mutableStateOf(settings.adaptive) }
    var autoConnect by remember { mutableStateOf(settings.autoConnect) }
    var showManual by remember { mutableStateOf(false) }
    // After the user taps Disconnect, stop auto-connecting until the app is reopened.
    var userStopped by remember { mutableStateOf(false) }
    val state = Playback.state
    val active = state is PlayState.Playing || state is PlayState.Connecting

    fun connect(h: String, p: Int, label: String) {
        userStopped = false
        PlaybackService.start(ctx, h, p, label, target.roundToInt(), adaptive)
    }

    fun connect(s: Server) {
        settings.lastServer = s.name
        connect(s.best.host, s.port, s.name)
    }

    // Zero-tap flow: reconnect to the last PC, or to the only PC on the network.
    val servers = discovery.servers.toList()
    LaunchedEffect(servers, state, autoConnect, userStopped) {
        if (!autoConnect || userStopped) return@LaunchedEffect
        when (state) {
            PlayState.Idle -> {
                val pick = servers.firstOrNull { it.name == settings.lastServer }
                    ?: servers.singleOrNull()?.takeIf { settings.lastServer.isEmpty() }
                if (pick != null) connect(pick)
            }
            is PlayState.Playing -> {
                // The USB cable was plugged in mid-session: move to the lower-latency path.
                val s = servers.firstOrNull { it.name == state.label } ?: return@LaunchedEffect
                if (s.viaUsb && s.best.host != state.host) connect(s)
            }
            else -> Unit
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        StatusCard(state, onStop = {
            userStopped = true
            PlaybackService.stop(ctx)
        })

        if (!active) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.found_servers), style = MaterialTheme.typography.titleMedium)
                    if (servers.isEmpty()) {
                        Text(stringResource(R.string.searching), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    servers.forEach { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { connect(s) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(if (s.viaUsb) R.string.via_usb else R.string.via_wifi),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(onClick = { connect(s) }) { Text(stringResource(R.string.connect)) }
                        }
                    }
                    HorizontalDivider()
                    TextButton(onClick = { showManual = !showManual }) {
                        Text(stringResource(if (showManual) R.string.manual_hide else R.string.manual))
                    }
                    if (showManual) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = host, onValueChange = { host = it.trim() },
                                label = { Text(stringResource(R.string.host)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                                label = { Text(stringResource(R.string.port)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(96.dp)
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                val p = port.toIntOrNull() ?: Discovery.DEFAULT_PORT
                                settings.host = host
                                settings.port = p
                                connect(host, p, host)
                            },
                            enabled = host.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.connect)) }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                SettingSwitch(
                    stringResource(R.string.auto_connect), stringResource(R.string.auto_connect_hint), autoConnect
                ) {
                    autoConnect = it
                    settings.autoConnect = it
                }
                Spacer(Modifier.padding(6.dp))
                Text(stringResource(R.string.buffer_title, target.roundToInt()), style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = target, onValueChange = { target = it }, valueRange = 2f..150f,
                    onValueChangeFinished = {
                        settings.targetMs = target.roundToInt()
                        NativeEngine.setTarget(target.roundToInt(), adaptive)
                    }
                )
                Text(stringResource(R.string.buffer_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(6.dp))
                SettingSwitch(stringResource(R.string.adaptive), stringResource(R.string.adaptive_hint), adaptive) {
                    adaptive = it
                    settings.adaptive = it
                    NativeEngine.setTarget(target.roundToInt(), it)
                }
            }
        }

        Text(stringResource(R.string.help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingSwitch(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StatusCard(state: PlayState, onStop: () -> Unit) {
    val stats = remember { mutableStateOf(FloatArray(NativeEngine.COUNT)) }
    if (state is PlayState.Playing) {
        LaunchedEffect(state) {
            while (true) {
                val s = FloatArray(NativeEngine.COUNT)
                NativeEngine.stats(s)
                stats.value = s
                delay(250)
            }
        }
    }

    val playing = state is PlayState.Playing
    Card(
        Modifier.fillMaxWidth(),
        colors = if (playing) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (state) {
                PlayState.Idle -> Text(stringResource(R.string.status_idle), style = MaterialTheme.typography.titleMedium)
                is PlayState.Connecting -> Text(stringResource(R.string.status_connecting, state.label),
                    style = MaterialTheme.typography.titleMedium)
                is PlayState.Failed -> Text(state.message, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error)
                is PlayState.Playing -> {
                    val s = stats.value
                    val receiving = s[NativeEngine.RECEIVING] > 0f
                    Text(state.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (receiving) stringResource(R.string.latency_total, s[NativeEngine.TOTAL_MS].roundToInt())
                        else stringResource(R.string.waiting_audio),
                        style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold
                    )
                    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    Text(
                        stringResource(
                            R.string.latency_breakdown,
                            s[NativeEngine.PACKET_MS], s[NativeEngine.NETWORK_MS],
                            s[NativeEngine.BUFFER_MS], s[NativeEngine.TARGET_MS], s[NativeEngine.OUTPUT_MS]
                        ), style = mono
                    )
                    Text(
                        stringResource(
                            R.string.stats_line,
                            s[NativeEngine.PACKETS].toLong(), s[NativeEngine.LOST].toLong(),
                            s[NativeEngine.UNDERRUNS].toLong(), s[NativeEngine.RATIO_PPM].roundToInt(),
                            if (s[NativeEngine.EXCLUSIVE] > 0f) "MMAP" else "shared", s[NativeEngine.BURST].toInt()
                        ), style = mono
                    )
                }
            }
            if (state is PlayState.Playing || state is PlayState.Connecting) {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.disconnect))
                }
            }
        }
    }
}
