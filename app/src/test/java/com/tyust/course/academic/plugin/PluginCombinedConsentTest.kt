package com.tyust.course.academic.plugin

import android.app.Application
import android.net.Uri
import com.tyust.course.academic.AcademicSessionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PluginCombinedConsentTest {
    private val app = RuntimeEnvironment.getApplication()
    private val destination = "https://synthetic.example/view"
    private fun pkg() = PluginPackage(PluginManifest(JSONObject("""{
      "id":"synthetic.combined","version":"1.0.0","name":"模拟工具","kind":"native","apiVersion":3,
      "permissions":["academic.read","navigation"],"contributes":{"pages":[],"entries":[]},
      "network":[{"origin":"https://synthetic.example","pathPrefix":"/","methods":["GET"],"purposes":["query"]}],
      "dataDisclosure":[{"origin":"https://synthetic.example","categories":["academic"],"purpose":"合成数据预览"}]
    }""")), "", "synthetic", false)
    private fun interaction(confirm: (String, String) -> Boolean) = object : NativePluginInteraction {
        override suspend fun confirm(title: String, message: String) = confirm.invoke(title,message)
        override suspend fun authenticate(challenge: JSONObject, image: File?): JSONObject? = null
        override suspend fun pick(types: Array<String>): Uri? = null
        override suspend fun notificationPermission() = false
        override fun haptic() {}
        override fun navigate(pageId: String, params: JSONObject) {}
        override fun back() {}
    }
    @Test fun firstExternalOpenCombinesRecipientAndActionInOnePrompt() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val p = pkg(); var prompts = 0
        val session = AcademicSessionStore().session("school","account","https://school.invalid")
        val host = NativeCapabilityHost(app,p,session,interaction { title,message ->
            prompts++; assertEquals("打开网页",title); assertTrue(message.contains("synthetic.example")); assertTrue(message.contains("合成数据预览")); true
        }) { true }
        try {
            host.execute(JSONObject().put("id","open").put("capability","navigation.url").put("version",1).put("input",JSONObject().put("url",destination)),NativeFlow(true))
            assertEquals(1,prompts); assertTrue(PluginDataGuard(app,p).allowed(destination.toHttpUrl()))
        } finally { host.close(); session.retire(); Dispatchers.resetMain() }
    }
    @Test fun cancellationOrRevocationDuringTheCombinedPromptCannotSaveConsentOrOpen() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            for (revoke in listOf(false,true)) {
                val p=pkg(); PluginDataGuard.revoke(app,p.manifest.id)
                val session=AcademicSessionStore().session("school","account","https://school.invalid")
                val host=NativeCapabilityHost(app,p,session,interaction { _,_ -> if(revoke) PluginDataGuard.revoke(app,p.manifest.id); revoke }) { true }
                try {
                    val error=runCatching { host.execute(JSONObject().put("id","open").put("capability","navigation.url").put("version",1).put("input",JSONObject().put("url",destination)),NativeFlow(true)) }.exceptionOrNull()
                    assertTrue(error is PluginException); assertFalse(PluginDataGuard(app,p).allowed(destination.toHttpUrl()))
                    assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedActivity)
                } finally { host.close(); session.retire() }
            }
        } finally { Dispatchers.resetMain() }
    }
}
