package dev.opencode.mobile.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.opencode.mobile.ui.theme.OcTheme

enum class AppTab { CODE, SESSIONS, TERMINAL, SETTINGS }

/** Top-level navigation: Code · Sessions · Terminal · Settings (selected = accent-soft pill, link-colored icon). */
@Composable
fun AppBottomBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    val c = OcTheme.colors
    NavigationBar(containerColor = c.bg) {
        fun item(tab: AppTab, icon: ImageVector, label: String): @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            NavigationBarItem(
                selected = selected == tab,
                onClick = { if (selected != tab) onSelect(tab) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = c.link,
                    selectedTextColor = c.textPrimary,
                    indicatorColor = c.accentSoft,
                    unselectedIconColor = c.textSecondary,
                    unselectedTextColor = c.textSecondary,
                ),
            )
        }
        item(AppTab.CODE, Icons.Outlined.Code, "Code")()
        item(AppTab.SESSIONS, Icons.AutoMirrored.Outlined.Chat, "Sessions")()
        item(AppTab.TERMINAL, Icons.Outlined.Terminal, "Terminal")()
        item(AppTab.SETTINGS, Icons.Outlined.Settings, "Settings")()
    }
}

/** The same destinations as a 72 dp side rail, for short (landscape phone) layouts. */
@Composable
fun AppNavRail(selected: AppTab, onSelect: (AppTab) -> Unit, footer: @Composable () -> Unit = {}) {
    val c = OcTheme.colors
    androidx.compose.material3.NavigationRail(containerColor = c.bg) {
        val items = listOf(
            Triple(AppTab.CODE, Icons.Outlined.Code, "Code"),
            Triple(AppTab.SESSIONS, Icons.AutoMirrored.Outlined.Chat, "Sessions"),
            Triple(AppTab.TERMINAL, Icons.Outlined.Terminal, "Terminal"),
            Triple(AppTab.SETTINGS, Icons.Outlined.Settings, "Settings"),
        )
        for ((tab, icon, label) in items) {
            androidx.compose.material3.NavigationRailItem(
                selected = selected == tab,
                onClick = { if (selected != tab) onSelect(tab) },
                icon = { Icon(icon, contentDescription = label) },
                colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                    selectedIconColor = c.link,
                    indicatorColor = c.accentSoft,
                    unselectedIconColor = c.textSecondary,
                ),
            )
        }
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.weight(1f))
        footer()
    }
}
