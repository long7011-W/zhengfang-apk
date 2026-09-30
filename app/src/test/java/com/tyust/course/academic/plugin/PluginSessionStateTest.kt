package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = android.app.Application::class)
class PluginSessionStateTest {
    private val sessions = AcademicSessionStore()
    private val root = File(org.robolectric.RuntimeEnvironment.getApplication().cacheDir, "session-state-test")
    private val manifest = PluginManifest(JSONObject().put("id", "test.course-cache").put("version", "1.0.0")
        .put("kind", "independent").put("apiVersion", 3).put("capabilities", JSONArray()).put("network", JSONArray()))
    private fun session(account: String = "student") = sessions.session("school", account, "https://school.example/")
    private fun host(session: AcademicSession = session(), id: String = manifest.id) = PluginHost(
        PluginOperation(session, PluginManifest(JSONObject(manifest.json.toString()).put("id", id)), "selection.courses"), root)
    private fun set(host: PluginHost, key: String, value: Any, store: String = "state") =
        host.call("$store.set", JSONObject().put("key", key).put("value", value))
    private fun get(host: PluginHost, key: String, store: String = "state") =
        host.call("$store.get", JSONObject().put("key", key)).get("data")

    @Test fun largeCoursePagesRemainAvailableAcrossCallsWithoutChangingPersistentStorage() {
        val courses = JSONArray().apply { repeat(600) { index ->
            put(JSONObject().put("id", "course-$index").put("sectionId", "section-$index")
                .put("name", "课程教学班资料".repeat(40)))
        } }
        assertTrue(courses.toString().toByteArray(Charsets.UTF_8).size > 256 * 1024)
        set(host(), "account", "student")
        set(host(), "courses", courses)
        set(host(), "next-page", JSONArray().put("last-course"))
        val restored = get(host(), "courses") as JSONArray
        assertEquals(600, restored.length())
        assertEquals("section-0", restored.getJSONObject(0).getString("sectionId"))
        assertEquals("section-599", restored.getJSONObject(599).getString("sectionId"))
        assertEquals("student", get(host(), "account"))
        assertEquals(JSONObject.NULL, get(host(), "courses", "storage"))
    }

    @Test fun aggregateQuotaFailurePreservesOldValuesAndRemovingDataRestoresCapacity() {
        // UTF-8 bytes, not UTF-16 characters: each value is roughly 4.5 MiB.
        val page = "课".repeat(1_500_000)
        set(host(), "first", page)
        set(host(), "second", "old-value")
        val error = assertThrows(PluginException::class.java) { set(host(), "second", page) }
        assertEquals(PluginErrorCode.RESOURCE_LIMIT, error.code)
        assertEquals("old-value", get(host(), "second"))
        assertEquals(page, get(host(), "first"))
        host().call("state.remove", JSONObject().put("key", "first"))
        set(host(), "second", page)
        assertEquals(page, get(host(), "second"))
    }

    @Test fun persistentQuotaStaysSmallAndFailedWriteDoesNotReplaceSavedValue() {
        set(host(), "settings", "saved", "storage")
        val error = assertThrows(PluginException::class.java) {
            set(host(), "settings", "课".repeat(90_000), "storage")
        }
        assertEquals(PluginErrorCode.RESOURCE_LIMIT, error.code)
        assertEquals("saved", get(host(), "settings", "storage"))
        host().call("storage.remove", JSONObject().put("key", "settings"))
    }

    @Test fun cachedCoursesAreIsolatedAndExpireWithTheirSession() {
        val first = session()
        set(host(first), "courses", "private-courses")
        assertEquals(JSONObject.NULL, get(host(session("other")), "courses"))
        assertEquals(JSONObject.NULL, get(host(first, "other.plugin"), "courses"))
        val stale = host(first)
        first.invalidate()
        assertThrows(PluginException::class.java) { get(stale, "courses") }
        assertEquals(JSONObject.NULL, get(host(first), "courses"))
    }
}
