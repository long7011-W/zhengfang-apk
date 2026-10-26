package com.tyust.course.academic.plugin

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.theme.CourseSelectorTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Nested task logs stay bounded as polling appends text, without moving the footer. */
class NativeLogScrollDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun growingLogScrollsInsideItsCard() {
        val count = mutableIntStateOf(20)
        compose.setContent {
            CourseSelectorTheme {
                val log = JSONObject().put("id", "log-text").put("type", "text")
                    .put("text", (1..count.intValue).joinToString("\n") { "[12:00:00] 测试日志第 $it 行" })
                val scroll = JSONObject().put("id", "log-scroll").put("type", "scroll").put("height", 220)
                    .put("children", JSONArray().put(log))
                val footer = JSONObject().put("id", "log-footer").put("type", "listItem").put("title", "完整任务日志")
                val card = JSONObject().put("id", "log-card").put("type", "column").put("surface", "glass")
                    .put("padding", 14).put("children", JSONArray().put(scroll).put(footer))
                val page = JSONObject().put("id", "page").put("type", "list").put("height", 480)
                    .put("children", JSONArray().put(card))
                NativePluginNode(page, null, Modifier) { _, _ -> }
            }
        }
        val log = compose.onNodeWithTag("native-node-log-scroll")
        log.assertHeightIsEqualTo(220.dp)
        val footer = compose.onNodeWithTag("native-node-log-footer")
        val top = footer.fetchSemanticsNode().boundsInRoot.top
        val initial = log.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        log.performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue(log.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > initial)
        compose.runOnIdle { count.intValue = 80 }
        log.assertHeightIsEqualTo(220.dp)
        footer.assertIsDisplayed()
        assertEquals(top, footer.fetchSemanticsNode().boundsInRoot.top, 1f)
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(compose.activity.getExternalFilesDir(null), "plugin-ui/log-scroll.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
