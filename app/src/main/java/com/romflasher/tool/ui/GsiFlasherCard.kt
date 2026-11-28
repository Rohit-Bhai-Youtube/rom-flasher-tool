package com.romflasher.tool.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.romflasher.tool.ui.theme.*
import com.romflasher.tool.viewmodel.UiState
import java.io.File

/**
 * Bulletproof 1-Click GSI (Generic System Image) Flasher Card on the Main Screen.
 * Redesigned in compact Apple Liquid Glass styling with side-by-side selectors & horizontal toggle pills.
 */
@Composable
fun GsiFlasherOptionCard(
    uiState: UiState,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    selectedGsiFile: File?,
    onSelectGsiClick: () -> Unit,
    onClearGsiClick: () -> Unit,
    selectedCustomVbmetaFile: File? = null,
    onSelectCustomVbmetaClick: () -> Unit = {},
    onClearCustomVbmetaClick: () -> Unit = {},
    disableVbmeta: Boolean,
    onToggleDisableVbmeta: (Boolean) -> Unit,
    wipeUserdata: Boolean,
    onToggleWipeUserdata: (Boolean) -> Unit,
    freeSuperSpace: Boolean = true,
    onToggleFreeSuperSpace: (Boolean) -> Unit = {},
    rebootFastbootd: Boolean,
    onToggleRebootFastbootd: (Boolean) -> Unit,
    onRebootToFastbootd: () -> Unit = {},
    onFlashGsi: () -> Unit
) {
    val gsiAccent = Color(0xFF6C5CE7)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(
                glowColor = gsiAccent,
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
                    icon = Icons.Rounded.Android,
                    accent = Color(0xFF30D158),
                    size = 42.dp,
                    iconSize = 24.dp
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr("1-Click GSI Flasher (Bulletproof)", "1-Click GSI Flasher (Bulletproof)"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = tr("AOSP / Pixel / Lineage GSI | Qualcomm & MediaTek Safe", "GSI flash karo | Qualcomm & MediaTek Safe"),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF8E8E93)
                    )
                }

                if (selectedGsiFile != null) {
                    CardStatusBadge(
                        text = "READY",
                        accent = Color(0xFF30D158),
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }

                val gsiArrowRot by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isExpanded) 180f else 0f,
                    animationSpec = androidx.compose.animation.core.spring(
                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                        stiffness = 420f
                    ),
                    label = "gsiArrowRot"
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.rotate(gsiArrowRot)
                )
            }

            ExpandableContent(isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color(0x336C5CE7))
                Spacer(modifier = Modifier.height(14.dp))

                // Side-by-Side Left & Right File Selectors (GSI on Left, Stock VBMeta on Right)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: GSI File Picker or Selected Pill
                    Box(modifier = Modifier.weight(1f)) {
                        if (selectedGsiFile != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color(0x356C5CE7), Color(0x1A6C5CE7))
                                        )
                                    )
                                    .border(1.2.dp, Color(0x66A29BFE), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.InsertDriveFile,
                                    contentDescription = null,
                                    tint = Color(0xFFA29BFE),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedGsiFile.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${selectedGsiFile.length() / (1024 * 1024)} MB",
                                        color = Color(0xFFA29BFE),
                                        fontSize = 10.sp
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(Color(0x22FF453A))
                                        .clickable { onClearGsiClick() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "Clear",
                                        tint = Color(0xFFFF6B6B),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        } else {
                            LiquidGlassCapsuleButton(
                                onClick = onSelectGsiClick,
                                gradientColors = listOf(Color(0xFF6C5CE7), Color(0xFF4834D4)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.FolderOpen,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = tr("Select GSI", "GSI Chuno"),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    // Right: Stock VBMeta Picker or Selected Pill
                    Box(modifier = Modifier.weight(1f)) {
                        if (selectedCustomVbmetaFile != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color(0x3500E676), Color(0x1A00E676))
                                        )
                                    )
                                    .border(1.2.dp, Color(0x6600E676), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.VerifiedUser,
                                    contentDescription = null,
                                    tint = Color(0xFF00E676),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedCustomVbmetaFile.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Stock VBMeta",
                                        color = Color(0xFF00E676),
                                        fontSize = 10.sp
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(Color(0x22FF453A))
                                        .clickable { onClearCustomVbmetaClick() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "Clear",
                                        tint = Color(0xFFFF6B6B),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        } else {
                            LiquidGlassCapsuleButton(
                                onClick = onSelectCustomVbmetaClick,
                                gradientColors = listOf(Color(0x28FFFFFF), Color(0x18FFFFFF)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.Shield,
                                        contentDescription = null,
                                        tint = Color(0xFFA29BFE),
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = tr("VBMeta (Opt)", "VBMeta (Opt)"),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFE2E2EA)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Horizontal Row of 4 Apple Toggle Pills (1-Word Concise Descriptions)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppleTogglePill(
                        checked = disableVbmeta,
                        onToggle = onToggleDisableVbmeta,
                        label = "VBMeta",
                        modifier = Modifier.weight(1f)
                    )
                    AppleTogglePill(
                        checked = wipeUserdata,
                        onToggle = onToggleWipeUserdata,
                        label = "Wipe",
                        modifier = Modifier.weight(1f)
                    )
                    AppleTogglePill(
                        checked = freeSuperSpace,
                        onToggle = onToggleFreeSuperSpace,
                        label = "Super",
                        modifier = Modifier.weight(1f)
                    )
                    AppleTogglePill(
                        checked = rebootFastbootd,
                        onToggle = onToggleRebootFastbootd,
                        label = "FastbootD",
                        modifier = Modifier.weight(1.18f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Flash Button (Apple Liquid Glass Capsule Button)
                LiquidGlassCapsuleButton(
                    onClick = onFlashGsi,
                    enabled = selectedGsiFile != null && !uiState.isFlashingRom,
                    gradientColors = listOf(Color(0xFF6C5CE7), Color(0xFF8C7AE6)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Rounded.RocketLaunch,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = tr("1-CLICK FLASH GSI TO SYSTEM", "1-CLICK GSI FLASH KARO"),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact Apple-style frosted toggle pill capsule with active glowing indicator dot.
 */
@Composable
private fun AppleTogglePill(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFFA29BFE)
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (checked) {
                    Brush.verticalGradient(
                        listOf(accentColor.copy(alpha = 0.32f), accentColor.copy(alpha = 0.14f))
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(Color(0x18FFFFFF), Color(0x0CFFFFFF))
                    )
                }
            )
            .border(
                width = 1.2.dp,
                brush = if (checked) {
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.70f), accentColor.copy(alpha = 0.85f))
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.20f), Color(0x10FFFFFF))
                    )
                },
                shape = RoundedCornerShape(18.dp)
            )
            .clickable { onToggle(!checked) }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (checked) accentColor else Color(0xFF6E6E7A))
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium,
                color = if (checked) Color.White else Color(0xFF9E9EA5),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
