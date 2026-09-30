package com.tyust.course.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.BottomNavItem
import com.tyust.course.BuildConfig
import com.tyust.course.ui.system.CapsuleNavigationBar
import com.tyust.course.ui.system.GlassWindowHost
import com.tyust.course.ui.theme.CourseSelectorTheme
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the actual bar, its hidden optical copy and its minimized presentation together. */
@RunWith(AndroidJUnit4::class)
class NavigationGlassRegressionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun isolatedVariant() = assumeTrue(BuildConfig.UI_PREVIEW)

    @Test fun rapidSwitchAndMinimizeRestoreTheSameRestingGlyphs() {
        val selected = mutableIntStateOf(0)
        val minimized = mutableStateOf(false)
        val dark = mutableStateOf(false)
        compose.setContent {
            CourseSelectorTheme(darkTheme = dark.value) {
                GlassWindowHost {
                    Box(Modifier.align(Alignment.BottomCenter).testTag("tested-navbar")) {
                        CapsuleNavigationBar(BottomNavItem.entries, selected.intValue, { selected.intValue = it },
                            minimized.value, { minimized.value = false })
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night; selected.intValue = 0 }
            settle()
            val initial = capture("$night-initial")
            for (index in listOf(2, 3, 1, 4, 0)) {
                compose.runOnIdle { selected.intValue = index }
                compose.mainClock.advanceTimeBy(96)
                capture("$night-switch-$index").recycle()
            }
            compose.runOnIdle { minimized.value = true }
            settle()
            capture("$night-minimized").recycle()
            compose.runOnIdle { minimized.value = false }
            settle()
            val restored = capture("$night-restored")
            assertTrue("Resting navbar changed size", initial.width == restored.width && initial.height == restored.height)
            var error = 0L
            for (y in 0 until initial.height) for (x in 0 until initial.width) {
                val a = initial.getPixel(x, y); val b = restored.getPixel(x, y)
                error += abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
                    abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
                    abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
            }
            assertTrue("Old animated glyph remains in the optical copy", error.toDouble() / (initial.width * initial.height * 3) < 4.0)
            initial.recycle(); restored.recycle()
        }
    }

    private fun settle() { compose.mainClock.advanceTimeBy(1600); Thread.sleep(500); compose.mainClock.advanceTimeBy(200) }
    private fun capture(name: String): Bitmap {
        val bitmap = compose.onNodeWithTag("tested-navbar").captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        val directory = File(compose.activity.getExternalFilesDir(null), "navigation-glass-regression").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }
}
