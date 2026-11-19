package dev.opencode.mobile.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.Computer
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.opencode.mobile.AppViewModel
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.data.model.UsageWindow
import dev.opencode.mobile.ui.theme.OcTheme
import dev.opencode.mobile.ui.theme.ThemeMode
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appVm: AppViewModel,
    onBack: () -> Unit,
    /** As a top-level tab (inside a server): bottom bar instead of a back arrow. */
    onTab: ((dev.opencode.mobile.ui.common.AppTab) -> Unit)? = null,
) {
    val context = LocalContext.current
    val themeMode by appVm.themeMode.collectAsState()
    val notificationsEnabled by appVm.notificationsEnabled.collectAsState()
    val updateMessage by appVm.updateMessage.collectAsState()
    val checkingUpdate by appVm.checkingUpdate.collectAsState()
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            appVm.setNotificationsEnabled(true)
            dev.opencode.mobile.notify.SessionWatchService.start(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (onTab == null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = { if (onTab != null) dev.opencode.mobile.ui.common.AppBottomBar(dev.opencode.mobile.ui.common.AppTab.SETTINGS, onTab) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionLabel("Appearance") }
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text("Theme") },
                        supportingContent = { Text("Follow the system or force dark / light") },
                        leadingContent = { Icon(Icons.Filled.DarkMode, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    )
                    SingleChoiceSegmentedButtonRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
                    ) {
                        val options = listOf(
                            "System" to ThemeMode.SYSTEM,
                            "Dark" to ThemeMode.DARK,
                            "Light" to ThemeMode.LIGHT,
                        )
                        options.forEachIndexed { index, (label, mode) ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = { appVm.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

            item { SectionLabel("Notifications") }
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text("Notify when a turn finishes") },
                        supportingContent = {
                            Text("Background connection (plus a periodic fallback) so you hear when opencode is done or needs input.")
                        },
                        leadingContent = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = notificationsEnabled,
                                onCheckedChange = { want ->
                                    if (want) {
                                        val granted = Build.VERSION.SDK_INT < 33 ||
                                            ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.POST_NOTIFICATIONS,
                                            ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            appVm.setNotificationsEnabled(true)
                                            dev.opencode.mobile.notify.SessionWatchService.start(context)
                                        } else {
                                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    } else {
                                        appVm.setNotificationsEnabled(false)
                                        dev.opencode.mobile.notify.SessionWatchService.stop(context)
                                    }
                                },
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    )
                }
            }

            item { SectionLabel("Mac helper") }
            item { HelperCard(appVm) }

            item { SectionLabel("OpenCode Go") }
            item {
                val usageVm: UsageViewModel = viewModel(
                    key = "opencode_go_usage",
                    factory = viewModelFactory { initializer { UsageViewModel(app = context.applicationContext as OpenCodeApp) } },
                )
                val apiKey by usageVm.apiKey.collectAsState()
                val usageState by usageVm.state.collectAsState()
                var keyDraft by remember(apiKey) { mutableStateOf(apiKey) }
                var revealKey by remember { mutableStateOf(false) }

                LaunchedEffect(apiKey) {
                    if (apiKey.isNotBlank()) usageVm.refresh()
                }
                LaunchedEffect(apiKey) {
                    while (true) {
                        delay(60_000)
                        usageVm.refresh()
                    }
                }
                LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { usageVm.refresh() }

                SettingsCard {
                    ListItem(
                        headlineContent = { Text("API key") },
                        supportingContent = { Text("Paste your OpenCode Go key to show plan usage.") },
                        leadingContent = { Icon(Icons.Filled.VpnKey, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    )
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        label = { Text("sk-...") },
                        singleLine = true,
                        visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { revealKey = !revealKey }) {
                                Icon(
                                    if (revealKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (revealKey) "Hide API key" else "Show API key",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = { usageVm.saveKey(keyDraft) },
                            enabled = keyDraft != apiKey,
                        ) { Text("Save") }
                    }
                    if (apiKey.isNotBlank()) {
                        HorizontalDivider()
                        UsageBars(state = usageState, onRefresh = { usageVm.refresh() })
                    }
                }
            }

            item { SectionLabel("About") }
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text("Version") },
                        supportingContent = { Text(version.ifBlank { "unknown" }) },
                        leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Check for updates") },
                        supportingContent = {
                            val message = updateMessage
                            Text(
                                when {
                                    checkingUpdate -> "Checking…"
                                    !message.isNullOrBlank() -> message
                                    else -> "Fetched from GitHub Releases"
                                },
                            )
                        },
                        leadingContent = {
                            if (checkingUpdate) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = null)
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clickable(enabled = !checkingUpdate) { appVm.checkForUpdate(force = true) },
                    )
                }
            }

            item { SectionLabel("Security") }
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text("Plain HTTP with Basic auth") },
                        supportingContent = {
                            Text(
                                "The app talks to your server over HTTP(S) with HTTP Basic auth. Use Tailscale so traffic never leaves the tailnet.",
                            )
                        },
                        leadingContent = { Icon(Icons.Filled.Security, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageBars(state: UsageUiState, onRefresh: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Plan usage", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (state.loading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh usage")
            }
        }
        val usage = state.usage
        if (usage == null) {
            Text(
                state.error ?: "No usage data",
                style = MaterialTheme.typography.bodySmall,
                color = OcTheme.colors.textSecondary,
            )
        } else {
            UsageRow("5 hours", usage.rolling)
            UsageRow("Weekly", usage.weekly)
            UsageRow("Monthly", usage.monthly)
        }
        state.error?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = OcTheme.colors.red)
        }
    }
}

