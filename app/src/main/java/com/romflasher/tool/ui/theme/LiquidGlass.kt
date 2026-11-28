package com.romflasher.tool.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * VisionOS / iOS-style Liquid Glass design primitives shared across cards, dialogs, and controls.
 */
object LiquidGlass {

    val CardShape: Shape = RoundedCornerShape(22.dp)

    val InnerBevelSheen: Brush = Brush.verticalGradient(
        0.00f to Color.White.copy(alpha = 0.22f),
        0.35f to Color.White.copy(alpha = 0.06f),
        1.00f to Color.Transparent
    )

    @Composable
    fun rememberGlassFillBrush(): Brush = remember {
        Brush.verticalGradient(
            0.00f to Color(0xF0111624),
            0.35f to Color(0xEC0B0F1A),
            0.75f to Color(0xF0080B13),
            1.00f to Color(0xF506080F)
        )
    }

    @Composable
    fun rememberSpecularRimBrush(
        glowColors: List<Color> = listOf(Color(0xFF00E5FF))
    ): Brush {
        val primary = glowColors.firstOrNull() ?: Color(0xFF00E5FF)
        val secondary = glowColors.getOrElse(1) { primary }
        return remember(glowColors) {
            Brush.verticalGradient(
                0.00f to Color.White.copy(alpha = 0.85f),
                0.18f to Color.White.copy(alpha = 0.35f),
                0.55f to primary.copy(alpha = 0.50f),
                0.85f to secondary.copy(alpha = 0.30f),
                1.00f to Color.White.copy(alpha = 0.14f)
            )
        }
    }
}

