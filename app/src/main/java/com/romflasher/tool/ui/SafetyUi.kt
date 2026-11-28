package com.romflasher.tool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import com.romflasher.tool.ui.theme.liquidGlass
import com.romflasher.tool.ui.theme.LiquidGlassCapsuleButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A pending destructive action waiting for the user to confirm it. Held in
 * Compose state on the main screen so the ViewModel logic stays untouched.
 */
data class ConfirmRequest(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val accent: Color,
    val action: () -> Unit
)

/**
 * Shared "are you sure?" sheet used by every destructive action (partition
 * flash, sideload, reboot, slot switch). The action only
 * runs after an explicit confirm tap.
 */
@Composable
fun ConfirmActionDialog(request: ConfirmRequest, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        SmoothDialogEntrance {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .shadow(32.dp, RoundedCornerShape(22.dp), spotColor = request.accent, ambientColor = request.accent)
                    .liquidGlass(request.accent, shape = RoundedCornerShape(22.dp), borderWidth = 1.6.dp)
                    .padding(18.dp)
            ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = request.accent,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = request.title,
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = request.message,
                    fontSize = 12.5.sp,
                    color = Color(0xFFB9BCC6),
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(Color(0x22FFFFFF))
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(13.dp))
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tr("CANCEL", "CANCEL"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFB8B8BE)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(request.accent)
                            .clickable { request.action(); onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = request.confirmLabel,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF04121A),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        }
    }
}
