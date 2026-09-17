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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.opencode.mobile.AppViewModel
import dev.opencode.mobile.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appVm: AppViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val themeMode by appVm.themeMode.collectAsState()
    val notificationsEnabled by appVm.notificationsEnabled.collectAsState()
    val updateMessage by appVm.updateMessage.collectAsState()
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
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
                        supportingContent = { Text(updateMessage ?: "Fetched from GitHub Releases") },
                        leadingContent = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clickable { appVm.checkForUpdate(force = true) },
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
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column { content() }
    }
}
