package com.tyust.course.academic.plugin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyust.course.academic.AcademicSessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in live reads through the final package and production HTTP host. Never runs a userscript or writes progress/answers. */
@RunWith(AndroidJUnit4::class)
class PluginChapterReadLiveDeviceTest {
    @Test fun authorizedChapterEntryAndStandaloneHomeworkAreReadable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, "chapter-live.json")
        assumeTrue("No authorized read-only fixture supplied", input.isFile)
        val config = try { JSONObject(input.readText()) } finally { input.delete() }
        val pkg = AcademicProviderRegistry.packages().install(File(context.filesDir, "chapter-live.eduplugin").readBytes(), true)
        val session = AcademicSessionStore().session("synthetic-course-host", "dev:chapter-${UUID.randomUUID()}", "https://mooc1.chaoxing.com")
        val reports = JSONArray()
        try {
            for (item in PluginJson.objects(config.getJSONArray("cookies"))) {
                val domain = item.getString("domain").trimStart('.')
                require(domain == "chaoxing.com" || domain.endsWith(".chaoxing.com"))
                val cookie = Cookie.Builder().name(item.getString("name")).value(item.getString("value"))
                    .domain(domain).path(item.getString("path")).apply { if (item.optBoolean("secure")) secure() }.build()
                session.cookies.saveFromResponse(okhttp3.HttpUrl.Builder().scheme("https").host(domain).build(), listOf(cookie))
            }
            val policy = PluginNetworkPolicy(pkg.manifest.network)
            for (target in PluginJson.objects(config.getJSONArray("targets"))) {
                val url = target.getString("url").toHttpUrl()
                require(url.isHttps && (url.host == "chaoxing.com" || url.host.endsWith(".chaoxing.com")))
                val purpose = if (runCatching { policy.requireAllowed(url, "GET", "mutation", null) }.isSuccess) "mutation" else "query"
                assertEquals("Reading a question must never be classified as a write", "query", purpose)
                val operation = PluginOperation(session, pkg.manifest, "host.effect")
                val host = PluginHost(operation, context.cacheDir)
                val response = host.call("http", JSONObject().put("url", url.toString()).put("purpose", purpose)).getJSONObject("data")
                val doc = Jsoup.parse(response.getString("body"))
                assertEquals(200, response.getInt("status"))
                assertFalse(operation.mutationSent)
                assertFalse("Information page is not a completed quiz", doc.title().contains("信息提示"))
                val questions = doc.select(".questionLi,.question-item,.TiMu,input[name^=answer],textarea[name^=answer],.stem_title").size
                if (target.getString("kind") == "chapter") assertTrue("Chapter question content absent", questions > 0)
                else assertTrue("Standalone homework list absent", doc.select("a.current[data*=/work/getAllWork],input#cid").isNotEmpty()
                    && response.getString("body").contains("/work/doHomeWorkNew"))
                reports.put(JSONObject().put("kind", target.getString("kind")).put("noJobId", target.optBoolean("noJobId"))
                    .put("purpose", purpose).put("status", response.getInt("status")).put("questionsPresent", questions > 0).put("mutationSent", false))
            }
        } finally {
            File(context.getExternalFilesDir(null), "chapter-read-validation.json").writeText(reports.toString(2))
            session.retire()
        }
    }
}
