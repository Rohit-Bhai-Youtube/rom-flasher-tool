package com.romflasher.tool.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.romflasher.tool.engine.feedback.AudioHapticEngine
import kotlin.math.abs

/**
 * Clickable modifier without any ripple effect / white shine.
 * Eliminates the white highlight flash when tapping cards to expand them
 * and triggers crisp, flagship tactile haptic feedback.
 */
fun Modifier.noRippleClickable(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    this.clickable(
        enabled = enabled,
        indication = null,
        interactionSource = remember { MutableInteractionSource() },
        onClick = {
            AudioHapticEngine.playClickHaptic()
            onClick()
        }
    )
}

/**
 * Smoothly reveals the body of an expandable card.
 *
 * Previously each card used `Modifier.animateContentSize()`, which re-measures the
 * whole (often heavy) expanded content on every animation frame and can stutter.
 * [AnimatedVisibility] with an explicit vertical expand/shrink measures the content
 * once and only animates its clipped height, which reads noticeably smoother.
 *
 * The content grows downward from the card header ([Alignment.Top]) on expand and
 * shrinks back up towards it on collapse, so the header never appears to jump.
 *
 * Both directions deliberately share the *same* easing ([FastOutSlowInEasing]): a
 * decelerating curve reaches the end at zero velocity, so the card glides to a stop.
 * An accelerating curve (used previously for the collapse) still had speed when the
 * animation snapped shut, which read as a jitter on the way back up.
 */
val LocalMainScrollState = compositionLocalOf<ScrollState?> { null }
val LocalScrollContainerTop = compositionLocalOf { 0f }
private val LocalInsideAutoScroll = compositionLocalOf { false }

/**
 * Adaptive frame-locked expand + scroll helper.
 * Tracks [coordsProvider] every vsync frame while expanding so the card glides into
 * view (above the floating dock and without clipping the top header) at the exact
 * same time it expands.
 */
@Composable
private fun AdaptiveExpandScrollEffect(
    expanded: Boolean,
    coordsProvider: () -> LayoutCoordinates?
) {
    val scrollState = LocalMainScrollState.current
    val containerTop = LocalScrollContainerTop.current
    val density = LocalDensity.current

    LaunchedEffect(expanded) {
        if (expanded && scrollState != null) {
            val topPadPx = with(density) { 12.dp.toPx() }
            val dockClearancePx = with(density) { 145.dp.toPx() }
            val startNs = System.nanoTime()
            val durationNs = 420_000_000L

            while (System.nanoTime() - startNs < durationNs) {
                withFrameNanos { _ -> }
                val coords = coordsProvider()
                if (coords != null && coords.isAttached) {
                    val bounds = coords.boundsInRoot()
                    val viewportHeight = scrollState.viewportSize.toFloat()
                    if (viewportHeight > 0f && containerTop >= 0f) {
                        val visibleTop = containerTop + topPadPx
                        val visibleBottom = containerTop + viewportHeight - dockClearancePx
                        val maxShift = (bounds.top - visibleTop).coerceAtLeast(0f)

                        val targetDelta = when {
                            bounds.top < visibleTop -> bounds.top - visibleTop
                            bounds.bottom > visibleBottom -> minOf(bounds.bottom - visibleBottom, maxShift)
                            else -> 0f
                        }

                        if (abs(targetDelta) > 0.5f) {
                            val step = if (abs(targetDelta) > 6f) targetDelta * 0.35f else targetDelta
                            val nextScroll = (scrollState.value + step).toInt().coerceIn(0, scrollState.maxValue)
                            if (nextScroll != scrollState.value) {
                                scrollState.scrollTo(nextScroll)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Wraps any expandable card so expanding it smoothly scrolls the card into view
 * frame-by-frame in sync with its expand animation.
 */
@Composable
fun AutoScrollOnExpand(
    expanded: Boolean,
    content: @Composable () -> Unit
) {
    var cardCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    AdaptiveExpandScrollEffect(expanded = expanded, coordsProvider = { cardCoordinates })

    CompositionLocalProvider(LocalInsideAutoScroll provides true) {
        Box(
            modifier = Modifier.onGloballyPositioned { coordinates ->
                cardCoordinates = coordinates
            }
        ) {
            content()
        }
    }
}

@Composable
fun ExpandableContent(
    isExpanded: Boolean,
    enter: EnterTransition = expandVertically(
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 380f,
            visibilityThreshold = IntSize.VisibilityThreshold
        ),
        expandFrom = Alignment.Top
    ) + fadeIn(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)),
    exit: ExitTransition = shrinkVertically(
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 380f,
            visibilityThreshold = IntSize.VisibilityThreshold
        ),
        shrinkTowards = Alignment.Top
    ) + fadeOut(animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing)),
    content: @Composable ColumnScope.() -> Unit
) {
    val insideWrapper = LocalInsideAutoScroll.current
    var contentCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    if (!insideWrapper) {
        AdaptiveExpandScrollEffect(expanded = isExpanded, coordsProvider = { contentCoords })
    }

    AnimatedVisibility(
        visible = isExpanded,
        enter = enter,
        exit = exit
    ) {
        Column(
            modifier = Modifier
                .graphicsLayer { clip = true }
                .then(
                    if (!insideWrapper) {
                        Modifier.onGloballyPositioned { contentCoords = it }
                    } else {
                        Modifier
                    }
                ),
            content = content
        )
    }
}

/**
 * Compact pill badge for card headers showing selected file or ready status.
 */
@Composable
fun CardStatusBadge(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text = text,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold,
        color = accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(shape)
            .background(accent.copy(alpha = 0.16f))
            .border(0.8.dp, accent.copy(alpha = 0.45f), shape)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

/**
 * Tactile spring press bounce + haptic feedback for buttons and interactive cards.
 */
fun Modifier.pressBounceClickable(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 460f),
        label = "pressBounce"
    )
    this
        .scale(scale)
        .clickable(
            enabled = enabled,
            indication = null,
            interactionSource = interactionSource,
            onClick = {
                AudioHapticEngine.playClickHaptic()
                onClick()
            }
        )
}

/**
 * Smooth spring scale + slide-up entrance wrapper for dialogs and sheet-style popups.
 */
@Composable
fun SmoothDialogEntrance(content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) +
            slideInVertically(
                initialOffsetY = { it / 14 },
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f)
            ) +
            scaleIn(
                initialScale = 0.94f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f)
            )
    ) {
        content()
    }
}
