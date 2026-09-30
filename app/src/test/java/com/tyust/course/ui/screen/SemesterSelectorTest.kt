package com.tyust.course.ui.screen

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tyust.course.ui.system.GlassOverlayHost
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class SemesterSelectorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyFailedCatalogCanBeRetriedThenAnOpaqueHistoricalTermSelected() {
        val terms = mutableStateOf(emptyList<String>())
        val error = mutableStateOf("学校暂时不可用")
        val selected = mutableStateOf("")
        var refreshes = 0
        compose.setContent {
            MaterialTheme {
                GlassOverlayHost {
                    Box(Modifier.fillMaxSize()) {
                        SemesterSelector(terms.value, mapOf("opaque-current" to "当前学期", "opaque-old" to "历史学期"),
                            selected.value, { selected.value = it }, error = error.value, onRefresh = {
                                refreshes++
                                terms.value = listOf("opaque-current", "opaque-old")
                                error.value = ""
                                selected.value = "opaque-current"
                            })
                    }
                }
            }
        }
        compose.onNodeWithText("学期列表加载失败：学校暂时不可用").assertIsDisplayed()
        compose.onNodeWithContentDescription("学期").assertIsEnabled().performClick()
        compose.onNodeWithText("刷新学期列表").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, refreshes) }
        compose.onNodeWithContentDescription("学期").performClick()
        compose.onNodeWithText("历史学期").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("opaque-old", selected.value) }
    }

    @Test fun loadingACatalogRetainsTheAlreadyAvailableChoices() {
        var selected = "current"
        compose.setContent {
            MaterialTheme {
                GlassOverlayHost {
                    Box(Modifier.fillMaxSize()) {
                        SemesterSelector(listOf("current", "old"), mapOf("current" to "当前学期", "old" to "历史学期"),
                            selected, { selected = it }, isLoading = true, onRefresh = {})
                    }
                }
            }
        }
        compose.onNodeWithText("正在加载学期列表…").assertIsDisplayed()
        compose.onNodeWithContentDescription("学期").assertIsEnabled().performClick()
        compose.onNodeWithText("历史学期").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("old", selected) }
    }

    @Test fun anEmptyLoadingCatalogDoesNotOfferDuplicateRefreshes() {
        val loading = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                GlassOverlayHost {
                    Box(Modifier.fillMaxSize()) {
                        SemesterSelector(emptyList(), emptyMap(), "", {}, isLoading = loading.value, onRefresh = {})
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("学期").assertIsNotEnabled()
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithText("学校暂未提供可选学期，请刷新学期列表重试。").assertIsDisplayed()
        compose.onNodeWithContentDescription("学期").assertIsEnabled()
    }
}
