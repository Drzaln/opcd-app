package dev.opencode.mobile.ui.servers

import dev.opencode.mobile.ui.theme.OcTheme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.AppViewModel
import dev.opencode.mobile.data.net.DiscoveredServer
import dev.opencode.mobile.data.net.ServerConfig
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    appVm: AppViewModel,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val servers by appVm.servers.collectAsState()
    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ServerConfig?>(null) }
    var showScan by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("opencode") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; showForm = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add server") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Connect to opencode running on your Mac over Tailscale",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedButton(
                    onClick = { showScan = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Radar, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Auto-detect on network (mDNS)")
                }
            }
            items(servers, key = { it.id }) { server ->
                ServerCard(
                    server = server,
                    onConnect = { appVm.setActive(server.id); onOpen(server.id) },
                    onEdit = { editing = server; showForm = true },
                    onDelete = {
                        scope.launch {
                            appVm.removeServer(server.id)
                            if (appVm.activeServer.value?.id == server.id) appVm.clearActive()
                        }
                    },
                )
            }
        }
    }

    if (showScan) {
        ScanDialog(
            appVm = appVm,
            onDismiss = { showScan = false },
            onConnect = { server ->
                showScan = false
                scope.launch {
                    appVm.addServerSync(server)
                    appVm.setActiveSync(server.id)
                    onOpen(server.id)
                }
            },
        )
    }

    if (showForm) {
        ServerFormDialog(
            existing = editing,
            onDismiss = { showForm = false },
            onSave = { config ->
                appVm.addServer(config)
                showForm = false
            },
            appVm = appVm,
        )
    }
}

@Composable
private fun ServerCard(
    server: ServerConfig,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(server.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            }
            Text(server.baseUrl, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = OcTheme.colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onConnect) { Text("Connect") }
        }
    }
}

@Composable
private fun ScanDialog(
    appVm: AppViewModel,
    onDismiss: () -> Unit,
    onConnect: (ServerConfig) -> Unit,
) {
    var found by remember { mutableStateOf<List<DiscoveredServer>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<DiscoveredServer?>(null) }
    var password by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    var probeError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scanning = true
        error = null
        appVm.nsd.discover().collect { server ->
            found = found + server
        }
        scanning = false
    }

    if (selected != null) {
        val server = selected!!
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Connect to ${server.name}") },
            text = {
                Column {
                    Text(server.baseUrl, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Server password (OPENCODE_SERVER_PASSWORD)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (probeError != null) {
                        Text(probeError!!, color = OcTheme.colors.red, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !probing,
                    onClick = {
                        scope.launch {
                            probing = true
                            probeError = null
                            val config = ServerConfig(
                                name = server.name,
                                baseUrl = server.baseUrl,
                                username = "opencode",
                                password = password,
                            )
                            val result = appVm.probe(config)
                            probing = false
                            if (result.ok) {
                                onConnect(config)
                            } else {
                                probeError = "Connection failed: ${result.error.ifEmpty { "unknown" }}"
                            }
                        }
                    },
                ) {
                    if (probing) {
                        CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Connect")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Back") } },
        )
    } else {
        var remoteHost by remember { mutableStateOf("") }
        var remotePort by remember { mutableStateOf("4096") }
        var remotePassword by remember { mutableStateOf("") }
        var remoteProbing by remember { mutableStateOf(false) }
        var remoteError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Connect to server") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "mDNS scans your LAN. Tailscale works anywhere — use the remote field below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OcTheme.colors.textSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("mDNS (LAN)", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    if (error != null) {
                        Text(error!!, color = OcTheme.colors.red, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (scanning) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Scanning…")
                        }
                    }
                    found.forEach { s ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selected = s },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(s.name, style = MaterialTheme.typography.titleSmall)
                                Text(s.baseUrl, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = OcTheme.colors.textSecondary)
                            }
                        }
                    }
                    if (found.isEmpty() && !scanning) {
                        Text(
                            "No server found on the LAN. Make sure opencode runs with --mdns on the Mac and you're on the same network.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Tailscale (remote)", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = remoteHost,
                        onValueChange = { remoteHost = it },
                        label = { Text("Mac hostname or IP (e.g. my-mac.ts.net or 100.64.0.1)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        OutlinedTextField(
                            value = remotePort,
                            onValueChange = { remotePort = it },
                            label = { Text("Port") },
                            singleLine = true,
                            modifier = Modifier.weight(0.4f),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = remotePassword,
                            onValueChange = { remotePassword = it },
                            label = { Text("Server password") },
                            singleLine = true,
                            modifier = Modifier.weight(0.6f),
                        )
                    }
                    if (remoteError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(remoteError!!, color = OcTheme.colors.red, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = !remoteProbing && remoteHost.isNotBlank(),
                        onClick = {
                            scope.launch {
                                remoteProbing = true
                                remoteError = null
                                val isTsNet = remoteHost.contains(".ts.net")
                                val scheme = if (isTsNet) "https" else "http"
                                val base = if (isTsNet) "$scheme://${remoteHost.trim()}" else "$scheme://${remoteHost.trim()}:${remotePort.trim().ifEmpty { "4096" }}"
                                val config = ServerConfig(
                                    name = remoteHost.trim(),
                                    baseUrl = base,
                                    username = "opencode",
                                    password = remotePassword,
                                )
                                val result = appVm.probe(config)
                                remoteProbing = false
                                if (result.ok) {
                                    onConnect(config)
                                } else {
                                    remoteError = "Connection failed: ${result.error.ifEmpty { "unknown" }}"
                                }
                            }
                        },
                    ) {
                        if (remoteProbing) {
                            CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Connect over Tailscale")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        )
    }
}

@Composable
private fun ServerFormDialog(
    existing: ServerConfig?,
    onDismiss: () -> Unit,
    onSave: (ServerConfig) -> Unit,
    appVm: AppViewModel,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "http://") }
    var username by remember { mutableStateOf(existing?.username ?: "opencode") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var probing by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add server" else "Edit server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL (http:// or https://)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (probeResult != null) {
                    Spacer(Modifier.height(8.dp))
                    val ok = probeResult!!.startsWith("OK")
                    Text(probeResult!!, color = if (ok) OcTheme.colors.green else OcTheme.colors.red, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            probing = true
                            probeResult = null
                            val config = ServerConfig(id = existing?.id ?: "", name = name, baseUrl = baseUrl, username = username, password = password)
                            val result = appVm.probe(config)
                            probing = false
                            probeResult = if (result.ok) "OK — opencode ${result.version}" else "Failed: ${result.error}"
                        }
                    },
                    enabled = !probing && baseUrl.isNotBlank(),
                ) {
                    if (probing) {
                        CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Test connection")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && baseUrl.isNotBlank(),
                onClick = {
                    onSave(ServerConfig(id = existing?.id ?: java.util.UUID.randomUUID().toString(), name = name, baseUrl = baseUrl, username = username, password = password))
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}