package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Immutable
data class MuharrirExtendedColors(
    val headerGradientStart: Color,
    val headerGradientEnd: Color,
    val onHeader: Color,
    val onHeaderMuted: Color,
    val headerBadgeBackground: Color,
    val headerOutline: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val paused: Color,
    val pausedContainer: Color,
    val onPausedContainer: Color,
)

@Immutable
data class PaletteSwatchPreview(
    val background: Color,
    val surface: Color,
    val header: Color,
    val accent: Color,
)

private data class PaletteBundle(
    val lightScheme: ColorScheme,
    val darkScheme: ColorScheme,
    val lightExtended: MuharrirExtendedColors,
    val darkExtended: MuharrirExtendedColors,
    val lightPreview: PaletteSwatchPreview,
    val darkPreview: PaletteSwatchPreview,
)

private fun lightExtendedWithHeader(
    headerStart: Color,
    headerEnd: Color,
): MuharrirExtendedColors = MuharrirExtendedColors(
    headerGradientStart = headerStart,
    headerGradientEnd = headerEnd,
    onHeader = Color(0xFFF6F5F1),
    onHeaderMuted = Color(0xFFD6DAE0),
    headerBadgeBackground = Color(0x26FFFFFF),
    headerOutline = Color(0x52FFFFFF),
    success = StatusSuccessLight,
    onSuccess = StatusOnSuccessLight,
    successContainer = StatusSuccessContainerLight,
    onSuccessContainer = StatusOnSuccessContainerLight,
    warning = StatusWarningLight,
    onWarning = StatusOnWarningLight,
    warningContainer = StatusWarningContainerLight,
    onWarningContainer = StatusOnWarningContainerLight,
    paused = StatusPausedLight,
    pausedContainer = StatusPausedContainerLight,
    onPausedContainer = StatusOnPausedContainerLight,
)

private fun darkExtendedWithHeader(
    headerStart: Color,
    headerEnd: Color,
    headerOutline: Color,
): MuharrirExtendedColors = MuharrirExtendedColors(
    headerGradientStart = headerStart,
    headerGradientEnd = headerEnd,
    onHeader = Color(0xFFECEAE4),
    onHeaderMuted = Color(0xFFB5BCC7),
    headerBadgeBackground = Color(0x1EFFFFFF),
    headerOutline = headerOutline,
    success = StatusSuccessDark,
    onSuccess = StatusOnSuccessDark,
    successContainer = StatusSuccessContainerDark,
    onSuccessContainer = StatusOnSuccessContainerDark,
    warning = StatusWarningDark,
    onWarning = StatusOnWarningDark,
    warningContainer = StatusWarningContainerDark,
    onWarningContainer = StatusOnWarningContainerDark,
    paused = StatusPausedDark,
    pausedContainer = StatusPausedContainerDark,
    onPausedContainer = StatusOnPausedContainerDark,
)

