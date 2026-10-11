package com.tyust.course.ui.system
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tyust.course.manager.AppearanceSettingsManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,qualifiers="w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PickerVisibilityTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var renderedView: View
    private fun content(last: Boolean, fontScale: Float = 1f) {
        compose.setContent {
            val view=LocalView.current
            SideEffect { renderedView=view }
            MaterialTheme(colorScheme=androidx.compose.material3.lightColorScheme(primary=Color.Black)) { CompositionLocalProvider(
                LocalGlassAppearanceOverride provides WallpaperAppearanceColors.Light.copy(onSurface=Color.Black,onSurfaceVariant=Color.Black),
                LocalDensity provides Density(LocalDensity.current.density,fontScale)
            ) { GlassOverlayHost { Box(Modifier.fillMaxSize().background(Color.White)) {
                SystemPicker(options=(0..99).map { "学期选项 $it" }, selectedIndex=if(last)98 else 0,
                    onSelect={},label="学期", modifier=Modifier.width(280.dp),popupWidth=360.dp,
                    maxLabelLines=2,actionLabel="刷新列表",onAction={})
            } } } }
        }
    }
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


    @Test fun scrollAfterSettlingKeepsLastRowsOpaqueWithGlassOnAndOff() {
        content(false)
        compose.onNodeWithContentDescription("学期").performClick()
        compose.onNodeWithText("学期选项 99").performScrollTo()
        assertInk("学期选项 99")
        compose.runOnIdle { AppearanceSettingsManager.updateGlassEffect(false) }
        assertInk("学期选项 99")
        compose.runOnIdle { AppearanceSettingsManager.updateGlassEffect(true) }
        compose.onNodeWithText("刷新列表").performScrollTo()
        assertInk("刷新列表")
    }
    @Test fun selectedTailAndLargeFontRemainReadableAfterReopening() {
        content(true,1.8f)
        repeat(2) {
            compose.onNodeWithContentDescription("学期").performClick()
            compose.onNodeWithText("学期选项 99").performScrollTo()
            assertInk("学期选项 99")
            compose.onNodeWithContentDescription("学期").performClick()
        }
    }
}
