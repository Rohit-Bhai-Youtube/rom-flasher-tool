package com.romflasher.tool.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.romflasher.tool.R
import com.romflasher.tool.ui.theme.*
import com.romflasher.tool.viewmodel.DeviceMode
import com.romflasher.tool.viewmodel.UiState

@Composable
fun TopHeaderBanner() {
    val context = LocalContext.current
    var showDonate by remember { mutableStateOf(false) }

    // Multi-stop 24K Liquid Gold Metallic Gradient for "ROM"
    val goldBrush = remember {
        Brush.horizontalGradient(
            listOf(
                Color(0xFFFFEA79), // specular bright gold glint
                Color(0xFFFFC107), // classic rich gold
                Color(0xFFFF9800), // deep warm amber
                Color(0xFFFFC107)  // gold glint
            )
        )
    }

    // VisionOS Milled Diamond Titanium Gradient for "FLASHER"
    val titaniumBrush = remember {
        Brush.horizontalGradient(
            listOf(
                Color(0xFFFFFFFF), // pure white glint
                Color(0xFFE2E8F0), // platinum silver
                Color(0xFFCBD5E1), // titanium
                Color(0xFFFFFFFF)  // specular sheen
            )
        )
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Main Title Row: ROM + Glowing Crown + FLASHER
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { openRomFlasherSite(context) }
                ) {
                    Text(
                        text = "ROM",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        style = TextStyle(brush = goldBrush),
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))

                    // Crown with golden ambient halo
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 1.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(Color(0x55FFD700), Color.Transparent)
                                )
                            )
                            .padding(horizontal = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "👑",
                            fontSize = 19.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Text(
                        text = "FLASHER",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        style = TextStyle(brush = titaniumBrush),
                        letterSpacing = (-0.5).sp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Subtitle Badges: Apple VisionOS Frosted Glass Capsules
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // "ALL-IN-ONE ANDROID TOOL" Frosted Ruby Glass Capsule
                    val allInOneShape = RoundedCornerShape(9.dp)
                    Box(
                        modifier = Modifier
                            .clip(allInOneShape)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0x35FF3B30), Color(0x18FF3B30))
                                )
                            )
                            .border(
                                1.1.dp,
                                Brush.verticalGradient(
                                    listOf(Color(0x99FF453A), Color(0x33FF3B30))
                                ),
                                allInOneShape
                            )
                            .clickable { openRomFlasherSite(context) }
                            .padding(horizontal = 8.dp, vertical = 3.5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.ElectricBolt,
                                contentDescription = null,
                                tint = Color(0xFFFF453A),
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "All-In-One Android Tool",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    // "DONATE" Liquid Amber Glass Capsule
                    val donateShape = RoundedCornerShape(9.dp)
                    Box(
                        modifier = Modifier
                            .clip(donateShape)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0x40FF9500), Color(0x20FF9500))
                                )
                            )
                            .border(
                                1.1.dp,
                                Brush.verticalGradient(
                                    listOf(Color(0xFFFFD60A), Color(0x44FF9F0A))
                                ),
                                donateShape
                            )
                            .clickable { showDonate = true }
                            .padding(horizontal = 9.dp, vertical = 3.5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Favorite,
                                contentDescription = "Donate",
                                tint = Color(0xFFFFD60A),
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "DONATE",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFFFF9E6),
                                letterSpacing = 0.4.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // YouTube Channel 3D Liquid Glass Squircle Island
            val ytShape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .clip(ytShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x33FF2D55), Color(0x121C1D24))
                        )
                    )
                    .border(
                        1.3.dp,
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.65f), Color(0x44FF2D55))
                        ),
                        ytShape
                    )
                    .clickable { openYouTubeChannel(context) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_youtube_logo),
                        contentDescription = "YouTube Channel",
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "CHANNEL",
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFFF6961),
                        letterSpacing = 0.8.sp
                    )
                }
            }
        }

        if (showDonate) {
            DonateDialog(onClose = { showDonate = false })
        }
    }
}

