package com.tyust.course.ui.screen
import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tyust.course.academic.*
import com.tyust.course.ui.system.GlassOverlayHost
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class)
class WebsiteFiltersTest {
    @get:Rule val compose=createComposeRule()
    @Test fun closedStateHasNoManualSchoolParameterInputs() {
        compose.setContent { MaterialTheme { GlassOverlayHost {
            AcademicWebsiteFiltersDialog(null,"",false,emptyMap(),{},{},{},{},{})
        } } }
        compose.onNodeWithText("选课未开放，暂无法获取筛选条件").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("应用").assertIsNotEnabled()
    }
    @Test fun websiteOptionsAllowMultipleSelectionsAndApplyOnce() {
        val values=mutableStateOf<Map<String,List<String>>>(emptyMap());var applies=0
        compose.setContent { MaterialTheme { GlassOverlayHost {
            AcademicWebsiteFiltersDialog(CourseFilters("round","revision",listOf(CourseFilterGroup("college","学院","multi",
                listOf(CourseFilterOption("A","甲学院"),CourseFilterOption("B","乙学院"))))),"",false,values.value,
                {values.value=it},{},{},{applies++},{values.value=emptyMap()})
        } } }
        compose.onNodeWithText("甲学院").performClick();compose.onNodeWithText("乙学院").performClick()
        compose.onNodeWithText("应用").performClick()
        compose.runOnIdle { assertEquals(listOf("A","B"),values.value["college"]);assertEquals(1,applies) }
        compose.onNodeWithText("清空").performClick()
        compose.runOnIdle { assertTrue(values.value.isEmpty()) }
    }
}
