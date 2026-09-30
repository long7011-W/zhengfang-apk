package com.tyust.course.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.BuildConfig
import com.tyust.course.manager.AppearanceSettingsManager
import com.tyust.course.manager.WallpaperPreset
import com.tyust.course.manager.contrastRatio
import com.tyust.course.schedule.SemesterReminderSummary
import com.tyust.course.ui.screen.SemesterReminderSection
import com.tyust.course.ui.screen.WeekHeaderCompact
import com.tyust.course.ui.screen.MeasuredGradesHeader
import com.tyust.course.ui.system.*
import com.tyust.course.ui.theme.CourseSelectorTheme
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Checks actual text and surface pixels, including the picture/theme polarity mismatch. */
@RunWith(AndroidJUnit4::class)
class WallpaperReadabilityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun demoOnly() = assumeTrue(BuildConfig.UI_PREVIEW)
    @After fun resetDemoWallpaper() {
        compose.runOnUiThread { AppearanceSettingsManager.updateWallpaper(WallpaperPreset.Aurora) }
    }

    @Test fun floatingHeaderHasNoInteriorHorizontalSeam() {
        compose.setContent {
            CourseSelectorTheme(darkTheme = true) {
                GlassWindowHost {
                    Box(Modifier.padding(top = 48.dp, start = 16.dp, end = 16.dp)) {
                        HeaderGlassSlab(1f, requireNotNull(LocalControlBackdrop.current), 26.dp,
                            Modifier.fillMaxWidth().height(112.dp).testTag("plain-header-slab"))
                    }
                }
            }
        }
        importBackground("dark")
        val bitmap = compose.onNodeWithTag("plain-header-slab").captureToImage().asAndroidBitmap()
        capture("plain-header-slab")
        // Uniform wallpaper plus a soft reflection may vary gradually, but cannot
        // jump between adjacent rows in the unobstructed middle of the glass.
        val x = bitmap.width / 2
        for (y in bitmap.height / 3 until bitmap.height * 2 / 3) {
            val a = bitmap.getPixel(x, y)
            val b = bitmap.getPixel(x, y + 1)
            val jump = listOf(16, 8, 0).maxOf { shift ->
                kotlin.math.abs(((a ushr shift) and 255) - ((b ushr shift) and 255))
            }
            assertTrue("Hard horizontal seam at row $y: channel jump=$jump", jump <= 3)
        }
    }

    @Test fun cardsHeadersAndModalTextRemainReadableAcrossBackgrounds() {
        val dark = mutableStateOf(false)
        val page = mutableIntStateOf(0)
        val collapse = mutableFloatStateOf(0f)
        val dialog = mutableStateOf(false)
        compose.setContent {
            CourseSelectorTheme(darkTheme = dark.value) {
                GlassWindowHost {
                    Column(Modifier.fillMaxSize().testTag("wallpaper-page")) {
                        when (page.intValue) {
                            0 -> SystemTopBar("课表设置", "学期起始与节次时间", collapseFraction = collapse.floatValue)
                            1 -> WeekHeaderCompact(9, {}, {}, firstWeekDate = "2026-09-07",
                                sampleBackdrop = LocalControlBackdrop.current, collapseFraction = collapse.floatValue)
                            else -> MeasuredGradesHeader("本学期成绩", listOf("总体", "学期", "考试"), 1, {},
                                collapse.floatValue, LocalControlBackdrop.current, true, true, false, {}, {}, { _, _ -> })
                        }
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SemesterReminderSection("测试账号", "2026-2027-1", SemesterReminderSummary(12, 6, emptyMap()), true, {}, {})
                            SystemCard {
                                Text("卡片正文", Modifier.testTag("implicit-card-text"))
                                SystemSectionHeader("卡片内部标题", "内部说明")
                                SystemSecondaryButton("卡片按钮", {})
                            }
                            SystemSectionHeader("课程明细", "共 12 门课程")
                            LiquidPicker(listOf(LiquidPickerOption("标准"), LiquidPickerOption("紧凑")), 0, {},
                                modifier = Modifier.testTag("wallpaper-picker"))
                        }
                    }
                    if (dialog.value) SystemDialog(onDismissRequest = { dialog.value = false }, title = { Text("验证弹窗") }) {
                        Text("弹窗默认正文", Modifier.testTag("dialog-default"))
                        Text("弹窗显式正文", Modifier.testTag("dialog-explicit"), color = MaterialTheme.colorScheme.onSurface)
                        LiquidPicker(listOf(LiquidPickerOption("弹窗标准"), LiquidPickerOption("弹窗紧凑")), 0, {})
                    }
                }
            }
        }
        for (background in listOf("light", "dark", "grey", "mixed", "color", "landscape")) {
            importBackground(background)
            for (night in listOf(false, true)) {
                compose.runOnIdle { dark.value = night; page.intValue = 0; collapse.floatValue = 0f }
                settle()
                assertTextContrast(compose.onNodeWithText("账号：测试账号", true))
                assertTextContrast(compose.onNodeWithTag("implicit-card-text", true))
                assertTextContrast(compose.onNodeWithText("卡片内部标题", true))
                assertTextContrast(compose.onNodeWithText("课表设置", true))
                capture("$background-$night-settings")
                compose.onNodeWithTag("wallpaper-picker").performClick(); settle()
                assertTextContrast(compose.onNodeWithText("紧凑", true))
                capture("$background-$night-picker")
                compose.onNodeWithText("紧凑", true).performClick(); settle()
                for (screen in 1..2) {
                    compose.runOnIdle { page.intValue = screen }; settle()
                    if (screen == 2) assertTextContrast(compose.onNodeWithText("本学期成绩", true))
                    if (screen == 1) assertScheduleDatesReadable()
                    capture("$background-$night-header-$screen")
                    compose.runOnIdle { collapse.floatValue = 1f }; settle()
                    if (screen == 1) assertScheduleDatesReadable()
                    capture("$background-$night-header-$screen-collapsed")
                    compose.runOnIdle { collapse.floatValue = 0f }
                }
                compose.runOnIdle { dialog.value = true }; settle()
                assertTextContrast(compose.onNodeWithTag("dialog-default", true))
                assertTextContrast(compose.onNodeWithTag("dialog-explicit", true))
                capture("$background-$night-dialog")
                compose.runOnIdle { dialog.value = false }; settle()
            }
        }
    }

    private fun settle() { compose.waitForIdle(); Thread.sleep(160) }
    private fun assertScheduleDatesReadable() {
        for (day in 1..7) {
            assertTextContrast(compose.onNodeWithTag("schedule-day-number-$day", true))
            assertTextContrast(compose.onNodeWithTag("schedule-weekday-label-$day", true))
        }
    }
    private fun capture(name: String) {
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(compose.activity.getExternalFilesDir(null), "wallpaper-readability").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun assertTextContrast(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val foreground = layout.layoutInput.style.color.toArgb()
        val bitmap = node.captureToImage().asAndroidBitmap()
        // Text has a line box with blank pixels above/below its glyphs. Those are
        // the actual rendered surface, including wallpaper and all translucent layers.
        val y = bitmap.height - 1
        val samples = (0 until bitmap.width step maxOf(1, bitmap.width / 12)).map { bitmap.getPixel(it, y) }
        val minimum = samples.minOf { contrastRatio(foreground, it) }
        assertTrue("Unreadable ${layout.layoutInput.text}: contrast=$minimum", minimum >= 4.4)
    }

    private fun importBackground(kind: String) {
        val bitmap = Bitmap.createBitmap(96, 192, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val color = when (kind) {
                "light" -> 0xfffafaff.toInt()
                "dark" -> 0xff10234b.toInt()
                "grey" -> 0xff777777.toInt()
                "mixed" -> if ((x / 12 + y / 12) % 2 == 0) 0xff08080b.toInt() else 0xfffafaff.toInt()
                "color" -> listOf(0xffff4545.toInt(), 0xff12b886.toInt(), 0xff3b5bdb.toInt(), 0xffffe066.toInt())[(x / 24 + y / 48) % 4]
                else -> if (y < 72) android.graphics.Color.rgb(175 + y / 3, 200 + y / 4, 240)
                    else android.graphics.Color.rgb(12 + (x + y) % 23, 42 + (x * 3 + y) % 50, 90 + (x + y * 2) % 65)
            }
            bitmap.setPixel(x, y, color)
        }
        val file = File(compose.activity.cacheDir, "readability-$kind.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val done = AtomicBoolean(false)
        compose.runOnIdle {
            AppearanceSettingsManager.updateImageAdjust(dim = 0f, blur = 0f)
            AppearanceSettingsManager.importImageWallpaper(compose.activity, Uri.fromFile(file)) { assertTrue(it); done.set(true) }
        }
        compose.waitUntil(10_000) { done.get() }
        settle()
    }
}
