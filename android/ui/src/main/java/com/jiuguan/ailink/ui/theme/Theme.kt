package com.jiuguan.ailink.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = BrandBlueSoft,
    onPrimaryContainer = BrandBlueDeep,
    secondary = Color(0xFF3F6BEA),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6FF),
    onSecondaryContainer = Color(0xFF10224D),
    tertiary = WarnAmber,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = DangerRed,
    onError = Color.White,
    errorContainer = DangerRedSoft,
    onErrorContainer = Color(0xFF5C0A0C)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7E9BFF),
    onPrimary = Color(0xFF0B1B45),
    primaryContainer = Color(0xFF23325E),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFF8FAEFF),
    onSecondary = Color(0xFF0B1B45),
    secondaryContainer = Color(0xFF1E2E52),
    onSecondaryContainer = Color(0xFFD6E1FF),
    tertiary = Color(0xFFF7B955),
    onTertiary = Color(0xFF3A2600),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = Color(0xFFFF8A8D),
    onError = Color(0xFF4A0507),
    errorContainer = Color(0xFF4A1A1C),
    onErrorContainer = Color(0xFFFFDAD9)
)

@Composable
fun AiLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
