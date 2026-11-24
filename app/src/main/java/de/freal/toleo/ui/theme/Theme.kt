package de.freal.toleo.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

internal val Light = lightColorScheme(
    primary = Color(0xFF8A4B2A),
    secondary = Color(0xFF6F5B40),
    tertiary = Color(0xFF52643F),
)
internal val Dark = darkColorScheme(
    primary = Color(0xFFFFB68F),
    secondary = Color(0xFFDDC2A1),
    tertiary = Color(0xFFB9CCA0),
)

@Composable
fun ToleoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
