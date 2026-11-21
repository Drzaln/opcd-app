package dev.opencode.mobile.ui.common

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.theme.OcTheme

/**
 * The size and visibility of one resizable pane (wide layouts: session list, file tree, device column,
 * bottom panel), remembered across restarts per [key] and per orientation — a tablet keeps a different
 * layout in portrait and landscape.
 */
@Stable
class PaneState internal constructor(
    private val prefs: android.content.SharedPreferences?,
    private val prefKey: String,
    val default: Dp,
    val min: Dp,
    val max: Dp,
    private val defaultVisible: Boolean,
) {
    var size by mutableStateOf(prefs?.getFloat("$prefKey.size", Float.NaN)?.takeUnless { it.isNaN() }?.dp ?: default)
        private set
    var visible by mutableStateOf(prefs?.getBoolean("$prefKey.visible", defaultVisible) ?: defaultVisible)
        private set

    /** Size while a drag is in progress, before clamping (can go below [min]: that's how a pane hides). */
    private var dragRaw = 0f
    /** The size when the drag began: a pane dragged closed reopens at this size, not at [min]. */
    private var sizeAtStart = default

    fun startDrag() { sizeAtStart = size; dragRaw = if (visible) size.value else 0f }

    /** [delta] in dp, positive = the pane grows. Dragging well below [min] hides it; dragging out shows it. */
    fun drag(delta: Float, limit: Dp = max) {
        dragRaw += delta
        if (dragRaw < min.value * HIDE_FRACTION) {
            visible = false
            size = sizeAtStart
        } else {
            visible = true
            size = dragRaw.dp.coerceIn(min, minOf(max, limit).coerceAtLeast(min))
        }
    }

    fun endDrag() = save()

    fun toggle() { visible = !visible; save() }

    fun show(value: Boolean) { if (visible != value) { visible = value; save() } }

    fun reset() { size = default; visible = true; save() }

    private fun save() {
        prefs?.edit()?.putFloat("$prefKey.size", size.value)?.putBoolean("$prefKey.visible", visible)?.apply()
    }

    private companion object { const val HIDE_FRACTION = 0.5f }
}

@Composable
fun rememberPaneState(key: String, default: Dp, min: Dp, max: Dp, defaultVisible: Boolean = true): PaneState {
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val prefKey = "$key.${if (landscape) "land" else "port"}"
    return remember(prefKey) {
        val prefs = runCatching { context.getSharedPreferences("pane_layout", Context.MODE_PRIVATE) }.getOrNull()
        PaneState(prefs, prefKey, default, min, max, defaultVisible)
    }
}

/**
 * Two panes side by side with a draggable divider, IDE-style. [state] sizes the pane at the start
 * (or the end when [sizedAtEnd]); [rest] takes what's left but never less than [restMin]. The divider
 * is a 1 dp line with a 24 dp touch strip over it; double-tap resets the size. Dragging the pane
 * narrow hides it, leaving a slim edge you tap or drag to bring it back.
 */
@Composable
fun HorizontalSplit(
    state: PaneState,
    modifier: Modifier = Modifier,
    sizedAtEnd: Boolean = false,
    restMin: Dp = 320.dp,
    paneLabel: String = "pane",
    sized: @Composable () -> Unit,
    rest: @Composable () -> Unit,
) {
    val c = OcTheme.colors
    BoxWithConstraints(modifier) {
        val limit = (maxWidth - restMin - 1.dp).coerceAtLeast(state.min)
        val width = state.size.coerceAtMost(limit)
        val sign = if (sizedAtEnd) -1f else 1f
        val density = LocalDensity.current
        val interaction = remember { MutableInteractionSource() }
        val dragging by interaction.collectIsDraggedAsState()
        val dragState = rememberDraggableState { px -> state.drag(sign * with(density) { px.toDp().value }, limit) }

        Row(Modifier.fillMaxSize()) {
            if (!sizedAtEnd) SizedOrEdge(state, width, sizedAtEnd, paneLabel, dragState, sized)
            Box(Modifier.weight(1f).fillMaxHeight()) { rest() }
            if (sizedAtEnd) SizedOrEdge(state, width, sizedAtEnd, paneLabel, dragState, sized)
        }
        if (state.visible) {
            // The touch strip over the divider, drawn last so it gets the touches before either pane.
            val lineX = if (sizedAtEnd) maxWidth - width - 1.dp else width
            Box(
                Modifier
                    .offset(x = lineX - HANDLE_TOUCH / 2)
                    .width(HANDLE_TOUCH)
                    .fillMaxHeight()
                    .semantics { contentDescription = "Resize $paneLabel" }
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Horizontal,
                        interactionSource = interaction,
                        onDragStarted = { state.startDrag() },
                        onDragStopped = { state.endDrag() },
                    )
                    .pointerInput(state) { detectTapGestures(onDoubleTap = { state.reset() }) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.width(if (dragging) 3.dp else 1.dp).fillMaxHeight()
                        .background(if (dragging) c.link else c.border),
                )
            }
        }
    }
}

@Composable
private fun SizedOrEdge(
    state: PaneState,
    width: Dp,
    sizedAtEnd: Boolean,
    paneLabel: String,
    dragState: androidx.compose.foundation.gestures.DraggableState,
    content: @Composable () -> Unit,
) {
    val c = OcTheme.colors
    if (state.visible) {
        Box(Modifier.width(width).fillMaxHeight()) { content() }
        Box(Modifier.width(1.dp).fillMaxHeight()) // room for the divider line drawn by the handle
    } else {
        // Hidden: a slim edge with a chevron — tap to show, or drag it out.
        Box(
            Modifier
                .width(HIDDEN_EDGE)
                .fillMaxHeight()
                .background(c.surface)
                .semantics { contentDescription = "Show $paneLabel" }
                .clickable { state.show(true) }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = { state.startDrag() },
                    onDragStopped = { state.endDrag() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (sizedAtEnd) Icons.AutoMirrored.Outlined.KeyboardArrowLeft else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = c.textSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
    }
}

/** A small grip drawn on a draggable panel header (the bottom panel's tab strip). */
@Composable
fun DragGrip(modifier: Modifier = Modifier, active: Boolean = false) {
    val c = OcTheme.colors
    Box(
        modifier.width(32.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
            .background(if (active) c.link else c.border),
    )
}

/** Turns vertical drags on a panel header into height changes for [state] (positive dp = taller). */
@Composable
fun Modifier.verticalResize(state: PaneState, limit: Dp, interaction: MutableInteractionSource? = null, onShow: () -> Unit = {}, onHide: () -> Unit = {}): Modifier {
    val density = LocalDensity.current
    val dragState = rememberDraggableState { px ->
        val before = state.visible
        state.drag(-with(density) { px.toDp().value }, limit)
        if (state.visible != before) { if (state.visible) onShow() else onHide() }
    }
    return this.draggable(
        state = dragState,
        orientation = Orientation.Vertical,
        interactionSource = interaction,
        onDragStarted = { state.startDrag() },
        onDragStopped = { state.endDrag() },
    )
}

private val HANDLE_TOUCH = 24.dp
private val HIDDEN_EDGE = 14.dp