private val NeutralBundle = PaletteBundle(
    lightScheme = lightColorScheme(
        primary = Color(0xFF2B3A4A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDDE5ED),
        onPrimaryContainer = Color(0xFF16222E),
        secondary = Color(0xFF4B5E6F),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE3EAF0),
        onSecondaryContainer = Color(0xFF1F2D3A),
        tertiary = Color(0xFF8C6B3B),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFF3E7D3),
        onTertiaryContainer = Color(0xFF33230C),
        background = Color(0xFFF7F7F5),
        onBackground = Color(0xFF1A1D21),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1A1D21),
        surfaceVariant = Color(0xFFECECE7),
        onSurfaceVariant = Color(0xFF545B64),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF3F3F0),
        surfaceContainer = Color(0xFFEDEDEA),
        surfaceContainerHigh = Color(0xFFE6E6E2),
        surfaceContainerHighest = Color(0xFFDFDFDA),
        outline = Color(0xFFD2D4D8),
        outlineVariant = Color(0xFFE3E4E6),
        error = CalmCrimsonErrorLight,
        onError = CalmCrimsonOnErrorLight,
        errorContainer = CalmCrimsonContainerLight,
        onErrorContainer = CalmCrimsonOnContainerLight,
    ),
    darkScheme = darkColorScheme(
        primary = Color(0xFF9FB4C7),
        onPrimary = Color(0xFF14212D),
        primaryContainer = Color(0xFF283746),
        onPrimaryContainer = Color(0xFFDCE5EE),
        secondary = Color(0xFF90A7B8),
        onSecondary = Color(0xFF162530),
        secondaryContainer = Color(0xFF24323F),
        onSecondaryContainer = Color(0xFFD7E2EC),
        tertiary = Color(0xFFD4B47C),
        onTertiary = Color(0xFF37280D),
        tertiaryContainer = Color(0xFF4B3818),
        onTertiaryContainer = Color(0xFFF3E2C3),
        background = Color(0xFF111418),
        onBackground = Color(0xFFE8E9EC),
        surface = Color(0xFF181C22),
        onSurface = Color(0xFFE8E9EC),
        surfaceVariant = Color(0xFF232932),
        onSurfaceVariant = Color(0xFFAAB2BD),
        surfaceContainerLowest = Color(0xFF0E1115),
        surfaceContainerLow = Color(0xFF161A20),
        surfaceContainer = Color(0xFF1D222A),
        surfaceContainerHigh = Color(0xFF252B34),
        surfaceContainerHighest = Color(0xFF2E3540),
        outline = Color(0xFF3D4552),
        outlineVariant = Color(0xFF2B323D),
        error = CalmCrimsonErrorDark,
        onError = CalmCrimsonOnErrorDark,
        errorContainer = CalmCrimsonContainerDark,
        onErrorContainer = CalmCrimsonOnContainerDark,
    ),
    lightExtended = lightExtendedWithHeader(
        headerStart = Color(0xFF25313E),
        headerEnd = Color(0xFF2E3C4B),
    ),
    darkExtended = darkExtendedWithHeader(
        headerStart = Color(0xFF1B222B),
        headerEnd = Color(0xFF222B36),
        headerOutline = Color(0xFF34404F),
    ),
    lightPreview = PaletteSwatchPreview(
        background = Color(0xFFF7F7F5),
        surface = Color(0xFFFFFFFF),
        header = Color(0xFF25313E),
        accent = Color(0xFF8C6B3B),
    ),
    darkPreview = PaletteSwatchPreview(
        background = Color(0xFF111418),
        surface = Color(0xFF181C22),
        header = Color(0xFF1B222B),
        accent = Color(0xFF9FB4C7),
    ),
)

