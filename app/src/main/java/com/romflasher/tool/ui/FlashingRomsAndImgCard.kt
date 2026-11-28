package com.romflasher.tool.ui

import java.io.File

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.romflasher.tool.ui.theme.*
import com.romflasher.tool.viewmodel.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashingRomsAndImgOptionCard(
    uiState: UiState,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    // Fastboot ROM card params
    onSelectRomFile: () -> Unit,
    onSelectRomFolder: () -> Unit,
    onSelectExistingFolder: (File) -> Unit = {},
    // Sideload card params
    onSelectSideloadFile: () -> Unit,
    onClearSelectedFile: () -> Unit,
    onFlashSideload: () -> Unit,
    // Custom Image card params
    onSelectPartition: (String) -> Unit,
    onSetDualSlotChecked: (Boolean) -> Unit,
    onRunCustomCommand: (String) -> Unit = {},
    onSelectImageClick: () -> Unit,
    onClearSelectedImage: () -> Unit,
    onBootCustomImage: (() -> Unit)? = null,
    onFlashCustomImage: () -> Unit
) {
    var dropdownExpanded by remember { mutableStateOf(false) }
    var showRomSourceDialog by remember { mutableStateOf(false) }

    val partitionsList = listOf(
        "boot", "init_boot", "vendor_boot", "recovery", "super",
        "dtbo", "vbmeta", "vbmeta_system", "vbmeta_vendor",
        "system", "vendor", "product", "odm", "cust", "userdata",
        "vendor_dlkm", "system_dlkm", "modem", "persist"
    )

    val tricolorGlow = Brush.horizontalGradient(
        listOf(
            Color(0xFF00FF66), // Fastboot Green
            Color(0xFF00E5FF), // Cyber Cyan
            Color(0xFFBF5AF2)  // Image Purple
        )
    )

    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = 420f
        ),
        label = "arrowRotate"
    )

    val blue = Color(0xFF64D2FF)
    val busySideloading = uiState.isSideloadingPackage || uiState.isPreparingSideloadFile
    val canSideload = uiState.selectedSideloadFile != null && !busySideloading && uiState.isConnected

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(
                glowColors = listOf(Color(0xFF00FF66), Color(0xFF00E5FF), Color(0xFFBF5AF2)),
                shape = LiquidGlass.CardShape,
                borderWidth = 1.8.dp
            )
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Master Header Row (Clickable)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .noRippleClickable { onToggleExpand() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    LiquidGlassSquircleIcon(
                        icon = Icons.Rounded.ElectricBolt,
                        accent = Color(0xFF00E5FF),
                        size = 46.dp,
                        iconSize = 26.dp,
                        shape = RoundedCornerShape(14.dp)
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = "Flashing Rom's & Img",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = tr(
                                "Fastboot ROMs, Sideload & Partition Images",
                                "Fastboot ROMs, Sideload aur Partition Images"
                            ),
                            fontSize = 12.sp,
                            color = Color(0xFF98A0B6),
                            lineHeight = 15.sp
                        )
                    }
                }

                IconButton(onClick = onToggleExpand) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Toggle Expand",
                        tint = Color.White,
                        modifier = Modifier
                            .size(28.dp)
                            .rotate(arrowRotation)
                    )
                }
            }

            // Expanded Direct Controls
            ExpandableContent(isExpanded = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {

                    // ⚡ AUTO ROM FLASHER (Apple Liquid Glass Action Button)
                    LiquidGlassCapsuleButton(
                        onClick = { showRomSourceDialog = true },
                        enabled = true,
                        gradientColors = listOf(Color(0xFF00FF66), Color(0xFF00C853)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bolt,
                                contentDescription = null,
                                tint = Color(0xFF04190B),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Auto Flasher ( Any Rom 1 Click)",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF04190B),
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Primary Package (ROM Zip) + Sideload Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            when {
                                uiState.isPreparingSideloadFile -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(Color(0x2864D2FF))
                                            .border(1.dp, Color(0x5564D2FF), RoundedCornerShape(14.dp))
                                            .padding(horizontal = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                color = blue,
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = tr("Preparing...", "Taiyaar ho raha hai..."),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextPrimary
                                            )
                                        }
                                    }
                                }

                                uiState.selectedSideloadFile == null -> {
                                    LiquidGlassCapsuleButton(
                                        onClick = onSelectSideloadFile,
                                        gradientColors = listOf(Color(0x28FFFFFF), Color(0x18FFFFFF)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                Icons.Rounded.FolderZip,
                                                contentDescription = null,
                                                tint = blue,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = tr("SELECT ZIP", "ZIP CHUNO"),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }

                                else -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(Color(0x2864D2FF))
                                            .border(1.dp, Color(0x5564D2FF), RoundedCornerShape(14.dp))
                                            .padding(horizontal = 10.dp)
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
                                                Icon(
                                                    Icons.Rounded.FolderZip,
                                                    contentDescription = null,
                                                    tint = blue,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(
                                                        text = uiState.selectedSideloadFile.name,
                                                        fontSize = 12.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = "Size: ${uiState.selectedSideloadFile.length() / (1024 * 1024)} MB",
                                                        fontSize = 10.5.sp,
                                                        color = TextSecondary
                                                    )
                                                }
                                            }

                                            IconButton(onClick = onClearSelectedFile, modifier = Modifier.size(28.dp)) {
                                                Icon(
                                                    Icons.Rounded.Close,
                                                    contentDescription = "Clear",
                                                    tint = TextSecondary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        LiquidGlassCapsuleButton(
                            onClick = onFlashSideload,
                            enabled = canSideload,
                            gradientColors = listOf(Color(0xFF00C6FF), Color(0xFF0072FF)),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (uiState.isSideloadingPackage) tr("SIDELOADING...", "SIDELOAD CHAL RAHA HAI...") else "SIDELOAD\nGAPPS, ROOT",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 12.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color(0x2EFFFFFF))
                    Spacer(modifier = Modifier.height(14.dp))

                    // ══════════════════════════════════════════════
                    // 3. FLASH CUSTOM PARTITION IMAGES (Sleek Aligned)
                    // ══════════════════════════════════════════════
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFBF5AF2))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "PARTITION IMAGE FLASHER",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFBF5AF2),
                            letterSpacing = 1.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Partition dropdown + dual-slot tick side by side
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExposedDropdownMenuBox(
                            expanded = dropdownExpanded,
                            onExpandedChange = { dropdownExpanded = it },
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                                    .liquidGlass(
                                        glowColor = if (dropdownExpanded) Color(0xFFBF5AF2) else Color.White.copy(alpha = 0.25f),
                                        shape = RoundedCornerShape(14.dp),
                                        borderWidth = 1.3.dp
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Rounded.Memory,
                                            contentDescription = null,
                                            tint = Color(0xFFBF5AF2),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = uiState.selectedPartition,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AppleGreen,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Icon(
                                        imageVector = if (dropdownExpanded) Icons.Rounded.ArrowDropUp else Icons.Rounded.ArrowDropDown,
                                        contentDescription = null,
                                        tint = TextPrimary
                                    )
                                }
                            }

                            ExposedDropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF1B1C24))
                                    .border(1.5.dp, Color(0x44BF5AF2), RoundedCornerShape(16.dp))
                            ) {
                                partitionsList.forEach { part ->
                                    val isSelected = part == uiState.selectedPartition
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = part,
                                                    color = if (isSelected) AppleGreen else TextPrimary,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    fontSize = 14.sp
                                                )

                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.CheckCircle,
                                                        contentDescription = "Selected",
                                                        tint = AppleGreen,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            onSelectPartition(part)
                                            dropdownExpanded = false
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (isSelected) Color(0x2230D158) else Color.Transparent)
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        // Dual Slot (A/B) compact tick
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (uiState.isDualSlotChecked) Color(0x2830D158) else Color(0x1AFFFFFF))
                                .border(1.2.dp, if (uiState.isDualSlotChecked) Color(0x6630D158) else Color(0x33FFFFFF), RoundedCornerShape(14.dp))
                                .clickable { onSetDualSlotChecked(!uiState.isDualSlotChecked) }
                                .padding(horizontal = 12.dp, vertical = 11.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (uiState.isDualSlotChecked) AppleGreen else Color.Transparent)
                                    .border(1.6.dp, if (uiState.isDualSlotChecked) AppleGreen else Color(0x66FFFFFF), RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (uiState.isDualSlotChecked) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = "Both slots",
                                        tint = Color.White,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Both\nslots",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (uiState.isDualSlotChecked) AppleGreen else TextSecondary,
                                textAlign = TextAlign.Center,
                                lineHeight = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Custom Command Box (ADB & Fastboot Console Runner)
                    var customCommandInput by remember { mutableStateOf("") }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customCommandInput,
                            onValueChange = { customCommandInput = it },
                            placeholder = {
                                Text(
                                    text = "fastboot flash empty vbmeta or adb devices",
                                    fontSize = 11.sp,
                                    color = Color(0xFF8E8E93),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Terminal,
                                    contentDescription = "Command",
                                    tint = Color(0xFFBF5AF2),
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFBF5AF2),
                                unfocusedBorderColor = Color(0x33FFFFFF),
                                focusedContainerColor = Color(0x1A000000),
                                unfocusedContainerColor = Color(0x1A000000)
                            ),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                        )

                        LiquidGlassCapsuleButton(
                            onClick = {
                                if (customCommandInput.isNotBlank()) {
                                    onRunCustomCommand(customCommandInput.trim())
                                }
                            },
                            enabled = customCommandInput.isNotBlank() && uiState.isConnected,
                            gradientColors = listOf(Color(0xFFBF5AF2), Color(0xFF9D4EDD)),
                            modifier = Modifier.height(50.dp).width(86.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Run",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "RUN",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Image file (left) + Flash button (right) side by side
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (uiState.isPreparingCustomImageFile) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0x28BF5AF2))
                                        .border(1.dp, Color(0x55BF5AF2), RoundedCornerShape(14.dp))
                                        .padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            color = Color(0xFFBF5AF2),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = tr("Preparing...", "Taiyaar ho raha hai..."),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                    }
                                }
                            } else if (uiState.selectedImageFile == null) {
                                LiquidGlassCapsuleButton(
                                    onClick = onSelectImageClick,
                                    gradientColors = listOf(Color(0x28FFFFFF), Color(0x18FFFFFF)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            Icons.Rounded.AddPhotoAlternate,
                                            contentDescription = null,
                                            tint = AppleBlue,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            tr("Select Image (.img)", "Image chuno (.img)"),
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            textAlign = TextAlign.Center,
                                            lineHeight = 14.sp
                                        )
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0x2830D158))
                                        .border(1.dp, Color(0x5530D158), RoundedCornerShape(14.dp))
                                        .padding(horizontal = 10.dp)
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
                                            Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, contentDescription = null, tint = AppleGreen, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = uiState.selectedImageFile.name,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${uiState.selectedImageFile.length() / (1024 * 1024)} MB",
                                                    fontSize = 10.sp,
                                                    color = TextSecondary
                                                )
                                            }
                                        }

                                        IconButton(onClick = onClearSelectedImage, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = TextSecondary, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }

                        LiquidGlassCapsuleButton(
                            onClick = onFlashCustomImage,
                            enabled = uiState.selectedImageFile != null && !uiState.isFlashingCustomImage && !uiState.isPreparingCustomImageFile && uiState.isConnected,
                            gradientColors = listOf(Color(0xFFBF5AF2), Color(0xFF9D4EDD)),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Icon(Icons.Rounded.FlashOn, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (uiState.isFlashingCustomImage) "FLASHING..." else "FLASH IMAGE",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                            }
                        }

                        if (onBootCustomImage != null && uiState.selectedImageFile != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            LiquidGlassCapsuleButton(
                                onClick = onBootCustomImage,
                                enabled = !uiState.isFlashingCustomImage && !uiState.isPreparingCustomImageFile && uiState.isConnected,
                                gradientColors = listOf(Color(0xFF26A69A), Color(0xFF00897B)),
                                modifier = Modifier.height(52.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                ) {
                                    Icon(Icons.Rounded.PowerSettingsNew, contentDescription = "Boot image", tint = Color.White, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "BOOT",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRomSourceDialog) {
        RomSourcePickerDialog(
            onDismiss = { showRomSourceDialog = false },
            onSelectFolder = {
                showRomSourceDialog = false
                onSelectRomFolder()
            },
            onSelectArchive = {
                showRomSourceDialog = false
                onSelectRomFile()
            },
            onSelectRecentFolder = { folder ->
                showRomSourceDialog = false
                onSelectExistingFolder(folder)
            }
        )
    }
}
