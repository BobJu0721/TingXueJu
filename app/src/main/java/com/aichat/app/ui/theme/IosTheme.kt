package com.aichat.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Apple iOS system palette (Human Interface Guidelines).
 * Values follow UIColor system semantics for light / dark appearances.
 */
object Ios {
    // systemBlue
    val BlueLight = Color(0xFF007AFF)
    val BlueDark = Color(0xFF0A84FF)

    // systemGreen
    val GreenLight = Color(0xFF34C759)
    val GreenDark = Color(0xFF30D158)

    // systemRed
    val RedLight = Color(0xFFFF3B30)
    val RedDark = Color(0xFFFF453A)

    // systemGroupedBackground / secondarySystemGroupedBackground
    val GroupedBackgroundLight = Color(0xFFF2F2F7)
    val GroupedBackgroundDark = Color(0xFF000000)
    val CardLight = Color(0xFFFFFFFF)
    val CardDark = Color(0xFF1C1C1E)
    val CardElevatedLight = Color(0xFFFFFFFF)
    val CardElevatedDark = Color(0xFF2C2C2E)

    // labels & separators
    val SecondaryLabelLight = Color(0x993C3C43)
    val SecondaryLabelDark = Color(0x99EBEBF5)
    val TertiaryLabelLight = Color(0x4D3C3C43)
    val TertiaryLabelDark = Color(0x4DEBEBF5)
    val SeparatorLight = Color(0xFFC6C6C8)
    val SeparatorDark = Color(0xFF38383A)

    // systemFill equivalents (solid approximations)
    val FillLight = Color(0xFFE5E5EA)
    val FillDark = Color(0xFF2C2C2E)
    val FillTranslucentLight = Color(0x1F787880)
    val FillTranslucentDark = Color(0x33787880)

    // bar material (translucent approximation of UIBlurEffect bars)
    val BarLight = Color(0xF2F9F9F9)
    val BarDark = Color(0xF2131315)

    fun bar(dark: Boolean): Color = if (dark) BarDark else BarLight
}

/**
 * Maps the iOS system palette onto Material 3 roles so existing screens
 * re-skin without structural changes:
 *  - primary          -> systemBlue (tint / actions / selected states)
 *  - tertiary         -> systemGreen (iOS switches)
 *  - error            -> systemRed
 *  - background       -> grouped background (#F2F2F7 / #000)
 *  - surface          -> grouped card (#FFF / #1C1C1E)
 *  - outline(Variant) -> separator hairline
 */
fun iosColorScheme(dark: Boolean): ColorScheme {
    val blue = if (dark) Ios.BlueDark else Ios.BlueLight
    val green = if (dark) Ios.GreenDark else Ios.GreenLight
    val red = if (dark) Ios.RedDark else Ios.RedLight
    val label = if (dark) Color.White else Color.Black
    val secondaryLabel = if (dark) Ios.SecondaryLabelDark else Ios.SecondaryLabelLight
    val card = if (dark) Ios.CardDark else Ios.CardLight
    val cardElevated = if (dark) Ios.CardElevatedDark else Ios.CardElevatedLight
    val fill = if (dark) Ios.FillDark else Ios.FillLight
    val separator = if (dark) Ios.SeparatorDark else Ios.SeparatorLight
    val groupedBackground = if (dark) Ios.GroupedBackgroundDark else Ios.GroupedBackgroundLight

    return if (dark) {
        darkColorScheme(
            primary = blue,
            onPrimary = Color.White,
            primaryContainer = blue,
            onPrimaryContainer = Color.White,
            inversePrimary = blue,
            secondary = blue,
            onSecondary = Color.White,
            secondaryContainer = Ios.FillDark,
            onSecondaryContainer = label,
            tertiary = green,
            onTertiary = Color.White,
            tertiaryContainer = Ios.CardElevatedDark,
            onTertiaryContainer = label,
            background = groupedBackground,
            onBackground = label,
            surface = card,
            onSurface = label,
            surfaceVariant = cardElevated,
            onSurfaceVariant = secondaryLabel,
            error = red,
            onError = Color.White,
            errorContainer = Ios.CardElevatedDark,
            onErrorContainer = red,
            outline = separator,
            outlineVariant = separator,
            scrim = Color.Black,
            surfaceBright = Ios.CardElevatedDark,
            surfaceDim = Ios.GroupedBackgroundDark,
            surfaceContainerLowest = groupedBackground,
            surfaceContainerLow = card,
            surfaceContainer = card,
            surfaceContainerHigh = cardElevated,
            surfaceContainerHighest = cardElevated,
        )
    } else {
        lightColorScheme(
            primary = blue,
            onPrimary = Color.White,
            primaryContainer = blue,
            onPrimaryContainer = Color.White,
            inversePrimary = blue,
            secondary = blue,
            onSecondary = Color.White,
            secondaryContainer = Ios.FillLight,
            onSecondaryContainer = label,
            tertiary = green,
            onTertiary = Color.White,
            tertiaryContainer = Ios.CardLight,
            onTertiaryContainer = label,
            background = groupedBackground,
            onBackground = label,
            surface = card,
            onSurface = label,
            surfaceVariant = fill,
            onSurfaceVariant = secondaryLabel,
            error = red,
            onError = Color.White,
            errorContainer = Ios.CardLight,
            onErrorContainer = red,
            outline = separator,
            outlineVariant = separator,
            scrim = Color.Black,
            surfaceBright = Ios.CardLight,
            surfaceDim = groupedBackground,
            surfaceContainerLowest = groupedBackground,
            surfaceContainerLow = card,
            surfaceContainer = card,
            surfaceContainerHigh = cardElevated,
            surfaceContainerHighest = cardElevated,
        )
    }
}
