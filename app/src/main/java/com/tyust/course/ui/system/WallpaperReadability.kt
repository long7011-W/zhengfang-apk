package com.tyust.course.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.tyust.course.manager.AppearanceSettingsManager
import com.tyust.course.manager.WallpaperMode
import com.tyust.course.ui.theme.DarkColorScheme
import com.tyust.course.ui.theme.LightColorScheme

/** A themed card and a transparent wallpaper control have different foreground contracts. */
internal val LocalThemedContent = staticCompositionLocalOf { false }
internal val LocalGlassAppearanceOverride = staticCompositionLocalOf<WallpaperAppearanceColors?> { null }

@Composable
internal fun themedSurfaceAppearance(): WallpaperAppearanceColors {
    val colors = MaterialTheme.colorScheme
    return WallpaperAppearanceColors(colors.surface, colors.surface, colors.onSurface,
        colors.onSurfaceVariant, colors.outlineVariant, !rememberGlassDarkTheme())
}

@Composable
internal fun ProvideThemedContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalThemedContent provides true,
        LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
}

@Composable
internal fun rememberReadableContentAppearance(region: WallpaperRegionState? = null): WallpaperAppearanceColors =
    if (LocalThemedContent.current) themedSurfaceAppearance() else rememberWallpaperRegionAppearance(region)

/** The palette of whatever a control sits on: the theme in cards and dialogs, else the nearest wallpaper sample. */
@Composable
internal fun localReadableAppearance(): WallpaperAppearanceColors =
    if (LocalThemedContent.current) themedSurfaceAppearance() else LocalWallpaperAppearanceColors.current

/**
 * Accent for text drawn on [colors]. A tone-mapped backing only guarantees contrast for the
 * near-black or near-white foreground; blue on a backing that barely separates black text from a
 * checkerboard is about 1.4:1. Custom wallpapers therefore keep the foreground, while preset
 * wallpapers keep the brand accent in the polarity their backing actually has.
 */
@Composable
internal fun readableAccent(colors: WallpaperAppearanceColors): Color = when {
    LocalThemedContent.current -> MaterialTheme.colorScheme.primary
    AppearanceSettingsManager.mode != WallpaperMode.Preset -> colors.onSurface
    colors.usesDarkForeground -> LightColorScheme.primary
    else -> DarkColorScheme.primary
}

/**
 * Backing alpha that keeps 4.5:1 for either foreground over any pixel: white text over black needs
 * 0.52 of the dark backing, near-black text over white needs 0.545 of the light one.
 */
internal const val ReadableAnyBackdropAlpha = 0.56f

/** Only free-standing content needs a backing; nested cards already supply one. */
@Composable
internal fun Modifier.readableWallpaper(
    colors: WallpaperAppearanceColors,
    shape: Shape = RoundedCornerShape(12.dp)
): Modifier = if (!LocalThemedContent.current && AppearanceSettingsManager.mode != WallpaperMode.Preset)
    background(colors.surface, shape) else this

@Composable
internal fun WallpaperCaption(text: String, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall) {
    val region = rememberWallpaperRegionState()
    val colors = rememberReadableContentAppearance(region)
    Text(text, modifier.wallpaperRegion(region).readableWallpaper(colors).padding(horizontal = 6.dp, vertical = 3.dp),
        color = colors.onSurfaceVariant, style = style)
}
