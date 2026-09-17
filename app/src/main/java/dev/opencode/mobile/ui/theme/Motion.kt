package dev.opencode.mobile.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween

/** Shared motion tokens so animations feel consistent across screens. */
object Motion {
    const val FAST = 140
    const val MEDIUM = 240
    const val SLOW = 400

    fun medium() = tween<Float>(durationMillis = MEDIUM, easing = FastOutSlowInEasing)
    fun fast() = tween<Float>(durationMillis = FAST)
}
