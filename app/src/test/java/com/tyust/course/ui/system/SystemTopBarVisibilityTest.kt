package com.tyust.course.ui.system

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.tyust.course.manager.AppearanceSettingsManager
import com.tyust.course.manager.WallpaperPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Local native-canvas regression, not a claim of device/GPU acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemTopBarVisibilityTest {
    @get:Rule val compose = createComposeRule()
    private val collapse = mutableFloatStateOf(0f)
    private val title = mutableStateOf("设置")
    private val shown = mutableStateOf(true)
    private var clicks = 0
    private lateinit var renderedView: View

    @Before fun presetWithGlass() {
        AppearanceSettingsManager.updateWallpaper(WallpaperPreset.Aurora)
        AppearanceSettingsManager.updateGlassEffect(true)
    }

    @After fun restoreAppearance() {
        AppearanceSettingsManager.updateWallpaper(WallpaperPreset.Aurora)
        AppearanceSettingsManager.updateGlassEffect(true)
    }

    @Composable private fun Header() {
        val view = LocalView.current
        SideEffect { renderedView = view }
        val backdrop = rememberLayerBackdrop()
        MaterialTheme {
            CompositionLocalProvider(LocalGlassAppearanceOverride provides WallpaperAppearanceColors.Light.copy(
                onSurface = Color.Black, onSurfaceVariant = Color.Black
            )) {
                Box(Modifier.fillMaxWidth().background(Color.White)) {
                    Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(Color.White))
                    if (shown.value) key(title.value) {
                        SystemTopBar(title.value, "页面说明", collapse.floatValue,
                            navigationIcon = { Text("返回", Modifier.clickable { clicks++ }, color = Color.Black) },
                            actions = { Text("帮助", Modifier.clickable { clicks++ }, color = Color.Black) },
                            backdrop = backdrop)
                    }
                }
            }
        }
    }

    // Semantics and assertIsDisplayed alone accept alpha=0 ancestors. Draw the actual
    // Compose view into the local native canvas: PixelCopy's window-redraw callback
    // requires a device and does not run in this JVM environment.
    private fun assertInk(label: String) {
        val bounds = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(renderedView.width, renderedView.height, Bitmap.Config.ARGB_8888)
            try {
                renderedView.draw(Canvas(bitmap))
                var ink = 0
                for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(bitmap.height)) {
                    for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(bitmap.width)) {
                        val c = bitmap.getPixel(x, y)
                        if (android.graphics.Color.alpha(c) > 200 && maxOf(android.graphics.Color.red(c),
                                android.graphics.Color.green(c), android.graphics.Color.blue(c)) < 90) ink++
                    }
                }
                assertTrue("$label has no readable ink ($ink pixels)", ink > 12)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun expandedHeaderShowsTitleSubtitleAndActionsWithoutScrolling() {
        compose.setContent { Header() }
        listOf("设置", "页面说明", "返回", "帮助").forEach(::assertInk)
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("帮助").performClick()
        compose.runOnIdle { assertEquals(2, clicks) }
    }

    @Test fun scrollingAndReversingNeverFadesTheTitleWithTheGlass() {
        compose.setContent { Header() }
        for (fraction in listOf(0f, 0.2f, 0.5f, 1f, 0.3f, 0f)) {
            compose.runOnIdle { collapse.floatValue = fraction }
            compose.waitForIdle()
            listOf("设置", "返回", "帮助").forEach(::assertInk)
        }
        assertInk("页面说明")
    }

    @Test fun changingPagesAndReenteringAtRestKeepsTheHeaderVisible() {
        compose.setContent { Header() }
        for (page in listOf("设置", "抢课工作台", "问卷中心", "添加课程")) {
            compose.runOnIdle { shown.value = false }
            compose.runOnIdle { title.value = page; shown.value = true }
            assertInk(page)
            assertInk("页面说明")
        }
    }

    @Test fun togglingGlassAndCustomWallpaperKeepsForegroundVisible() {
        compose.setContent { Header() }
        compose.runOnIdle { AppearanceSettingsManager.updateGlassEffect(false) }
        assertInk("设置")
        compose.runOnIdle { AppearanceSettingsManager.updateCustomColor(Color.White, persist = false) }
        compose.runOnIdle { AppearanceSettingsManager.updateGlassEffect(true) }
        assertInk("设置")
        compose.runOnIdle { AppearanceSettingsManager.updateWallpaper(WallpaperPreset.Aurora) }
        assertInk("设置")
    }
}