private val WarmIvoryBundle = PaletteBundle(
    lightScheme = lightColorScheme(
        primary = Color(0xFF343D46),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE3E0D6),
        onPrimaryContainer = Color(0xFF1F252B),
        secondary = Color(0xFF53605B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE8E4DA),
        onSecondaryContainer = Color(0xFF27302C),
        tertiary = Color(0xFF996B2F),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFF4E6CF),
        onTertiaryContainer = Color(0xFF382307),
        background = Color(0xFFF8F4EA),
        onBackground = Color(0xFF1E1C18),
        surface = Color(0xFFFDFBF6),
        onSurface = Color(0xFF1E1C18),
        surfaceVariant = Color(0xFFEFE8D9),
        onSurfaceVariant = Color(0xFF5D574D),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF6F1E5),
        surfaceContainer = Color(0xFFF0E9DA),
        surfaceContainerHigh = Color(0xFFE9E1CF),
        surfaceContainerHighest = Color(0xFFE2D9C4),
        outline = Color(0xFFD5CCB9),
        outlineVariant = Color(0xFFE6DFCF),
        error = CalmCrimsonErrorLight,
        onError = CalmCrimsonOnErrorLight,
        errorContainer = CalmCrimsonContainerLight,
        onErrorContainer = CalmCrimsonOnContainerLight,
    ),
    darkScheme = darkColorScheme(
        primary = Color(0xFFC8BCA3),
        onPrimary = Color(0xFF262016),
        primaryContainer = Color(0xFF3A3429),
        onPrimaryContainer = Color(0xFFEDE4D2),
        secondary = Color(0xFF9FB0A8),
        onSecondary = Color(0xFF18241E),
        secondaryContainer = Color(0xFF2A322E),
        onSecondaryContainer = Color(0xFFDDE7E1),
        tertiary = Color(0xFFD6AF70),
        onTertiary = Color(0xFF3A270B),
        tertiaryContainer = Color(0xFF503915),
        onTertiaryContainer = Color(0xFFF5E3C2),
        background = Color(0xFF151310),
        onBackground = Color(0xFFECE6DA),
        surface = Color(0xFF1D1A16),
        onSurface = Color(0xFFECE6DA),
        surfaceVariant = Color(0xFF29241E),
        onSurfaceVariant = Color(0xFFB7AFA2),
        surfaceContainerLowest = Color(0xFF110F0C),
        surfaceContainerLow = Color(0xFF1A1713),
        surfaceContainer = Color(0xFF221E19),
        surfaceContainerHigh = Color(0xFF2B2620),
        surfaceContainerHighest = Color(0xFF352F27),
        outline = Color(0xFF474036),
        outlineVariant = Color(0xFF332E27),
        error = CalmCrimsonErrorDark,
        onError = CalmCrimsonOnErrorDark,
        errorContainer = CalmCrimsonContainerDark,
        onErrorContainer = CalmCrimsonOnContainerDark,
    ),
    lightExtended = lightExtendedWithHeader(
        headerStart = Color(0xFF2D3238),
        headerEnd = Color(0xFF383E45),
    ),
    darkExtended = darkExtendedWithHeader(
        headerStart = Color(0xFF221F1A),
        headerEnd = Color(0xFF2B2721),
        headerOutline = Color(0xFF443D33),
    ),
    lightPreview = PaletteSwatchPreview(
        background = Color(0xFFF8F4EA),
        surface = Color(0xFFFDFBF6),
        header = Color(0xFF2D3238),
        accent = Color(0xFF996B2F),
    ),
    darkPreview = PaletteSwatchPreview(
        background = Color(0xFF151310),
        surface = Color(0xFF1D1A16),
        header = Color(0xFF221F1A),
        accent = Color(0xFFC8BCA3),
    ),
)

