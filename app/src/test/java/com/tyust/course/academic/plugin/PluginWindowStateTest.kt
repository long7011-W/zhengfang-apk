package com.tyust.course.academic.plugin

import android.app.Application
import com.tyust.course.academic.AcademicSessionStore
import com.tyust.course.ui.system.windowWidthClass
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PluginWindowStateTest {
    @Test fun widthsUseWindowBoundsWithoutChangingPhoneClass() {
        for (width in listOf(360f,411f,599f)) assertEquals("compact", windowWidthClass(width))
        assertEquals("medium", windowWidthClass(600f)); assertEquals("medium", windowWidthClass(839f)); assertEquals("expanded", windowWidthClass(840f))
    }
    @Test fun pendingConsentSurvivesLauncherReattachmentAndCloseCancelsIt() = runTest {
        val state = PageInteraction()
        val first = async { state.consent("学校", "合成学校授权") }; runCurrent()
        val prompt = state.prompt!!
        state.launchFile = null; state.launchFile = {}
        assertSame(prompt, state.prompt); assertFalse(first.isCompleted)
        prompt.result.complete(JSONObject().put("choice", "remember"))
        assertEquals("remember", first.await())
        val next = async { state.confirm("操作", "合成内容") }; runCurrent(); state.close()
        assertTrue(runCatching { next.await() }.exceptionOrNull() is CancellationException)
    }
    @Test fun sharedRetainerKeepsOneSessionAndInvalidatesChangedPackageScope() {
        val app = RuntimeEnvironment.getApplication()
        val pkg = PluginPackage(PluginManifest(JSONObject("""{"id":"window.test","name":"Window","version":"1.0.0","apiVersion":3,"kind":"native","permissions":[],"network":[],"contributes":{"pages":[],"entries":[]}}""")), "", "synthetic", false)
        val owner = PluginPageRetainer(); var created = 0
        fun newPage(): PluginPageLifetime { created++; return PluginPageLifetime(app,pkg,AcademicSessionStore().session("school","account","https://synthetic.invalid")){true} }
        val first = owner.obtain("page","one",::newPage)
        assertSame(first, owner.obtain("page","one",::newPage)); assertEquals(1,created)
        val second = owner.obtain("page","two",::newPage)
        assertTrue(first.session.retired); assertFalse(second.session.retired)
        owner.release(second); assertTrue(second.session.retired)
    }
    @Test fun viewportIsReadWithoutPromptOnlyWhileTheNativePageIsForeground() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val p = PluginPackage(PluginManifest(JSONObject("""{"id":"viewport.test","name":"Viewport","version":"1.0.0","apiVersion":3,"kind":"native","permissions":["pages"],"network":[],"minAppVersionCode":96,"requires":[{"name":"ui.viewport","version":1}],"contributes":{"pages":[],"entries":[]}}""")),"","synthetic",false)
        val session = AcademicSessionStore().session("school","account","https://synthetic.invalid")
        var foreground = true
        val value = JSONObject().put("widthDp",840).put("heightDp",400).put("widthClass","expanded").put("fontScale",1.8)
        val host = NativeCapabilityHost(app,p,session,null,viewport={value.takeIf { foreground }}) { true }
        val effect = JSONObject().put("id","viewport").put("capability","ui.viewport").put("version",1).put("input",JSONObject())
        try {
            assertEquals(840,(host.execute(effect,NativeFlow(false)) as JSONObject).getInt("widthDp"))
            foreground = false
            assertEquals(PluginErrorCode.UNSUPPORTED,(runCatching { host.execute(effect,NativeFlow(false)) }.exceptionOrNull() as PluginException).code)
        } finally { host.close(); session.retire() }
    }
    @Test fun viewportAndSchoolClassificationCannotBeSelfPromotedByDisplayCategory() {
        val tool = JSONObject().put("kind","native").put("category","school")
        assertEquals(PluginDiscovery.Type.TOOL,PluginDiscovery.type(tool))
        val mixed = JSONObject(tool.toString()).put("capabilities",JSONArray(listOf("auth.start","ui.init")))
        assertEquals(PluginDiscovery.Type.ACADEMIC,PluginDiscovery.type(mixed))
    }
    @Test fun forumAndMixedPackagesUseFunctionalTypesWithoutDuplicateRows() {
        val forum = JSONObject().put("id","forum").put("kind","native")
            .put("servers",JSONArray().put(JSONObject().put("id","forum")))
        assertEquals(PluginDiscovery.Type.CAMPUS, PluginDiscovery.type(forum))
        assertTrue(PluginDiscovery.universal(forum))
        val mixed = JSONObject(forum.toString()).put("capabilities",JSONArray(listOf("auth.start")))
        assertEquals("教务适配 · 含校园服务",PluginDiscovery.typeLabel(mixed))
        assertEquals(1,PluginDiscovery.filter(listOf(mixed),null,"",false,type=PluginDiscovery.Type.ACADEMIC).size)
        assertTrue(PluginDiscovery.filter(listOf(mixed),null,"",false,type=PluginDiscovery.Type.CAMPUS).isEmpty())
    }
}
