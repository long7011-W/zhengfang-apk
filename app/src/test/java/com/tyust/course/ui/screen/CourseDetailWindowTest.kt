package com.tyust.course.ui.screen

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tyust.course.schedule.ScheduleDetail
import com.tyust.course.ui.system.ScheduleBottomSheetState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Layout/semantics only. No claim about device GPU or optical rendering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w1000dp-h700dp")
class CourseDetailWindowTest {
    @get:Rule val compose = createComposeRule()
    @Test fun shortWideAndPhoneWindowsKeepCloseAndEditReachableWithLargeText() {
        val width = mutableStateOf(360.dp)
        val course = ScheduleCourseUi("模拟课程","合成教师","模拟教室",1,1,2,"1-2周",Color.Blue,isCustom=true,customId="test",
            details = listOf(ScheduleDetail("notes","备注","合成课程内容。".repeat(60))))
        compose.setContent {
            MaterialTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density,1.8f)) {
                    Box(Modifier.size(width.value,420.dp)) {
                        CourseDetailContent(CourseDetailUiState(course),remember { ScheduleBottomSheetState() },{},{},{},{},{},{})
                    }
                }
            }
        }
        for (size in listOf(360,411,600,840)) {
            compose.runOnIdle { width.value = size.dp }
            compose.onNodeWithContentDescription("关闭课程详情").assertIsDisplayed()
            compose.onNodeWithTag("course-detail-edit").assertIsDisplayed()
            val bounds = compose.onNodeWithTag("course-detail-surface").getUnclippedBoundsInRoot()
            assertTrue(bounds.right - bounds.left <= 560.dp)
            assertTrue(bounds.bottom <= 420.dp)
        }
    }
}
