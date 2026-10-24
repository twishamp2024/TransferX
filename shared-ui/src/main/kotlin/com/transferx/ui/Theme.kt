package com.transferx.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Original TransferX palette: deep teal primary, warm amber accent for warnings.
private val Teal = Color(0xFF0B7A75)
private val TealLight = Color(0xFF5FD4C9)
private val Ink = Color(0xFF14212B)
private val Amber = Color(0xFFB45309)

private val Light = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFEB),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF3F5B78),
    secondaryContainer = Color(0xFFDCE6F2),
    tertiary = Amber,
    tertiaryContainer = Color(0xFFFDE8CF),
    onTertiaryContainer = Color(0xFF3A1A00),
    background = Color(0xFFF7FAFA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE6EEEE),
    onSurface = Ink,
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
)

private val Dark = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF00504B),
    onPrimaryContainer = Color(0xFFCDEFEB),
    secondary = Color(0xFFA9C7E8),
    secondaryContainer = Color(0xFF2A4560),
    tertiary = Color(0xFFFBBF6C),
    tertiaryContainer = Color(0xFF5A3200),
    onTertiaryContainer = Color(0xFFFDE8CF),
    background = Color(0xFF0E1517),
    surface = Color(0xFF141C1E),
    surfaceVariant = Color(0xFF243033),
    onSurface = Color(0xFFE2E9EA),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF601410),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun TransferXTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
}
