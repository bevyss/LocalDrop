package org.localdrop.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.util.Locale
import java.util.Date
import java.text.DateFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val background = Color(0xFF080E1D)
private val panel = Color(0xFF152540)
private val accent = Color(0xFF5BCBFF)

class MainActivity : ComponentActivity() {
    private enum class Section { NEARBY, FILES, SETTINGS }
    private data class Prompt(val title: String, val message: String, val confirm: String,
                              val code: String?, val result: CompletableFuture<Boolean>)

    private val peers = mutableStateListOf<DiscoveryRecord>()
    private val received = mutableStateListOf<ReceivedFile>()
    private var discoveryStatus by mutableStateOf("Starting discovery…")
    private var transferStatus by mutableStateOf("")
    private var filesStatus by mutableStateOf("")
    private var settingsStatus by mutableStateOf("")
    private var prompt by mutableStateOf<Prompt?>(null)
    private var section by mutableStateOf(Section.NEARBY)
    private var deviceName by mutableStateOf("")
    private var nameDraft by mutableStateOf("")
    private var selectedPeer: DiscoveryRecord? = null
    private lateinit var localization: Localization
    private var language by mutableStateOf("en")
    private fun t(key: String, vararg args: Any) = localization.text(key, *args)
    private var service: DiscoveryService? = null
    private var engine: TransferEngine? = null
    private val transfers = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(8, 14, 29)
        window.navigationBarColor = android.graphics.Color.rgb(8, 14, 29)
        localization = Localization(this)
        language = localization.language
        discoveryStatus = t("starting")
        deviceName = DeviceName.load(this)
        nameDraft = deviceName
        try {
            val id = DeviceIdentity.loadOrCreate(this)
            engine = TransferEngine(this, id, deviceName, localization,
                verifyPeer = { name, code -> ask(t("secure_connection"),
                    t("confirm_code", name), t("codes_match"), code) },
                acceptFile = { sender, name, length -> ask(t("incoming_file"),
                    t("wants_to_send", sender) + "\n\n$name\n\n" + formatSize(length), t("accept")) },
                status = { message -> runOnUiThread { transferStatus = message } },
                onReceived = { refreshReceived() })
        } catch (e: Exception) {
            Log.e("LocalDrop", "event=credentials_failed", e)
            transferStatus = t("secure_failed")
        }
        setContent {
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                val peer = selectedPeer
                if (uri != null && peer != null) transfers.execute { engine?.send(peer, uri) }
            }
            key(language) {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = accent, onPrimary = Color(0xFF061326),
                background = background, surface = panel, onSurface = Color.White)) {
                prompt?.let { pending ->
                    Dialog(onDismissRequest = { finishPrompt(pending, false) }) {
                        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF172844)) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                Text(pending.title, style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.SemiBold)
                                Text(pending.message, color = Color(0xFFD5E0F2))
                                pending.code?.let {
                                    Text(it, style = MaterialTheme.typography.headlineMedium,
                                        color = accent, modifier = Modifier.align(Alignment.CenterHorizontally))
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { finishPrompt(pending, false) }) { Text(t("decline")) }
                                    Spacer(Modifier.width(8.dp))
                                    Button(onClick = { finishPrompt(pending, true) }) { Text(pending.confirm) }
                                }
                            }
                        }
                    }
                }
                Scaffold(containerColor = background, topBar = {
                    Column(Modifier.statusBarsPadding()) {
                        Row(Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.localdrop_logo), "LocalDrop",
                                Modifier.width(100.dp).height(52.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(t("tagline"), color = Color(0xFFAAB8D2),
                                style = MaterialTheme.typography.labelMedium)
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            listOf(Section.NEARBY to t("nearby"), Section.FILES to t("files"),
                                Section.SETTINGS to t("settings")).forEach { (target, label) ->
                                TextButton(onClick = {
                                    section = target
                                    if (target == Section.FILES) refreshReceived()
                                }) {
                                    Text(label, color = if (section == target) accent else Color(0xFF9DAECB),
                                        fontWeight = if (section == target) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                        HorizontalDivider(color = Color(0xFF263A59))
                    }
                }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(18.dp)) {
                        when (section) {
                            Section.NEARBY -> {
                                Text(t("nearby"), style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text(t("this_device", deviceName, "Android"), color = Color(0xFFAAB8D2))
                                Spacer(Modifier.height(18.dp))
                                Card(colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                        Text(discoveryStatus)
                                        if (transferStatus.isNotBlank()) Text(transferStatus, color = accent)
                                    }
                                }
                                Spacer(Modifier.height(18.dp))
                                Text(t("tap_device"), color = Color(0xFFAAB8D2))
                                Spacer(Modifier.height(8.dp))
                                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    items(peers, key = { it.id }) { peer ->
                                        Card(Modifier.fillMaxWidth().clickable {
                                            if (peer.capabilities == "file1") {
                                                selectedPeer = peer
                                                picker.launch(arrayOf("*/*"))
                                            } else transferStatus = t("unsupported_peer")
                                        }, colors = CardDefaults.cardColors(containerColor = panel)) {
                                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(peer.name, style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.SemiBold)
                                                Text((if (peer.type == "android") "Android" else if (peer.type == "windows") "Windows" else peer.type)
                                                    + " · " + t("online"), color = Color(0xFFAAB8D2))
                                            }
                                        }
                                    }
                                }
                                OutlinedButton(onClick = {
                                    prompt?.let { finishPrompt(it, false) }
                                    engine?.cancel()
                                    transferStatus = t("transfer_cancelled")
                                }, modifier = Modifier.fillMaxWidth()) { Text(t("cancel_transfer")) }
                            }
                            Section.FILES -> {
                                Text(t("files"), style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.SemiBold)
                                Text("Downloads / LocalDrop", color = Color(0xFFAAB8D2))
                                TextButton(onClick = { refreshReceived() }) { Text(t("refresh")) }
                                if (filesStatus.isNotBlank()) Text(filesStatus, color = accent)
                                if (received.isEmpty()) Text(t("no_files"), color = Color(0xFFAAB8D2))
                                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    items(received, key = { it.uri.toString() }) { file ->
                                        Card(colors = CardDefaults.cardColors(containerColor = panel)) {
                                            Row(Modifier.fillMaxWidth().padding(14.dp),
                                                verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                        fontWeight = FontWeight.SemiBold)
                                                    Text(formatSize(file.size), color = Color(0xFFAAB8D2),
                                                        style = MaterialTheme.typography.bodySmall)
                                                    if (file.modified > 0) Text(
                                                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM,
                                                            DateFormat.SHORT).format(Date(file.modified)),
                                                        color = Color(0xFFAAB8D2),
                                                        style = MaterialTheme.typography.bodySmall)
                                                }
                                                TextButton(onClick = { openReceived(file) }) { Text(t("open")) }
                                            }
                                        }
                                    }
                                }
                            }
                            Section.SETTINGS -> {
                              Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                Text(t("settings"), style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(20.dp))
                                OutlinedTextField(value = nameDraft, onValueChange = { nameDraft = it },
                                    label = { Text(t("device_name")) }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(10.dp))
                                Text(t("device_name_help"),
                                    color = Color(0xFFAAB8D2))
                                Spacer(Modifier.height(18.dp))
                                Button(onClick = { saveName() }) { Text(t("save_name")) }
                                if (settingsStatus.isNotBlank()) Text(settingsStatus, color = accent)
                                Spacer(Modifier.height(18.dp))
                                Text(t("language"), fontWeight = FontWeight.SemiBold)
                                var languageExpanded by remember { mutableStateOf(false) }
                                Box {
                                    OutlinedButton(onClick = { languageExpanded = true }) {
                                        Text(mapOf("en" to "English", "tr" to "Türkçe", "de" to "Deutsch",
                                            "es" to "Español", "zh" to "中文", "ja" to "日本語")[language] ?: "English")
                                    }
                                    DropdownMenu(expanded = languageExpanded, onDismissRequest = { languageExpanded = false }) {
                                        listOf("en" to "English", "tr" to "Türkçe", "de" to "Deutsch",
                                            "es" to "Español", "zh" to "中文", "ja" to "日本語").forEach { (code, name) ->
                                            DropdownMenuItem(text = { Text(name) }, onClick = {
                                                localization.change(code)
                                                language = code
                                                languageExpanded = false
                                                discoveryStatus = if (peers.isEmpty()) t("no_devices") else t("device_count", peers.size)
                                            })
                                        }
                                    }
                                }
                                Spacer(Modifier.height(28.dp))
                                Card(colors = CardDefaults.cardColors(containerColor = panel)) {
                                    Column(Modifier.fillMaxWidth().padding(18.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(t("downloads"), fontWeight = FontWeight.SemiBold)
                                        Text(t("downloads_help"),
                                            color = Color(0xFFAAB8D2))
                                    }
                                }
                                Spacer(Modifier.height(24.dp))
                                Text("LocalDrop 0.1.0 · LD/1", color = Color(0xFF8293B1))
                              }
                            }
                        }
                    }
                }
            }
            }
        }
    }

    private fun finishPrompt(pending: Prompt, accepted: Boolean) {
        pending.result.complete(accepted)
        if (prompt === pending) prompt = null
    }

    private fun ask(title: String, message: String, confirm: String, code: String? = null): Boolean {
        val future = CompletableFuture<Boolean>()
        runOnUiThread { prompt = Prompt(title, message, confirm, code, future) }
        return try { future.get(5, TimeUnit.MINUTES) }
        catch (_: Exception) { false }
        finally { runOnUiThread { if (prompt?.result === future) prompt = null } }
    }

    private fun saveName() {
        if (engine?.isBusy == true) {
            settingsStatus = t("busy_name")
            return
        }
        try {
            val saved = DeviceName.save(this, nameDraft)
            if (saved == deviceName) {
                settingsStatus = t("name_unchanged")
                return
            }
            Log.i("LocalDrop", "event=device_name_changed")
            recreate()
        } catch (_: IllegalArgumentException) {
            settingsStatus = t("name_invalid")
        } catch (e: Exception) {
            Log.e("LocalDrop", "event=device_name_save_failed", e)
            settingsStatus = t("name_failed")
        }
    }

    private fun refreshReceived() {
        filesStatus = ""
        transfers.execute {
            val files = ReceivedFiles.list(this)
            runOnUiThread { received.clear(); received.addAll(files) }
        }
    }

    private fun openReceived(file: ReceivedFile) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(file.uri, file.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) { filesStatus = t("open_failed") }
    }

    override fun onStart() {
        super.onStart()
        refreshReceived()
        try {
            if (engine == null) return
            service = DiscoveryService(this, localization, DeviceIdentity.loadOrCreate(this),
                onPeer = { peer ->
                    peers.removeAll { it.id == peer.id }
                    peers.add(peer)
                    discoveryStatus = t("device_count", peers.size)
                },
                onLost = { id ->
                    peers.removeAll { it.id == id }
                    discoveryStatus = if (peers.isEmpty()) t("no_devices")
                        else t("device_count", peers.size)
                },
                onStatus = { discoveryStatus = it },
                onIncoming = { socket -> engine?.receive(socket) })
            service?.start(deviceName)
        } catch (e: Exception) {
            discoveryStatus = t("discovery_failed")
            Log.e("LocalDrop", "event=discovery_start_failed", e)
        }
    }

    override fun onStop() {
        service?.stop()
        service = null
        peers.clear()
        super.onStop()
    }

    override fun onDestroy() {
        prompt?.result?.complete(false)
        engine?.cancel()
        transfers.shutdownNow()
        super.onDestroy()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1048576 -> String.format(Locale.getDefault(), "%.1f KiB", bytes / 1024.0)
        bytes < 1073741824 -> String.format(Locale.getDefault(), "%.1f MiB", bytes / 1048576.0)
        else -> String.format(Locale.getDefault(), "%.1f GiB", bytes / 1073741824.0)
    }
}
