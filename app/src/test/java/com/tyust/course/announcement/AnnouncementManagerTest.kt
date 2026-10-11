package com.tyust.course.announcement

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AnnouncementManagerTest {
    @Test fun preservesExistingIdsAndSupportsHistoryEnvelope() {
        val result = AnnouncementManager.validateDocument("""{"announcements":[{"id":"old","title":"旧公告","content":"正文","showOnce":true},{"id":"new","title":"新公告","content":"正文"}]}""")
        assertEquals(listOf("old", "new"), result.map { it.id })
    }
    @Test fun acceptsLegacySingleAnnouncement() {
        assertEquals("legacy", AnnouncementManager.validateDocument("""{"id":"legacy","title":"标题","content":"正文"}""").single().id)
    }
    @Test fun rejectsErrorDocumentInsteadOfClearingHistory() {
        assertThrows(Exception::class.java) { AnnouncementManager.validateDocument("""{"error":"unavailable"}""") }
        assertThrows(Exception::class.java) { AnnouncementManager.validateDocument("<html>bad gateway</html>") }
    }
    @Test fun rejectsOversizedDocument() {
        assertThrows(Exception::class.java) { AnnouncementManager.validateDocument(" ".repeat(256 * 1024 + 1)) }
    }
}