fun Modifier.liquidGlass(
    glowColors: List<Color>,
    shape: Shape = LiquidGlass.CardShape,
    borderWidth: Dp = 1.6.dp
): Modifier {
    val palette = if (glowColors.isEmpty()) listOf(Color(0xFF00E5FF)) else glowColors
    val primary = palette.first()
    val secondary = palette.getOrElse(1) { primary }

    val glassFill = Brush.verticalGradient(
        0.00f to Color(0xF2121827),
        0.25f to Color(0xEE0C101C),
        0.70f to Color(0xF2080B14),
        1.00f to Color(0xF6060810)
    )

    val rimBrush = Brush.linearGradient(
        colors = buildList {
            add(Color.White.copy(alpha = 0.82f))
            add(primary.copy(alpha = 0.58f))
            if (palette.size > 1) {
                addAll(palette.drop(1).map { it.copy(alpha = 0.48f) })
            } else {
                add(secondary.copy(alpha = 0.28f))
            }
            add(Color.White.copy(alpha = 0.14f))
        }
    )

    return this
        .clip(shape)
        .background(glassFill)
        .drawWithCache {
            val radialBrush = Brush.radialGradient(
                colors = listOf(
                    primary.copy(alpha = 0.13f),
                    secondary.copy(alpha = 0.04f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.18f, 0f),
                radius = (size.maxDimension * 0.78f).coerceAtLeast(1f)
            )
            val sheenBrush = Brush.verticalGradient(
                0.00f to Color.White.copy(alpha = 0.16f),
                0.28f to Color.White.copy(alpha = 0.04f),
                1.00f to Color.Transparent
            )
            val sheenSize = size.copy(height = size.height * 0.34f)
            onDrawBehind {
                drawRect(brush = radialBrush)
                drawRect(brush = sheenBrush, size = sheenSize)
            }
        }
        .border(borderWidth, rimBrush, shape)
}

fun Modifier.liquidGlass(
    glowColor: Color,
    shape: Shape = LiquidGlass.CardShape,
    borderWidth: Dp = 1.6.dp
): Modifier = liquidGlass(
    glowColors = listOf(glowColor),
    shape = shape,
    borderWidth = borderWidth
)

@Composable
fun LiquidGlassButton(
    onClick: () -> Unit,
    glowColor: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    shape: Shape = LiquidGlass.CardShape,
    borderWidth: Dp = 1.6.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 460f),
        label = "liquidGlassButtonScale"
    )

    val fillBrush = if (filled) {
        Brush.verticalGradient(
            listOf(
                glowColor.copy(alpha = 0.26f),
                glowColor.copy(alpha = 0.12f),
                Color(0xE0090D16)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0xEE101522),
                Color(0xEC090D16)
            )
        )
    }

    val rimBrush = Brush.verticalGradient(
        0.00f to Color.White.copy(alpha = 0.72f),
        0.30f to glowColor.copy(alpha = 0.55f),
        0.80f to glowColor.copy(alpha = 0.25f),
        1.00f to Color.White.copy(alpha = 0.12f)
    )

    Box(
        modifier = modifier
            .scale(scale)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(fillBrush)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = if (filled) 0.20f else 0.10f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.20f, 0f),
                        radius = (size.maxDimension * 0.75f).coerceAtLeast(1f)
                    )
                )
                drawRect(
                    brush = LiquidGlass.InnerBevelSheen,
                    size = size.copy(height = size.height * 0.42f)
                )
            }
            .border(borderWidth, rimBrush, shape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
fun LiquidGlassCapsuleButton(
    onClick: () -> Unit,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val palette = if (gradientColors.isEmpty()) {
        listOf(Color(0xFF00E5FF), Color(0xFF007AFF))
    } else if (gradientColors.size == 1) {
        listOf(gradientColors.first(), gradientColors.first().copy(alpha = 0.75f))
    } else {
        gradientColors
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 460f),
        label = "liquidGlassCapsuleScale"
    )

    val capsuleShape = RoundedCornerShape(50)
    val primary = palette.first()

    Box(
        modifier = modifier
            .scale(scale)
            .alpha(if (enabled) 1f else 0.42f)
            .clip(capsuleShape)
            .background(Brush.horizontalGradient(palette))
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        0.00f to Color.White.copy(alpha = 0.30f),
                        0.45f to Color.White.copy(alpha = 0.06f),
                        1.00f to Color.Transparent
                    ),
                    size = size.copy(height = size.height * 0.55f)
                )
            }
            .border(
                width = 1.2.dp,
                brush = Brush.verticalGradient(
                    0.00f to Color.White.copy(alpha = 0.78f),
                    0.45f to primary.copy(alpha = 0.45f),
                    1.00f to Color.White.copy(alpha = 0.16f)
                ),
                shape = capsuleShape
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
fun AmbientFluidCanvas(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ambientFluid")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ambientFluidPhase"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val baseRadius = (maxOf(w, h) * 0.55f).coerceAtLeast(1f)

        val mintCenter = Offset(
            x = w * (0.20f + 0.08f * cos(phase)),
            y = h * (0.18f + 0.06f * sin(phase))
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x2200E5A8), Color.Transparent),
                center = mintCenter,
                radius = baseRadius * 0.72f
            ),
            radius = baseRadius * 0.72f,
            center = mintCenter
        )

        val azureCenter = Offset(
            x = w * (0.82f + 0.08f * sin(phase * 0.9f)),
            y = h * (0.42f + 0.08f * cos(phase * 0.9f))
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x2000B8FF), Color.Transparent),
                center = azureCenter,
                radius = baseRadius * 0.68f
            ),
            radius = baseRadius * 0.68f,
            center = azureCenter
        )

        val violetCenter = Offset(
            x = w * (0.38f + 0.10f * cos(phase + 2.1f)),
            y = h * (0.82f + 0.07f * sin(phase + 2.1f))
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x227C4DFF), Color.Transparent),
                center = violetCenter,
                radius = baseRadius * 0.75f
            ),
            radius = baseRadius * 0.75f,
            center = violetCenter
        )
    }
}

private data class DockTab(
    val id: Int,
    val label: String,
    val icon: ImageVector,
    val accentColor: Color
)

