package de.freal.toleo.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.freal.toleo.AppContainer
import de.freal.toleo.sync.SyncStatus
import de.freal.toleo.sync.SyncWorker
import de.freal.toleo.tolino.LoginResult
import de.freal.toleo.tolino.RemoteDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class AccountViewModel(val container: AppContainer) : ViewModel() {
    val devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val loading = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val session = container.tokens.session
    val syncStatus = container.sync.status

    fun load() = launch { devices.value = container.api.listDevices() }

    fun delete(device: RemoteDevice) = launch {
        container.api.deleteDevice(device.id)
        devices.value = container.api.listDevices()
    }

    fun retryRegistration(context: android.content.Context, onSuccess: () -> Unit) = launch {
        when (val r = container.auth.registerDevice()) {
            LoginResult.Success -> { SyncWorker.schedulePeriodic(context.applicationContext); onSuccess() }
            is LoginResult.DeviceNotRegistered -> message.value = r.reason
            is LoginResult.Failed -> message.value = r.reason
        }
    }

    fun syncNow() = launch {
        container.library.refreshInventory()
        container.sync.sync()
    }

    fun logout(onDone: () -> Unit) = viewModelScope.launch {
        container.auth.logout()
        container.library.clearAll()
        onDone()
    }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch {
        loading.value = true
        try {
            block()
        } catch (e: Exception) {
            message.value = e.message ?: e.javaClass.simpleName
        } finally {
            loading.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    vm: AccountViewModel,
    deviceProblem: String?,
    onBack: () -> Unit,
    onRegistered: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val devices by vm.devices.collectAsState()
    val loading by vm.loading.collectAsState()
    val message by vm.message.collectAsState()
    val session by vm.session.collectAsState()
    val sync by vm.syncStatus.collectAsState()
    var confirmLogout by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account & devices") },
                navigationIcon = {
                    if (deviceProblem == null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                Column(Modifier.widthIn(max = 640.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (deviceProblem != null || session?.deviceRegistered == false) {
                        Card {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("This phone isn't registered yet", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "A tolino account can have at most five devices. Remove one you no longer use below, then try again." +
                                        (deviceProblem?.let { "\n\nServer said: $it" } ?: ""),
                                )
                                Button(onClick = { vm.retryRegistration(context, onRegistered) }) { Text("Register this phone") }
                            }
                        }
                    }
                    session?.let { s ->
                        Text("Signed in via ${s.partnerId} · reseller ${s.resellerId}", style = MaterialTheme.typography.bodyMedium)
                        Text("Device ID ${s.hardwareId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        when (val st = sync) {
                            SyncStatus.Idle -> "Not synced yet in this session"
                            SyncStatus.Running -> "Syncing…"
                            is SyncStatus.Done -> "Last sync: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(st.at))}"
                            is SyncStatus.Failed -> "Last sync failed: ${st.message}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::syncNow, enabled = !loading) { Text("Sync now") }
                        OutlinedButton(onClick = { confirmLogout = true }) { Text("Sign out") }
                    }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Devices on this account", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (loading) CircularProgressIndicator(Modifier.padding(4.dp))
                    }
                }
            }
            item(key = "shop") { ShopAccessSection(vm.container, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            item(key = "diag") { DiagnosticsSection() }
            items(devices, key = { it.id }) { d ->
                Box(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    var confirm by remember { mutableStateOf(false) }
                    val isThis = d.id == session?.hardwareId
                    ListItem(
                        headlineContent = { Text(d.name + if (isThis) " (this phone)" else "") },
                        supportingContent = {
                            Text(listOfNotNull(d.type, d.lastUsage?.let { "last used " + DateFormat.getDateInstance().format(Date(it)) }).joinToString(" · "))
                        },
                        leadingContent = { Icon(Icons.Filled.PhoneAndroid, null) },
                        trailingContent = {
                            if (!isThis) IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, "Remove device") }
                        },
                    )
                    if (confirm) {
                        AlertDialog(
                            onDismissRequest = { confirm = false },
                            title = { Text("Remove “${d.name}”?") },
                            text = { Text("The device will be signed out of the tolino cloud and has to be set up again to sync.") },
                            confirmButton = { TextButton(onClick = { confirm = false; vm.delete(d) }) { Text("Remove") } },
                            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                        )
                    }
                }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Sign out?") },
            text = { Text("Downloaded books and unsynced changes on this phone will be removed. Your cloud library is not affected.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; vm.logout(onLoggedOut) }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DiagnosticsSection() {
    val entries by de.freal.toleo.tolino.Diagnostics.entries.collectAsState()
    var open by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Diagnostics (${entries.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
            TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(entries.joinToString("\n\n"))) }) { Text("Copy") }
        }
        Text(
            "Tolino API traffic since app start, with tokens redacted. Copy it to report problems.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (open) {
            entries.asReversed().forEach {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                androidx.compose.material3.HorizontalDivider()
            }
        }
    }
}
