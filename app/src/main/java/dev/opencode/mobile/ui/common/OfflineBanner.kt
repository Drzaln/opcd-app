package dev.opencode.mobile.ui.common

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.ConnectionState
import dev.opencode.mobile.ConnectionStatus
import dev.opencode.mobile.data.net.DiagnosisKind
import dev.opencode.mobile.ui.theme.OcTheme

/**
 * Shown while the server can't be reached: what's wrong (from the connection check), what to do,
 * and that what's on screen is the copy saved on the phone. Renders nothing when connected.
 */
@Composable
fun OfflineBanner(
    state: ConnectionState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    savedNote: String? = "Showing the copy saved on this phone.",
) {
    if (state.status != ConnectionStatus.OFFLINE) return
    val c = OcTheme.colors
    val context = LocalContext.current
    val d = state.diagnosis
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(Ui.cardShape)
            .background(c.orange.copy(alpha = 0.10f))
            .border(1.dp, c.orange.copy(alpha = 0.35f), Ui.cardShape)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = c.orange, modifier = Modifier.padding(top = 1.dp).size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(d?.title ?: "Can't reach the server", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                val advice = d?.advice ?: state.error
                if (!advice.isNullOrBlank()) Text(advice, fontSize = 12.sp, lineHeight = 17.sp, color = c.textBody, modifier = Modifier.padding(top = 2.dp))
                if (savedNote != null) Text(savedNote, fontSize = 12.sp, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (d?.kind == DiagnosisKind.TAILSCALE_OFF) {
                val launch = context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
                if (launch != null) TextButton(onClick = { runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Open Tailscale") }
            }
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