private const val LIQUID_GLASS_LENS_AGSL = """
    uniform shader content;
    uniform float2 size;
    uniform float cornerRadius;
    uniform float refractionHeight;
    uniform float refractionAmount;
    uniform float dispersion;
    uniform float magnification;

    float sdRoundedBox(float2 p, float2 b, float r) {
        float2 q = abs(p) - b + r;
        return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
    }

    half4 main(float2 fragCoord) {
        float2 halfSize = size * 0.5;
        float2 p = fragCoord - halfSize;
        float d = sdRoundedBox(p, halfSize, cornerRadius);
        if (d > 0.0) {
            return content.eval(fragCoord);
        }
        float2 magCoord = halfSize + p / max(magnification, 0.5);
        float edgeDist = clamp(-d / max(refractionHeight, 1.0), 0.0, 1.0);
        float bend = pow(1.0 - edgeDist, 2.2);
        float2 dir = length(p) > 0.001 ? normalize(p) : float2(0.0, 0.0);
        float shift = bend * refractionAmount;
        float2 uvR = clamp(magCoord - dir * (shift * (1.0 + dispersion)), float2(0.0), size);
        float2 uvG = clamp(magCoord - dir * shift, float2(0.0), size);
        float2 uvB = clamp(magCoord - dir * (shift * (1.0 - dispersion)), float2(0.0), size);
        half r = content.eval(uvR).r;
        half g = content.eval(uvG).g;
        half b = content.eval(uvB).b;
        half a = content.eval(uvG).a;
        return half4(r, g, b, a);
    }
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun rememberLiquidGlassRenderEffect(
    widthPx: Float,
    heightPx: Float,
    cornerRadiusPx: Float,
    blurRadius: Float = 14f,
    refractionAmount: Float = 38f,
    dispersion: Float = 0.45f,
    magnification: Float = 1.06f
): ComposeRenderEffect? {
    if (widthPx <= 1f || heightPx <= 1f) return null
    return try {
        val shader = RuntimeShader(LIQUID_GLASS_LENS_AGSL).apply {
            setFloatUniform("size", widthPx, heightPx)
            setFloatUniform("cornerRadius", cornerRadiusPx)
            setFloatUniform("refractionHeight", (heightPx * 0.44f).coerceAtLeast(12f))
            setFloatUniform("refractionAmount", refractionAmount)
            setFloatUniform("dispersion", dispersion)
            setFloatUniform("magnification", magnification)
        }
        val blur = AndroidRenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
        val lens = AndroidRenderEffect.createRuntimeShaderEffect(shader, "content")
        AndroidRenderEffect.createChainEffect(blur, lens).asComposeRenderEffect()
    } catch (_: Throwable) {
        AndroidRenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP).asComposeRenderEffect()
    }
}

@Composable
fun LiquidGlassDockBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdropLayer: GraphicsLayer? = null
) {
    val tabs = remember {
        listOf(
            DockTab(0, "Flashing", Icons.Rounded.FlashOn, Color(0xFF38BDF8)),
            DockTab(1, "Root", Icons.Rounded.Security, Color(0xFF34D399)),
            DockTab(2, "Tools", Icons.Rounded.Build, Color(0xFFC084FC)),
            DockTab(3, "Files", Icons.Rounded.FolderOpen, Color(0xFF2DD4BF))
        )
    }
    val activeAccent = tabs.firstOrNull { it.id == selectedTab }?.accentColor ?: Color(0xFF38BDF8)
    val density = LocalDensity.current
    val dockShape = RoundedCornerShape(34.dp)
    val cornerPx = with(density) { 34.dp.toPx() }

    var dockPosInRoot by remember { mutableStateOf(Offset.Zero) }
    var dockWidthPx by remember { mutableFloatStateOf(0f) }
    var dockHeightPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var dragTabFloat by remember { mutableFloatStateOf(selectedTab.toFloat()) }
    var lastHapticTab by remember { mutableStateOf(selectedTab) }

    val animatedTabIndex by animateFloatAsState(
        targetValue = if (isDragging) dragTabFloat else selectedTab.toFloat(),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 340f),
        label = "liquidLensSlide"
    )

    val lensRenderEffect = remember(dockWidthPx, dockHeightPx) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                rememberLiquidGlassRenderEffect(dockWidthPx, dockHeightPx, cornerPx)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                AndroidRenderEffect.createBlurEffect(26f, 26f, Shader.TileMode.CLAMP).asComposeRenderEffect()
            else -> null
        }
    }

    // True Apple Liquid Glass Dock (Translucent Backdrop Blur + AGSL Lens Refraction + Sliding Glass Pill)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(66.dp)
            .onGloballyPositioned { coords ->
                dockPosInRoot = coords.positionInRoot()
                dockWidthPx = coords.size.width.toFloat()
                dockHeightPx = coords.size.height.toFloat()
            }
            .shadow(
                elevation = 24.dp,
                shape = dockShape,
                spotColor = activeAccent.copy(alpha = 0.28f),
                ambientColor = Color.Black.copy(alpha = 0.55f)
            )
            .clip(dockShape)
    ) {
        // Layer 1: Live refracted & blurred backdrop behind the capsule
        if (backdropLayer != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        renderEffect = lensRenderEffect
                        clip = true
                        shape = dockShape
                    }
                    .drawWithContent {
                        clipRect {
                            translate(left = -dockPosInRoot.x, top = -dockPosInRoot.y) {
                                drawLayer(backdropLayer)
                            }
                        }
                    }
            )
        }

        // Layer 2: Crystal-clear Apple iOS 26 / VisionOS Liquid Glass tint + diagonal specular rim
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.White.copy(alpha = 0.14f),
                        0.50f to Color(0x241E293B),
                        1.00f to Color.White.copy(alpha = 0.06f)
                    )
                )
                .drawBehind {
                    // Soft overhead glass refraction sheen
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            0.00f to Color.White.copy(alpha = 0.22f),
                            0.28f to Color.White.copy(alpha = 0.04f),
                            0.60f to Color.Transparent
                        ),
                        size = size.copy(height = size.height * 0.50f),
                        cornerRadius = CornerRadius(cornerPx)
                    )
                }
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        0.00f to Color.White.copy(alpha = 0.58f),
                        0.35f to Color.White.copy(alpha = 0.10f),
                        0.68f to Color.White.copy(alpha = 0.12f),
                        1.00f to Color.White.copy(alpha = 0.34f)
                    ),
                    shape = dockShape
                )
                .padding(5.dp)
        ) {
            // Layer 3: Sliding Liquid Glass Droplet Pill + Interactive Tabs
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(tabs.size) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                dragTabFloat = ((offset.x / w) * tabs.size - 0.5f)
                                    .coerceIn(0f, (tabs.size - 1).toFloat())
                                lastHapticTab = dragTabFloat.roundToInt().coerceIn(0, tabs.size - 1)
                                com.romflasher.tool.engine.feedback.AudioHapticEngine.playClickHaptic()
                            },
                            onDragEnd = {
                                val target = dragTabFloat.roundToInt().coerceIn(0, tabs.size - 1)
                                isDragging = false
                                if (target != selectedTab) {
                                    com.romflasher.tool.engine.feedback.AudioHapticEngine.playClickHaptic()
                                }
                                onTabSelected(target)
                            },
                            onDragCancel = { isDragging = false },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                dragTabFloat = ((change.position.x / w) * tabs.size - 0.5f)
                                    .coerceIn(0f, (tabs.size - 1).toFloat())
                                val hovered = dragTabFloat.roundToInt().coerceIn(0, tabs.size - 1)
                                if (hovered != lastHapticTab) {
                                    lastHapticTab = hovered
                                    com.romflasher.tool.engine.feedback.AudioHapticEngine.playClickHaptic()
                                }
                            }
                        )
                    }
            ) {
                val tabWidth = maxWidth / tabs.size
                val lensWidth = tabWidth - 2.dp
                val lensOffsetPx = with(density) { (tabWidth * animatedTabIndex + 1.dp).toPx().roundToInt() }
                val lensWidthPx = with(density) { lensWidth.toPx() }
                val lensHeightPx = (dockHeightPx - with(density) { 10.dp.toPx() }).coerceAtLeast(1f)
                val lensCornerPx = with(density) { 28.dp.toPx() }
                val lensShape = RoundedCornerShape(28.dp)

                // Organic liquid squash & stretch when sliding/dragging between tabs
                val slideDelta = kotlin.math.abs(animatedTabIndex - selectedTab.toFloat()).coerceIn(0f, 1f)
                val dropletScaleX = if (isDragging) 1.08f else (1f + slideDelta * 0.14f)
                val dropletScaleY = if (isDragging) 0.94f else (1f - slideDelta * 0.06f)

                val dropletLensEffect = remember(lensWidthPx, lensHeightPx) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        rememberLiquidGlassRenderEffect(
                            widthPx = lensWidthPx,
                            heightPx = lensHeightPx,
                            cornerRadiusPx = lensCornerPx,
                            blurRadius = 6f,
                            refractionAmount = 30f,
                            dispersion = 0.42f,
                            magnification = 1.18f
                        )
                    } else null
                }

                // Sliding Magnifying Liquid Glass Droplet Pill (Kyant0 LiquidBottomTabs style)
                Box(
                    modifier = Modifier
                        .offset { IntOffset(lensOffsetPx, 0) }
                        .width(lensWidth)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = dropletScaleX
                            scaleY = dropletScaleY
                        }
                        .shadow(
                            elevation = 10.dp,
                            shape = lensShape,
                            spotColor = activeAccent.copy(alpha = 0.45f),
                            ambientColor = Color.Black.copy(alpha = 0.35f)
                        )
                        .clip(lensShape)
                ) {
                    if (backdropLayer != null && dropletLensEffect != null) {
                        val absLeftPx = dockPosInRoot.x + with(density) { 5.dp.toPx() } + lensOffsetPx
                        val absTopPx = dockPosInRoot.y + with(density) { 5.dp.toPx() }
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .graphicsLayer {
                                    renderEffect = dropletLensEffect
                                    clip = true
                                    shape = lensShape
                                }
                                .drawWithContent {
                                    clipRect {
                                        translate(left = -absLeftPx, top = -absTopPx) {
                                            drawLayer(backdropLayer)
                                        }
                                    }
                                }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    0.00f to Color.White.copy(alpha = 0.28f),
                                    0.40f to activeAccent.copy(alpha = 0.14f),
                                    1.00f to Color.White.copy(alpha = 0.08f)
                                )
                            )
                            .drawBehind {
                                // Glossy top droplet meniscus highlight
                                drawRoundRect(
                                    brush = Brush.verticalGradient(
                                        0.00f to Color.White.copy(alpha = 0.45f),
                                        0.35f to Color.White.copy(alpha = 0.06f),
                                        0.70f to Color.Transparent
                                    ),
                                    size = size.copy(height = size.height * 0.46f),
                                    cornerRadius = CornerRadius(lensCornerPx)
                                )
                            }
                            .border(
                                width = 1.2.dp,
                                brush = Brush.linearGradient(
                                    0.00f to Color.White.copy(alpha = 0.85f),
                                    0.42f to activeAccent.copy(alpha = 0.38f),
                                    0.75f to Color.White.copy(alpha = 0.15f),
                                    1.00f to Color.White.copy(alpha = 0.55f)
                                ),
                                shape = lensShape
                            )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEach { tab ->
                        val isSelected = selectedTab == tab.id
                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()

                        val scale by animateFloatAsState(
                            targetValue = if (isPressed) 0.92f else if (isSelected) 1.05f else 1.0f,
                            animationSpec = spring(dampingRatio = 0.65f, stiffness = 420f),
                            label = "dockItemScale_${tab.id}"
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .scale(scale)
                                .clip(lensShape)
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null
                                ) { onTabSelected(tab.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    tint = if (isSelected) Color.White else Color.White.copy(alpha = 0.62f),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = tab.label,
                                    fontSize = 10.5.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.62f),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