private val SoftGrayBundle = PaletteBundle(
    lightScheme = lightColorScheme(
        primary = Color(0xFF38414C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE0E4EA),
        onPrimaryContainer = Color(0xFF1B2129),
        secondary = Color(0xFF535D6B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE5E8ED),
        onSecondaryContainer = Color(0xFF222831),
        tertiary = Color(0xFF7D6B52),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFEDE5D8),
        onTertiaryContainer = Color(0xFF2F2518),
        background = Color(0xFFF2F3F5),
        onBackground = Color(0xFF1B1D21),
        surface = Color(0xFFFAFAFB),
        onSurface = Color(0xFF1B1D21),
        surfaceVariant = Color(0xFFE5E7EC),
        onSurfaceVariant = Color(0xFF555B65),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF4F5F7),
        surfaceContainer = Color(0xFFECEEF1),
        surfaceContainerHigh = Color(0xFFE5E7EB),
        surfaceContainerHighest = Color(0xFFDDE0E5),
        outline = Color(0xFFCFD3DA),
        outlineVariant = Color(0xFFE0E3E8),
        error = CalmCrimsonErrorLight,
        onError = CalmCrimsonOnErrorLight,
        errorContainer = CalmCrimsonContainerLight,
        onErrorContainer = CalmCrimsonOnContainerLight,
    ),
    darkScheme = darkColorScheme(
        primary = Color(0xFFB5BCC7),
        onPrimary = Color(0xFF1D2229),
        primaryContainer = Color(0xFF2E333C),
        onPrimaryContainer = Color(0xFFE2E6EC),
        secondary = Color(0xFF9AA3B0),
        onSecondary = Color(0xFF1B2028),
        secondaryContainer = Color(0xFF282D35),
        onSecondaryContainer = Color(0xFFDADFE7),
        tertiary = Color(0xFFCBB692),
        onTertiary = Color(0xFF322818),
        tertiaryContainer = Color(0xFF453927),
        onTertiaryContainer = Color(0xFFEEE3D1),
        background = Color(0xFF121316),
        onBackground = Color(0xFFE6E7EA),
        surface = Color(0xFF1A1C20),
        onSurface = Color(0xFFE6E7EA),
        surfaceVariant = Color(0xFF24272E),
        onSurfaceVariant = Color(0xFFAAB0BA),
        surfaceContainerLowest = Color(0xFF0E0F12),
        surfaceContainerLow = Color(0xFF17191D),
        surfaceContainer = Color(0xFF1F2228),
        surfaceContainerHigh = Color(0xFF272B32),
        surfaceContainerHighest = Color(0xFF30353D),
        outline = Color(0xFF3E434E),
        outlineVariant = Color(0xFF2C3039),
        error = CalmCrimsonErrorDark,
        onError = CalmCrimsonOnErrorDark,
        errorContainer = CalmCrimsonContainerDark,
        onErrorContainer = CalmCrimsonOnContainerDark,
    ),
    lightExtended = lightExtendedWithHeader(
        headerStart = Color(0xFF2D323B),
        headerEnd = Color(0xFF383E49),
    ),
    darkExtended = darkExtendedWithHeader(
        headerStart = Color(0xFF1D2025),
        headerEnd = Color(0xFF252930),
        headerOutline = Color(0xFF393E48),
    ),
    lightPreview = PaletteSwatchPreview(
        background = Color(0xFFF2F3F5),
        surface = Color(0xFFFAFAFB),
        header = Color(0xFF2D323B),
        accent = Color(0xFF535D6B),
    ),
    darkPreview = PaletteSwatchPreview(
        background = Color(0xFF121316),
        surface = Color(0xFF1A1C20),
        header = Color(0xFF1D2025),
        accent = Color(0xFFB5BCC7),
    ),
)

