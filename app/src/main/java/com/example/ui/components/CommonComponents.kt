package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalPulseChatColors
import com.example.ui.theme.OnlineEmerald
import com.example.util.MediaUtils

fun parseHexColor(hex: String, fallback: Color = Color(0xFF128C7E)): Color {
    return try {
        val cleaned = hex.removePrefix("#")
        val colorInt = cleaned.toLong(16)
        when (cleaned.length) {
            6 -> Color(0xFF000000L or colorInt)
            8 -> Color(colorInt)
            else -> fallback
        }
    } catch (_: Exception) {
        fallback
    }
}

@Composable
fun UserAvatar(
    name: String,
    colorHex: String,
    photoBase64: String = "",
    size: Dp = 48.dp,
    isGroup: Boolean = false,
    showOnlineBadge: Boolean = false,
    hasStatusRing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val pulseColors = LocalPulseChatColors.current
    val bgColor = remember(colorHex) { parseHexColor(colorHex) }
    val bitmap: Bitmap? = remember(photoBase64) { MediaUtils.decodeBase64ToBitmap(photoBase64) }
    val initials = remember(name) {
        name.trim().split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "P" }
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        val ringModifier = if (hasStatusRing) {
            Modifier
                .fillMaxSize()
                .border(2.5.dp, pulseColors.accentGreen, CircleShape)
                .padding(3.5.dp)
        } else {
            Modifier.fillMaxSize()
        }

        Box(
            modifier = ringModifier
                .clip(CircleShape)
                .background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "$name avatar",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else if (isGroup) {
                Icon(
                    imageVector = Icons.Default.Group,
                    contentDescription = "$name group",
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.52f)
                )
            } else {
                Text(
                    text = initials,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.36f).sp
                )
            }
        }

        if (showOnlineBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.28f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(OnlineEmerald)
            )
        }
    }
}

@Composable
fun MessageStatusTick(
    status: String,
    tintDefault: Color,
    modifier: Modifier = Modifier
) {
    val pulseColors = LocalPulseChatColors.current
    val (icon, tint, desc) = when (status) {
        "read" -> Triple(Icons.Default.DoneAll, pulseColors.readTick, "Read")
        "delivered" -> Triple(Icons.Default.DoneAll, tintDefault, "Delivered")
        else -> Triple(Icons.Default.Done, tintDefault, "Sent")
    }
    Icon(
        imageVector = icon,
        contentDescription = desc,
        tint = tint,
        modifier = modifier.size(16.dp)
    )
}

@Composable
fun ChatWallpaperBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val pulseColors = LocalPulseChatColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(pulseColors.chatBackground)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 72.dp.toPx()
            val stroke = Stroke(width = 1.4.dp.toPx())
            val patternColor = pulseColors.chatPatternTint
            var y = 0f
            var row = 0
            while (y < size.height + step) {
                var x = if (row % 2 == 0) 0f else step / 2f
                while (x < size.width + step) {
                    // Subtle geometric bubble + pulse motif
                    drawCircle(
                        color = patternColor,
                        radius = 10.dp.toPx(),
                        center = Offset(x + 14.dp.toPx(), y + 14.dp.toPx()),
                        style = stroke
                    )
                    drawLine(
                        color = patternColor,
                        start = Offset(x + 36.dp.toPx(), y + 26.dp.toPx()),
                        end = Offset(x + 48.dp.toPx(), y + 14.dp.toPx()),
                        strokeWidth = 1.4.dp.toPx()
                    )
                    drawCircle(
                        color = patternColor,
                        radius = 3.dp.toPx(),
                        center = Offset(x + 56.dp.toPx(), y + 40.dp.toPx())
                    )
                    x += step
                }
                y += step
                row++
            }
        }
        content()
    }
}

@Composable
fun EmptyStatePanel(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(modifier = Modifier.height(20.dp))
            action()
        }
    }
}
