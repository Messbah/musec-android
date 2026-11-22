package com.musec.browser.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

object MuSecColors {
    val BgDeep = Color(0xFF161824)
    val SurfaceGlass = Color(0xFF212436)
    val SurfaceCard = Color(0xFF272B40)
    val BorderSubtle = Color(0x28FFFFFF)
    val NeonCyan = Color(0xFF38BDF8)
    val NeonViolet = Color(0xFFA78BFA)
    val KidsEmerald = Color(0xFF34D399)
    val WarningAmber = Color(0xFFFBBF24)
    val DangerRed = Color(0xFFF87171)
    val TextPrimary = Color(0xFFF4F4F6)
    val TextSecondary = Color(0xFFA9B2C8)
}

private val MuSecDarkScheme = darkColorScheme(
    primary = MuSecColors.NeonCyan,
    secondary = MuSecColors.NeonViolet,
    tertiary = MuSecColors.KidsEmerald,
    background = MuSecColors.BgDeep,
    surface = MuSecColors.SurfaceGlass,
    surfaceVariant = MuSecColors.SurfaceCard,
    onPrimary = Color(0xFF0A111D),
    onBackground = MuSecColors.TextPrimary,
    onSurface = MuSecColors.TextPrimary
)

@Composable
fun MuSecTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MuSecDarkScheme,
        content = content
    )
}

/**
 * Adds a crisp Cyan D-Pad focus highlight ring for Android TV remote control navigation
 * while staying unobtrusive on touch phones.
 */
fun Modifier.tvFocusBorder(
    shape: Shape = RoundedCornerShape(14.dp),
    focusedColor: Color = MuSecColors.NeonCyan
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused }
        .border(
            width = if (focused) 2.5.dp else 1.dp,
            color = if (focused) focusedColor else MuSecColors.BorderSubtle,
            shape = shape
        )
        .focusable()
}
