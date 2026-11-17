package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = DarkSkyNavy,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkNavyContainer,
    onPrimaryContainer = DarkOnNavyContainer,
    secondary = DarkSoftTeal,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkTealContainer,
    onSecondaryContainer = DarkOnTealContainer,
    tertiary = DarkWarmAmber,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkAmberContainer,
    onTertiaryContainer = DarkOnAmberContainer,
    background = CharcoalInkBackground,
    onBackground = LightParchmentOnDark,
    surface = DarkSlateSurface,
    onSurface = LightParchmentOnDark,
    surfaceVariant = DarkSlateSurfaceVariant,
    onSurfaceVariant = LightMutedOnDark,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkCrimsonError,
    errorContainer = DarkCrimsonContainer,
    onErrorContainer = DarkOnCrimsonContainer,
)

private val LightColorScheme = lightColorScheme(
    primary = DeepAcademicNavy,
    onPrimary = NavyOnPrimary,
    primaryContainer = SoftSlateContainer,
    onPrimaryContainer = OnSlateContainer,
    secondary = ScholarlyTeal,
    onSecondary = TealOnSecondary,
    secondaryContainer = PaleTealContainer,
    onSecondaryContainer = OnTealContainer,
    tertiary = ManuscriptAmber,
    onTertiary = AmberOnTertiary,
    tertiaryContainer = WarmAmberContainer,
    onTertiaryContainer = OnAmberContainer,
    background = ParchmentBackground,
    onBackground = InkOnBackground,
    surface = ManuscriptSurface,
    onSurface = InkOnBackground,
    surfaceVariant = ParchmentSurfaceVariant,
    onSurfaceVariant = InkMuted,
    outline = ParchmentOutline,
    outlineVariant = ParchmentOutlineVariant,
    error = CrimsonError,
    errorContainer = CrimsonContainer,
    onErrorContainer = OnCrimsonContainer,
)

val MuharrirShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun MuharrirTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MuharrirTypography,
            shapes = MuharrirShapes,
            content = content,
        )
    }
}
