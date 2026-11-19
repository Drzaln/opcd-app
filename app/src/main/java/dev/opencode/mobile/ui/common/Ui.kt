package dev.opencode.mobile.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.ui.theme.OcTheme

/**
 * The app's small design system: one look for empty screens, segmented choices, status pills and
 * section headers, so every screen reads the same. Sizes: 16 dp screen gutter, 12 dp card radius,
 * 8 dp control radius, 40 dp minimum touch height for compact controls.
 */
object Ui {
    val gutter = 16.dp
    val cardShape = RoundedCornerShape(12.dp)
    val controlShape = RoundedCornerShape(8.dp)
}

/**
 * A centered empty / not-ready state: tinted icon, title, one or two lines of help and an optional
 * action. Use it instead of a bare line of grey text.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    tint: Color = OcTheme.colors.link,
) {
    val c = OcTheme.colors
    Column(
        modifier.padding(horizontal = 32.dp, vertical = 24.dp).widthIn(max = 420.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = c.textSecondary, textAlign = TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

/**
 * A segmented control (tabs inside a panel, filter modes): a rounded track with the selected
 * segment raised. [labels] and [badges] line up by index; a badge shows a small count or dot.
 */
@Composable
fun Segmented(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    badges: List<String?> = emptyList(),
) {
    val c = OcTheme.colors
    Row(
        modifier.clip(Ui.controlShape).background(c.surfaceVariant.copy(alpha = 0.6f)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(if (on) c.bg else Color.Transparent, label = "segment")
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).background(bg).clickable { onSelect(i) }
                    .heightIn(min = 34.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) c.textPrimary else c.textSecondary,
                    maxLines = 1,
                )
                badges.getOrNull(i)?.let { b ->
                    Spacer(Modifier.size(6.dp))
                    if (b.isEmpty()) Box(Modifier.size(6.dp).clip(CircleShape).background(c.warning))
                    else Text(
                        b, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.errorText,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.errorBg).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** A small rounded status label with a colored dot ("Running", "Booted", "slim"). */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier, dot: Boolean = true) {
    Row(
        modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.size(5.dp))
        }
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

/** An uppercase section label above a group of cards, with an optional trailing action. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = OcTheme.colors
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = c.textSecondary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke(this)
    }
}

/** A thin bordered surface with the card radius: groups related rows. */
@Composable
fun OcCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = OcTheme.colors
    Column(modifier.fillMaxWidth().clip(Ui.cardShape).background(c.surface).border(1.dp, c.border, Ui.cardShape)) { content() }
}

/** A dismissible banner (error or info) shown under a top bar or panel header. */
@Composable
fun Banner(text: String, error: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = if (error) c.errorText else c.textBody,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(Ui.controlShape)
            .background(if (error) c.errorBg else c.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