private val MutedBlueGrayBundle = PaletteBundle(
    lightScheme = lightColorScheme(
        primary = Color(0xFF2B4154),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD9E4EE),
        onPrimaryContainer = Color(0xFF142433),
        secondary = Color(0xFF456073),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDFEAEE),
        onSecondaryContainer = Color(0xFF1B2E3A),
        tertiary = Color(0xFF8B6E46),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFF2E6D4),
        onTertiaryContainer = Color(0xFF32230E),
        background = Color(0xFFEFF3F6),
        onBackground = Color(0xFF171E24),
        surface = Color(0xFFF9FBFD),
        onSurface = Color(0xFF171E24),
        surfaceVariant = Color(0xFFE1E8EE),
        onSurfaceVariant = Color(0xFF4E5B66),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF2F6F9),
        surfaceContainer = Color(0xFFE9EFF4),
        surfaceContainerHigh = Color(0xFFE1E8EE),
        surfaceContainerHighest = Color(0xFFD8E1E8),
        outline = Color(0xFFC8D3DC),
        outlineVariant = Color(0xFFDCE4EB),
        error = CalmCrimsonErrorLight,
        onError = CalmCrimsonOnErrorLight,
        errorContainer = CalmCrimsonContainerLight,
        onErrorContainer = CalmCrimsonOnContainerLight,
    ),
    darkScheme = darkColorScheme(
        primary = Color(0xFF94B0C8),
        onPrimary = Color(0xFF122331),
        primaryContainer = Color(0xFF223647),
        onPrimaryContainer = Color(0xFFD8E5F0),
        secondary = Color(0xFF82A3B6),
        onSecondary = Color(0xFF122631),
        secondaryContainer = Color(0xFF1F323E),
        onSecondaryContainer = Color(0xFFD4E4ED),
        tertiary = Color(0xFFCCA972),
        onTertiary = Color(0xFF34240D),
        tertiaryContainer = Color(0xFF493617),
        onTertiaryContainer = Color(0xFFF2E0C0),
        background = Color(0xFF0F151B),
        onBackground = Color(0xFFE4EAEF),
        surface = Color(0xFF161E26),
        onSurface = Color(0xFFE4EAEF),
        surfaceVariant = Color(0xFF202A34),
        onSurfaceVariant = Color(0xFFA4B2BF),
        surfaceContainerLowest = Color(0xFF0B1015),
        surfaceContainerLow = Color(0xFF131A21),
        surfaceContainer = Color(0xFF1A232C),
        surfaceContainerHigh = Color(0xFF222D38),
        surfaceContainerHighest = Color(0xFF2A3744),
        outline = Color(0xFF384756),
        outlineVariant = Color(0xFF26323E),
        error = CalmCrimsonErrorDark,
        onError = CalmCrimsonOnErrorDark,
        errorContainer = CalmCrimsonContainerDark,
        onErrorContainer = CalmCrimsonOnContainerDark,
    ),
    lightExtended = lightExtendedWithHeader(
        headerStart = Color(0xFF233342),
        headerEnd = Color(0xFF2B3E50),
    ),
    darkExtended = darkExtendedWithHeader(
        headerStart = Color(0xFF17222C),
        headerEnd = Color(0xFF1E2B37),
        headerOutline = Color(0xFF314455),
    ),
    lightPreview = PaletteSwatchPreview(
        background = Color(0xFFEFF3F6),
        surface = Color(0xFFF9FBFD),
        header = Color(0xFF233342),
        accent = Color(0xFF456073),
    ),
    darkPreview = PaletteSwatchPreview(
        background = Color(0xFF0F151B),
        surface = Color(0xFF161E26),
        header = Color(0xFF17222C),
        accent = Color(0xFF94B0C8),
    ),
)

private val SoftSageBundle = PaletteBundle(
    lightScheme = lightColorScheme(
        primary = Color(0xFF2D453E),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD9E7E1),
        onPrimaryContainer = Color(0xFF152722),
        secondary = Color(0xFF48635B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE0EAE5),
        onSecondaryContainer = Color(0xFF1D2F2A),
        tertiary = Color(0xFF8A6F46),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFF2E7D5),
        onTertiaryContainer = Color(0xFF322410),
        background = Color(0xFFF1F4F1),
        onBackground = Color(0xFF191F1C),
        surface = Color(0xFFFAFBF9),
        onSurface = Color(0xFF191F1C),
        surfaceVariant = Color(0xFFE3EAE4),
        onSurfaceVariant = Color(0xFF515E57),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF3F6F3),
        surfaceContainer = Color(0xFFECF0EC),
        surfaceContainerHigh = Color(0xFFE3E9E4),
        surfaceContainerHighest = Color(0xFFD9E1DA),
        outline = Color(0xFFCAD4CC),
        outlineVariant = Color(0xFFDCE3DD),
        error = CalmCrimsonErrorLight,
        onError = CalmCrimsonOnErrorLight,
        errorContainer = CalmCrimsonContainerLight,
        onErrorContainer = CalmCrimsonOnContainerLight,
    ),
    darkScheme = darkColorScheme(
        primary = Color(0xFF96B8AC),
        onPrimary = Color(0xFF142720),
        primaryContainer = Color(0xFF233A32),
        onPrimaryContainer = Color(0xFFD7EAE2),
        secondary = Color(0xFF8AA89E),
        onSecondary = Color(0xFF162721),
        secondaryContainer = Color(0xFF20332C),
        onSecondaryContainer = Color(0xFFD3E4DD),
        tertiary = Color(0xFFC9AE79),
        onTertiary = Color(0xFF332610),
        tertiaryContainer = Color(0xFF47371B),
        onTertiaryContainer = Color(0xFFF0E1C4),
        background = Color(0xFF101613),
        onBackground = Color(0xFFE5EAE6),
        surface = Color(0xFF171F1B),
        onSurface = Color(0xFFE5EAE6),
        surfaceVariant = Color(0xFF212C26),
        onSurfaceVariant = Color(0xFFA7B5AC),
        surfaceContainerLowest = Color(0xFF0C110E),
        surfaceContainerLow = Color(0xFF141B17),
        surfaceContainer = Color(0xFF1B241F),
        surfaceContainerHigh = Color(0xFF232E28),
        surfaceContainerHighest = Color(0xFF2C3932),
        outline = Color(0xFF39473F),
        outlineVariant = Color(0xFF27332C),
        error = CalmCrimsonErrorDark,
        onError = CalmCrimsonOnErrorDark,
        errorContainer = CalmCrimsonContainerDark,
        onErrorContainer = CalmCrimsonOnContainerDark,
    ),
    lightExtended = lightExtendedWithHeader(
        headerStart = Color(0xFF243530),
        headerEnd = Color(0xFF2E433D),
    ),
    darkExtended = darkExtendedWithHeader(
        headerStart = Color(0xFF18231E),
        headerEnd = Color(0xFF202D27),
        headerOutline = Color(0xFF32453C),
    ),
    lightPreview = PaletteSwatchPreview(
        background = Color(0xFFF1F4F1),
        surface = Color(0xFFFAFBF9),
        header = Color(0xFF243530),
        accent = Color(0xFF48635B),
    ),
    darkPreview = PaletteSwatchPreview(
        background = Color(0xFF101613),
        surface = Color(0xFF171F1B),
        header = Color(0xFF18231E),
        accent = Color(0xFF96B8AC),
    ),
)