@Composable
fun CyberDeviceStatusCard(
    uiState: UiState,
    onShowDeviceInfo: () -> Unit,
    embedded: Boolean = false
) {
    val isConnected = uiState.isConnected
    val deviceName = if (isConnected && uiState.deviceModel.isNotBlank()) {
        "${uiState.deviceModel} (${uiState.deviceCodeName.ifEmpty { "Android" }})"
    } else if (isConnected) {
        tr("Connected Device", "Device connected")
    } else {
        tr("No Device Connected", "Koi device connect nahi hai")
    }

    val statusColor by animateColorAsState(
        targetValue = if (isConnected) Color(0xFF30D158) else Color(0xFFFF453A),
        animationSpec = tween(260),
        label = "usbStatusColor"
    )
    val subtitleColor by animateColorAsState(
        targetValue = if (isConnected) Color(0xFF30D158) else Color(0xFFFF6961),
        animationSpec = tween(260),
        label = "usbSubtitleColor"
    )

    val cardModifier = if (embedded) {
        Modifier
            .fillMaxWidth()
            .animateContentSize(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f))
            .padding(vertical = 2.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .animateContentSize(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f))
            .liquidGlass(
                glowColor = if (isConnected) Color(0xFF30D158) else Color(0xFF00E5FF),
                shape = LiquidGlass.CardShape,
                borderWidth = 1.8.dp
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    }

    Box(modifier = cardModifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Device Info Squircle Button with glowing Cyan accent
                LiquidGlassSquircleIcon(
                    icon = Icons.Rounded.Info,
                    accent = Color(0xFF00E5FF),
                    size = 36.dp,
                    iconSize = 19.dp,
                    shape = RoundedCornerShape(11.dp),
                    modifier = Modifier.clickable { onShowDeviceInfo() }
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = deviceName,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isConnected) "● USB Online"
                            else tr("○ Connect via OTG", "○ OTG se connect karo"),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = subtitleColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Glass Status Pills for ADB and FASTBOOT with glowing LED indicators
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusIndicatorDot(
                    label = "ADB",
                    subtitle = if (uiState.deviceMode == DeviceMode.ADB) "ONLINE" else "OFFLINE",
                    isActive = uiState.deviceMode == DeviceMode.ADB
                )
                StatusIndicatorDot(
                    label = "FASTBOOT",
                    subtitle = if (uiState.deviceMode == DeviceMode.FASTBOOT) "ONLINE" else "OFFLINE",
                    isActive = uiState.deviceMode == DeviceMode.FASTBOOT
                )
            }
        }
    }
}

@Composable
fun StatusIndicatorDot(label: String, subtitle: String, isActive: Boolean) {
    val activeColor = Color(0xFF30D158)       // Apple Neon Green
    val inactiveColor = Color(0xFF8E8E93)     // Apple System Grey
    val pillShape = RoundedCornerShape(8.dp)

    val bgBrush = if (isActive) {
        Brush.verticalGradient(
            listOf(Color(0x3530D158), Color(0x1530D158))
        )
    } else {
        Brush.verticalGradient(
            listOf(Color(0x281C1D24), Color(0x151C1D24))
        )
    }

    val borderBrush = if (isActive) {
        Brush.verticalGradient(
            listOf(Color(0x9930D158), Color(0x3330D158))
        )
    } else {
        Brush.verticalGradient(
            listOf(Color(0x33FFFFFF), Color(0x10FFFFFF))
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(pillShape)
            .background(bgBrush)
            .border(1.dp, borderBrush, pillShape)
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // LED Bead with radial glow
            Box(
                modifier = Modifier
                    .size(6.5.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) {
                            Brush.radialGradient(
                                listOf(Color(0xFF30D158), Color(0xFF248A3D))
                            )
                        } else {
                            Brush.radialGradient(
                                listOf(Color(0xFF636366), Color(0xFF3A3A3C))
                            )
                        }
                    )
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                letterSpacing = 0.3.sp
            )
        }
        Text(
            text = subtitle,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (isActive) activeColor else inactiveColor
        )
    }
}

private fun openYouTubeChannel(context: Context) {
    val url = "https://www.youtube.com/@Rom_Flasher"
    try {
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:@Rom_Flasher"))
        appIntent.setPackage("com.google.android.youtube")
        context.startActivity(appIntent)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }
}