@Composable
private fun UsageRow(label: String, window: UsageWindow?) {
    if (window == null) return
    val percent = window.percent.coerceIn(0, 100)
    val color = when {
        percent >= 90 -> OcTheme.colors.red
        percent >= 70 -> OcTheme.colors.orange
        else -> MaterialTheme.colorScheme.primary
    }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("$percent%", style = MaterialTheme.typography.labelMedium, color = color)
        }
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        window.resetsAt?.let {
            Text(
                "resets in ${formatReset(it)}",
                style = MaterialTheme.typography.labelSmall,
                color = OcTheme.colors.textSecondary,
            )
        }
    }
}

private fun formatReset(iso: String): String = runCatching {
    val minutes = Duration.between(Instant.now(), Instant.parse(iso)).toMinutes()
    when {
        minutes <= 0 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 1440 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes / 1440}d ${(minutes % 1440) / 60}h"
    }
}.getOrDefault("")

@Composable
private fun SectionLabel(text: String) = dev.opencode.mobile.ui.common.SectionHeader(text)

@Composable
private fun SettingsCard(content: @Composable () -> Unit) = dev.opencode.mobile.ui.common.OcCard(content = content)


/**
 * Optional companion daemon on the Mac (`helper/` in the repo): TypeScript language server for the
 * editor and format on save; later Metro, adb and the emulator view. Lights up only when it answers.
 */
@Composable
private fun HelperCard(appVm: AppViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCodeApp
    val state by appVm.helper.collectAsState()
    val formatOnSave by app.serverStore.formatOnSave.collectAsState(initial = true)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { appVm.refreshHelper() }

    val (title, detail) = when (val s = state) {
        is dev.opencode.mobile.data.net.HelperState.Ready -> "Connected" to "${s.info.hostname} · helper ${s.info.version}"
        is dev.opencode.mobile.data.net.HelperState.Unpaired -> "Found on ${s.info.hostname}" to "Pair this phone to use TypeScript completions, errors and format on save."
        is dev.opencode.mobile.data.net.HelperState.Pairing -> "Pairing…" to "Check the Mac: allow the request if it shows ${s.code}."
        is dev.opencode.mobile.data.net.HelperState.Connecting -> "Connecting…" to s.info.hostname
        is dev.opencode.mobile.data.net.HelperState.Absent -> "Not running" to "Nothing answered at ${s.url}. On the Mac: cd helper && npm ci && npm start"
        is dev.opencode.mobile.data.net.HelperState.Failed -> "Not connected" to s.message
        dev.opencode.mobile.data.net.HelperState.Checking -> "Looking for the helper…" to ""
        dev.opencode.mobile.data.net.HelperState.Idle -> "No server selected" to ""
    }
    SettingsCard {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { if (detail.isNotBlank()) Text(detail) },
            leadingContent = { Icon(Icons.Filled.Computer, contentDescription = null) },
            trailingContent = {
                val c = dev.opencode.mobile.ui.theme.OcTheme.colors
                when (state) {
                    is dev.opencode.mobile.data.net.HelperState.Ready -> dev.opencode.mobile.ui.common.StatusPill("Live", c.green)
                    is dev.opencode.mobile.data.net.HelperState.Absent, is dev.opencode.mobile.data.net.HelperState.Failed -> dev.opencode.mobile.ui.common.StatusPill("Off", c.textSecondary)
                    else -> {}
                }
            },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
        (state as? dev.opencode.mobile.data.net.HelperState.Ready)?.let { ready ->
            // What this Mac can do, in words (the wire names are for the protocol).
            val names = mapOf(
                "lsp" to "TypeScript", "format" to "Format on save", "metro" to "Metro", "adb" to "Android devices",
                "screen" to "Device screen", "emulator" to "Emulators", "ios" to "iOS simulators", "simscreen" to "iOS screen",
            )
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                Modifier.padding(start = 56.dp, end = 16.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (f in ready.info.features) names[f]?.let { label ->
                    dev.opencode.mobile.ui.common.StatusPill(label, dev.opencode.mobile.ui.theme.OcTheme.colors.link, dot = false)
                }
            }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp)) {
            when (state) {
                is dev.opencode.mobile.data.net.HelperState.Unpaired -> androidx.compose.material3.TextButton(onClick = {
                    error = null
                    appVm.pairHelper(onCode = { code = it }, onDone = { err -> code = null; error = err })
                }) { Text("Pair") }
                is dev.opencode.mobile.data.net.HelperState.Ready, is dev.opencode.mobile.data.net.HelperState.Failed,
                is dev.opencode.mobile.data.net.HelperState.Connecting -> androidx.compose.material3.TextButton(onClick = { appVm.unpairHelper() }) { Text("Unpair") }
                else -> androidx.compose.material3.TextButton(onClick = { appVm.refreshHelper() }) { Text("Check again") }
            }
        }
        ListItem(
            headlineContent = { Text("Format on save") },
            supportingContent = { Text("Prettier, then ESLint --fix when the project has it (needs the helper)") },
            trailingContent = { Switch(checked = formatOnSave, onCheckedChange = { v -> scope.launch { app.serverStore.setFormatOnSave(v) } }) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
    }
    code?.let { shown ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text("Confirm on your Mac") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("Your Mac is asking whether to allow this phone. Press Allow there if it shows this code:")
                    Text(
                        shown,
                        style = MaterialTheme.typography.displayMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            },
            confirmButton = {},
        )
    }
}
