package com.tyust.course.academic.plugin

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PluginPersonalMapStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    private val store get() = PluginPersonalMapStore(context) { key }
    private val token = "zfmap1_" + "A".repeat(43)

    @Before fun before() { store.clear() }
    @After fun after() { store.clear() }

    @Test fun connectionSurvivesRecreationEncryptedAndDoesNotEnterPluginStorage() {
        assertNull(store.read())
        store.save(token)
        assertEquals(token, store.read())
        val folder = File(context.noBackupFilesDir, "native-plugin-vault/" + PluginJson.sha256("host:personal-map:v1".toByteArray()))
        assertFalse(folder.listFiles()!!.single().readText().contains(token))
        assertNull(NativePluginVault(context, "some-plugin-account", keyProvider = { key }).get("connection"))
        store.clear()
        assertNull(store.read())
    }

    @Test fun lostKeyFailsClosedInsteadOfSilentlySelectingThePublicPool() {
        store.save(token)
        val changed = PluginPersonalMapStore(context) { SecretKeySpec(ByteArray(32) { 9 }, "AES") }
        assertThrows(IOException::class.java) { changed.read() }
        assertEquals(token, store.read())
    }

    @Test fun webKeysAndMalformedCodesCannotReplaceAValidConnection() {
        store.save(token)
        for (invalid in listOf("a".repeat(32), "", token + "\n", "zfmap1_" + "!".repeat(43))) {
            assertThrows(IllegalArgumentException::class.java) { store.save(invalid) }
            assertEquals(token, store.read())
        }
    }
}
