package com.romflasher.tool.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.romflasher.tool.ui.theme.*
import com.romflasher.tool.viewmodel.DeviceMode
import com.romflasher.tool.viewmodel.UiState

@Composable
fun AdvancedRebootOptionCard(
    uiState: UiState,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onExecuteReboot: (modeTarget: String, command: String) -> Unit
) {
    val isAdbActive = uiState.deviceMode == DeviceMode.ADB
    val isFastbootActive = uiState.deviceMode == DeviceMode.FASTBOOT
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f),
        label = "rebootArrowRotate"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(
                glowColor = Color(0xFFFF1744),
                shape = LiquidGlass.CardShape,
                borderWidth = 1.8.dp
            )
            .padding(14.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .noRippleClickable { onToggleExpand() }
            ) {
                LiquidGlassSquircleIcon(
                    icon = Icons.Rounded.RestartAlt,
                    accent = Color(0xFFFF3B30),
                    size = 42.dp,
                    iconSize = 24.dp
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr("Reboot Options", "Reboot options"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "System → Fastboot | Recovery | FastbootD",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF8E8E93)
                    )
                }

                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.rotate(arrowRotation)
                )
            }

            ExpandableContent(isExpanded) {
                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider(color = Color(0x33FF1744))
                Spacer(modifier = Modifier.height(16.dp))

                // --- SECTION 1: FROM ANDROID / RECOVERY (ADB) ---
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isAdbActive) Color(0xFF00FF66) else Color(0xFFFF1744))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = tr("FROM ANDROID / RECOVERY (ADB)", "ANDROID / RECOVERY SE"),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isAdbActive) Color(0xFF00FF66) else Color(0xFFFF8A80),
                        letterSpacing = 0.8.sp
                    )
                    if (isAdbActive) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(CONNECTED)",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00FF66)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RebootListRow(
                        title = "System → Fastboot",
                        isActive = true,
                        accentColor = Color(0xFFFF1744),
                        icon = Icons.Rounded.FlashOn,
                        modifier = Modifier.weight(1f),
                        compact = true,
                        onClick = { onExecuteReboot("ADB", "reboot bootloader") }
                    )
                    RebootListRow(
                        title = "System → Recovery",
                        isActive = true,
                        accentColor = Color(0xFFFF1744),
                        icon = Icons.Rounded.Healing,
                        modifier = Modifier.weight(1f),
                        compact = true,
                        onClick = { onExecuteReboot("ADB", "reboot recovery") }
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RebootListRow(
                        title = "Recovery → System",
                        isActive = true,
                        accentColor = Color(0xFFFF1744),
                        icon = Icons.Rounded.Smartphone,
                        modifier = Modifier.weight(1f),
                        compact = true,
                        onClick = { onExecuteReboot("ADB", "reboot") }
                    )
                    RebootListRow(
                        title = "System → FastbootD",
                        isActive = true,
                        accentColor = Color(0xFFFF1744),
                        icon = Icons.Rounded.Layers,
                        modifier = Modifier.weight(1f),
                        compact = true,
                        onClick = { onExecuteReboot("ADB", "reboot fastboot") }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color(0x22FFFFFF))
                Spacer(modifier = Modifier.height(12.dp))

                // --- SECTION 2: FROM FASTBOOT (BOOTLOADER) ---
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isFastbootActive) Color(0xFF00FF66) else Color(0xFF00E5FF))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = tr("FROM FASTBOOT (BOOTLOADER)", "FASTBOOT / BOOTLOADER SE"),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isFastbootActive) Color(0xFF00FF66) else Color(0xFF80D8FF),
                        letterSpacing = 0.8.sp
                    )
                    if (isFastbootActive) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(CONNECTED)",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00FF66)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RebootListRow(
                        title = "Fastboot → System",
                        isActive = true,
                        accentColor = Color(0xFF00FF66),
                        icon = Icons.Rounded.Smartphone,
                        modifier = Modifier.weight(1f),
                        compact = true,
                        onClick = { onExecuteReboot("FASTBOOT", "reboot") }
                    )
                    RebootListRow(
                        title = "Fastboot → Recovery",
                        isActive = true,
                        accentColor = Color(0xFF00FF66),
                        icon = Icons.Rounded.Healing,
                        modifier = Modifier.weight(1f),
                        compact = true,
                        onClick = { onExecuteReboot("FASTBOOT", "reboot-recovery") }
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RebootListRow(
                        title = "Fastboot → FastbootD",
                        isActive = true,
                        accentColor = Color(0xFF00FF66),
                        icon = Icons.Rounded.Layers,
                        modifier = Modifier.weight(1f),
                        compact = true,
                        onClick = { onExecuteReboot("FASTBOOT", "reboot-fastboot") }
                    )
                    RebootListRow(
                        title = "Reboot Bootloader",
                        isActive = true,
                        accentColor = Color(0xFF00FF66),
                        icon = Icons.Rounded.Refresh,
                        modifier = Modifier.weight(1f),
                        compact = true,
                        onClick = { onExecuteReboot("FASTBOOT", "reboot-bootloader") }
                    )
                }
            }
        }
    }
}

@Composable
fun RebootListRow(
    title: String,
    isActive: Boolean,
    accentColor: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && isActive) 0.96f else 1.0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 420f),
        label = "rebootRowScale"
    )

    val rimBrush = remember(accentColor, isPressed) {
        Brush.linearGradient(
            0.00f to Color.White.copy(alpha = if (isPressed) 0.85f else 0.55f),
            0.30f to accentColor.copy(alpha = if (isPressed) 0.90f else 0.65f),
            0.80f to accentColor.copy(alpha = 0.20f),
            1.00f to Color.Black.copy(alpha = 0.25f),
            start = Offset.Zero,
            end = Offset(600f, 400f)
        )
    }

    val fillBrush = remember(accentColor, isActive) {
        Brush.verticalGradient(
            0.0f to accentColor.copy(alpha = if (isActive) 0.20f else 0.06f),
            0.5f to Color(0x650B0E17),
            1.0f to Color(0x80080A10)
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(13.dp))
            .background(if (isActive) fillBrush else SolidColor(Color(0xFF14151C)))
            .drawBehind {
                if (isActive) {
                    drawRect(
                        brush = LiquidGlass.InnerBevelSheen,
                        size = size.copy(height = size.height * 0.45f)
                    )
                }
            }
            .border(
                1.3.dp,
                if (isActive) rimBrush else Brush.linearGradient(listOf(Color(0x22FFFFFF), Color(0x08FFFFFF))),
                RoundedCornerShape(13.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = isActive,
                onClick = onClick
            )
            .padding(
                horizontal = if (compact) 8.dp else 12.dp,
                vertical = if (compact) 9.dp else 11.dp
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(if (compact) 26.dp else 30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = if (isActive) 0.22f else 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isActive) accentColor else Color(0xFF8E8E93),
                        modifier = Modifier.size(if (compact) 16.dp else 18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(if (compact) 7.dp else 10.dp))
                Text(
                    text = title,
                    fontSize = if (compact) 10.5.sp else 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = if (compact) 2 else 1,
                    lineHeight = 12.sp,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = if (isActive) accentColor else Color(0xFF636366),
                modifier = Modifier.size(if (compact) 13.dp else 16.dp)
            )
        }
    }
}