private val CharcoalSlateBundle = PaletteBundle(
    lightScheme = lightColorScheme(
        primary = Color(0xFF2A2D34),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE2E3E7),
        onPrimaryContainer = Color(0xFF181A1F),
        secondary = Color(0xFF4E525B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE8E9EC),
        onSecondaryContainer = Color(0xFF222429),
        tertiary = Color(0xFF8B6A3E),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFF2E6D4),
        onTertiaryContainer = Color(0xFF31220D),
        background = Color(0xFFF4F4F4),
        onBackground = Color(0xFF191A1D),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF191A1D),
        surfaceVariant = Color(0xFFE7E7EA),
        onSurfaceVariant = Color(0xFF565860),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF5F5F6),
        surfaceContainer = Color(0xFFEEEEF0),
        surfaceContainerHigh = Color(0xFFE7E7EA),
        surfaceContainerHighest = Color(0xFFDFDFE3),
        outline = Color(0xFFD0D1D6),
        outlineVariant = Color(0xFFE2E3E6),
        error = CalmCrimsonErrorLight,
        onError = CalmCrimsonOnErrorLight,
        errorContainer = CalmCrimsonContainerLight,
        onErrorContainer = CalmCrimsonOnContainerLight,
    ),
    darkScheme = darkColorScheme(
        primary = Color(0xFFB8BFC9),
        onPrimary = Color(0xFF1A1C21),
        primaryContainer = Color(0xFF2C2F37),
        onPrimaryContainer = Color(0xFFE4E6EA),
        secondary = Color(0xFF9DA3AE),
        onSecondary = Color(0xFF1A1C21),
        secondaryContainer = Color(0xFF262930),
        onSecondaryContainer = Color(0xFFDCDEE4),
        tertiary = Color(0xFFD2B27A),
        onTertiary = Color(0xFF34260E),
        tertiaryContainer = Color(0xFF473719),
        onTertiaryContainer = Color(0xFFF2E2C5),
        background = Color(0xFF0E0F12),
        onBackground = Color(0xFFE7E8EB),
        surface = Color(0xFF16181D),
        onSurface = Color(0xFFE7E8EB),
        surfaceVariant = Color(0xFF21242B),
        onSurfaceVariant = Color(0xFFA9ADB7),
        surfaceContainerLowest = Color(0xFF0A0B0E),
        surfaceContainerLow = Color(0xFF13151A),
        surfaceContainer = Color(0xFF1B1E24),
        surfaceContainerHigh = Color(0xFF23262E),
        surfaceContainerHighest = Color(0xFF2C3039),
        outline = Color(0xFF3B3F4A),
        outlineVariant = Color(0xFF292C35),
        error = CalmCrimsonErrorDark,
        onError = CalmCrimsonOnErrorDark,
        errorContainer = CalmCrimsonContainerDark,
        onErrorContainer = CalmCrimsonOnContainerDark,
    ),
    lightExtended = lightExtendedWithHeader(
        headerStart = Color(0xFF22252A),
        headerEnd = Color(0xFF2D3138),
    ),
    darkExtended = darkExtendedWithHeader(
        headerStart = Color(0xFF181B21),
        headerEnd = Color(0xFF21252D),
        headerOutline = Color(0xFF353A44),
    ),
    lightPreview = PaletteSwatchPreview(
        background = Color(0xFFF4F4F4),
        surface = Color(0xFFFFFFFF),
        header = Color(0xFF22252A),
        accent = Color(0xFF8B6A3E),
    ),
    darkPreview = PaletteSwatchPreview(
        background = Color(0xFF0E0F12),
        surface = Color(0xFF16181D),
        header = Color(0xFF181B21),
        accent = Color(0xFFB8BFC9),
    ),
)

