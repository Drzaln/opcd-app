package dev.opencode.mobile.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ConnectionState
import dev.opencode.mobile.ConnectionStatus
import dev.opencode.mobile.ui.theme.OcTheme

@Composable
fun ConnectionIndicator(
    state: ConnectionState,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    val color = when (state.status) {
        ConnectionStatus.CONNECTED -> OcTheme.colors.green
        ConnectionStatus.CHECKING -> OcTheme.colors.orange
        ConnectionStatus.OFFLINE -> OcTheme.colors.red
    }
    val label = when (state.status) {
        ConnectionStatus.CONNECTED -> "Live"
        ConnectionStatus.CHECKING -> "Checking…"
        ConnectionStatus.OFFLINE -> "Offline"
    }

    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .clickable { showDialog = true }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Connection") },
            text = {
                Column {
                    Text(label, color = color, style = MaterialTheme.typography.titleSmall)
                    state.version?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(6.dp))
                        Text("opencode $it", style = MaterialTheme.typography.bodySmall)
                    }
                    state.latencyMs?.let {
                        Spacer(Modifier.height(6.dp))
                        Text("${it}ms round-trip", style = MaterialTheme.typography.bodySmall, color = OcTheme.colors.textSecondary)
                    }
                    state.error?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = OcTheme.colors.red)
                    }
                    if (state.checkedAt > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text("checked ${relativeSeconds(state.checkedAt)}", style = MaterialTheme.typography.labelSmall, color = OcTheme.colors.textSecondary)
                    }
                }
            },
            confirmButton = { TextButton(onClick = onRecheck) { Text("Recheck") } },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Close") } },
        )
    }
}

private fun relativeSeconds(at: Long): String {
    val seconds = (System.currentTimeMillis() - at) / 1000
    return when {
        seconds < 5 -> "just now"
        seconds < 60 -> "${seconds}s ago"
        else -> "${seconds / 60}m ago"
    }
}
