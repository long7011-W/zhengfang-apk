package com.tyust.course.update

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class UpdateTransferTest {
    @get:Rule val temp = TemporaryFolder()
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: UpdateFailure) {} }
    private fun transfer(server: MockWebServer) = UpdateTransfer(UpdateTransfer.client(), { it.startsWith(server.url("/").toString()) })
    @Test fun freshDownload() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdef")); val file = temp.newFile()
            transfer(server).download(UpdateFixtures.manifest(), UpdateMirror("test", "test", server.url("/apk").toString()), file, null, 2000) { _, _ -> }
            assertEquals("abcdef", file.readText()); assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }
    @Test fun partialContentResumesExactly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6").setHeader("ETag", "\"same\"").setBody("def"))
            val file = temp.newFile().apply { writeText("abc") }
            transfer(server).download(UpdateFixtures.manifest(), UpdateMirror("test", "test", server.url("/apk").toString()), file, "\"same\"", 2000) { _, _ -> }
            assertEquals("abcdef", file.readText()); val request = server.takeRequest()
            assertEquals("bytes=3-", request.getHeader("Range")); assertEquals("\"same\"", request.getHeader("If-Range"))
        }
    }
    @Test fun ignoredRangeReplacesPartialRatherThanAppend() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdef")); val file = temp.newFile().apply { writeText("abc") }
            transfer(server).download(UpdateFixtures.manifest(), UpdateMirror("test", "test", server.url("/apk").toString()), file, null, 2000) { _, _ -> }
            assertEquals("abcdef", file.readText())
        }
    }
    @Test fun invalidRangeNeverAppended() {
        listOf("bytes 0-2/6", "bytes 3-5/7", "bytes 3-9/6", "garbage").forEach { rejected { UpdateTransfer.validateRange(it, 3, 6) } }
    }
    @Test fun htmlRejected() { MockWebServer().use { server ->
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("abcdef")); val file = temp.newFile()
        rejected { runBlocking { transfer(server).download(UpdateFixtures.manifest(), UpdateMirror("test", "test", server.url("/apk").toString()), file, null, 2000) { _, _ -> } } }
    } }
    @Test fun changedEtagDiscardsPartial() { MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6").setHeader("ETag", "\"changed\"").setBody("def"))
        val file = temp.newFile().apply { writeText("abc") }
        rejected { runBlocking { transfer(server).download(UpdateFixtures.manifest(), UpdateMirror("test", "test", server.url("/apk").toString()), file, "\"same\"", 2000) { _, _ -> } } }
        assertFalse(file.exists()) }
    }
    @Test fun range416DiscardsStalePartial() { MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(416)); val file = temp.newFile().apply { writeText("abc") }
        rejected { runBlocking { transfer(server).download(UpdateFixtures.manifest(), UpdateMirror("test", "test", server.url("/apk").toString()), file, null, 2000) { _, _ -> } } }
        assertFalse(file.exists()) }
    }
    @Test fun redirectChecksDestination() { MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://untrusted.invalid/apk"))
        rejected { runBlocking { transfer(server).download(UpdateFixtures.manifest(), UpdateMirror("test", "test", server.url("/apk").toString()), temp.newFile(), null, 2000) { _, _ -> } } } }
    }
    @Test fun slowResponseHasWholeCallDeadline() { MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("abcdef").setBodyDelay(1, TimeUnit.SECONDS))
        try { runBlocking { transfer(server).download(UpdateFixtures.manifest(), UpdateMirror("test", "test", server.url("/apk").toString()), temp.newFile(), null, 50) { _, _ -> } }; fail("timeout") }
        catch (_: java.io.IOException) {} }
    }
}
