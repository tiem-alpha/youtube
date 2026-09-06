package com.example.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF87D3F2),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF003548),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF174D65),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF214859),
    background = androidx.compose.ui.graphics.Color(0xFF0E171C),
    surface = androidx.compose.ui.graphics.Color(0xFF111C22),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFF192930)
)
private val LightColorScheme = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF006B91),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFBCEAFF),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFD5F1FC),
    background = androidx.compose.ui.graphics.Color(0xFFF8FCFF),
    surface = androidx.compose.ui.graphics.Color(0xFFF8FCFF),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFFEAF6FC),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFDDEFF7),
    onSurface = androidx.compose.ui.graphics.Color(0xFF14232B),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF49616D)
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}