private fun bundleFor(palette: AppThemePalette): PaletteBundle = when (palette) {
    AppThemePalette.NEUTRAL -> NeutralBundle
    AppThemePalette.WARM_IVORY -> WarmIvoryBundle
    AppThemePalette.SOFT_GRAY -> SoftGrayBundle
    AppThemePalette.MUTED_BLUE_GRAY -> MutedBlueGrayBundle
    AppThemePalette.SOFT_SAGE -> SoftSageBundle
    AppThemePalette.CHARCOAL_SLATE -> CharcoalSlateBundle
}

fun colorSchemeFor(palette: AppThemePalette, darkTheme: Boolean): ColorScheme {
    val bundle = bundleFor(palette)
    return if (darkTheme) bundle.darkScheme else bundle.lightScheme
}

fun extendedColorsFor(palette: AppThemePalette, darkTheme: Boolean): MuharrirExtendedColors {
    val bundle = bundleFor(palette)
    return if (darkTheme) bundle.darkExtended else bundle.lightExtended
}

fun paletteSwatchPreviewFor(palette: AppThemePalette, darkTheme: Boolean): PaletteSwatchPreview {
    val bundle = bundleFor(palette)
    return if (darkTheme) bundle.darkPreview else bundle.lightPreview
}

val LocalMuharrirExtendedColors = staticCompositionLocalOf {
    NeutralBundle.lightExtended
}

object MuharrirTheme {
    val extendedColors: MuharrirExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalMuharrirExtendedColors.current
}

@Composable
fun resolveIsDarkTheme(appearanceMode: AppearanceMode): Boolean = when (appearanceMode) {
    AppearanceMode.LIGHT -> false
    AppearanceMode.DARK -> true
    AppearanceMode.SYSTEM -> isSystemInDarkTheme()
}

val MuharrirShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun MuharrirTheme(
    appearanceMode: AppearanceMode = AppearanceMode.SYSTEM,
    themePalette: AppThemePalette = AppThemePalette.NEUTRAL,
    darkTheme: Boolean = resolveIsDarkTheme(appearanceMode),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        colorSchemeFor(palette = themePalette, darkTheme = darkTheme)
    }

    val extendedColors = extendedColorsFor(palette = themePalette, darkTheme = darkTheme)

    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalMuharrirExtendedColors provides extendedColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MuharrirTypography,
            shapes = MuharrirShapes,
            content = content,
        )
    }
}
