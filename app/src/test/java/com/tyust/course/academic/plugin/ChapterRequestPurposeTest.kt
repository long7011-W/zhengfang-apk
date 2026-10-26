package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicSessionStore
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ChapterRequestPurposeTest {
    private fun manifest(origin: String): PluginManifest {
        fun rule(path: String, methods: List<String>, purposes: List<String>) = JSONObject()
            .put("origin", origin).put("pathPrefix", path).put("methods", JSONArray(methods)).put("purposes", JSONArray(purposes))
        return PluginManifest(JSONObject().put("id", "test.chapter").put("version", "1.0.0").put("apiVersion", 3).put("kind", "native")
            .put("permissions", JSONArray(listOf("network"))).put("network", JSONArray(listOf(
                rule("/", listOf("GET"), listOf("query")),
                rule("/", listOf("POST"), listOf("query", "mutation")),
                rule("/ananas/job", listOf("GET"), listOf("mutation"))
            ))))
    }
    @Test fun readingQuestionsFollows302ButWritesRemainUnknownAndAreNeverReplayed() {
        MockWebServer().use { server ->
            server.start()
            val origin = server.url("/").toString().trimEnd('/')
            val manifest = manifest(origin)
            val policy = PluginNetworkPolicy(manifest.network)
            fun purpose(path: String, method: String): String = try {
                policy.requireAllowed(server.url(path), method, "mutation", null); "mutation"
            } catch (_: PluginException) { "query" }
            for (path in listOf("/mooc-ans/work/phone/work", "/mooc-ans/work/phone/work?jobid=")) {
                assertEquals("query", purpose(path, "GET"))
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/mooc-ans/work/phone/doHomeWork"))
                server.enqueue(MockResponse().setBody("synthetic questions"))
                val session = AcademicSessionStore().session("synthetic", "alice", origin)
                val host = PluginHost(PluginOperation(session, manifest, "host.effect"), File("build/chapter-test"))
                val result = host.call("http", JSONObject().put("url", server.url(path).toString()).put("purpose", purpose(path, "GET")))
                assertEquals("synthetic questions", result.getJSONObject("data").getString("body"))
            }
            for ((path, method) in listOf("/mooc-ans/work/addStudentWorkNew" to "POST", "/ananas/job/document" to "GET")) {
                assertEquals("mutation", purpose(path, method))
                val count = server.requestCount
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/done"))
                val session = AcademicSessionStore().session("synthetic", "alice", origin)
                val host = PluginHost(PluginOperation(session, manifest, "host.effect", confirmed = true), File("build/chapter-test"))
                val error = assertThrows(PluginException::class.java) {
                    host.call("http", JSONObject().put("url", server.url(path).toString()).put("method", method).put("purpose", "mutation"))
                }
                assertEquals(PluginErrorCode.RESULT_UNKNOWN, error.code)
                assertEquals(count + 1, server.requestCount)
            }
        }
    }
}
