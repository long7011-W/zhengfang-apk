package com.tyust.course.login

import android.app.Application
import com.tyust.course.academic.AcademicGatewayFactory
import com.tyust.course.academic.AcademicPasswordLoginGateway
import com.tyust.course.academic.plugin.AcademicProviderRegistry
import com.tyust.course.academic.plugin.BundledAcademicProviders
import com.tyust.course.academic.plugin.GenericAcademicProtocols
import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class PasswordLoginGatewayFactoryTest {
    @Before fun initializePlugins() {
        AcademicProviderRegistry.initialize(RuntimeEnvironment.getApplication())
    }

    @Test
    fun createsTyustSsoGatewayOnlyForTyust() {
        val tyust = SchoolConfig("tyust", "TYUST", "newjwc.tyust.edu.cn", "https")
        val other = SchoolConfig("other", "Other", "jw.example.edu.cn", "https")

        assertTrue(PasswordLoginGatewayFactory.create(tyust) is TyustSsoLoginManager)
        assertTrue(PasswordLoginGatewayFactory.create(other) is com.tyust.course.academic.AcademicPasswordLoginGateway)
    }

    @Test
    fun createsZjutSsoGatewayOnlyForZjut() {
        val zjut = SchoolConfig("zjut", "浙江工业大学", "www.gdjw.zjut.edu.cn", "http")
        val other = SchoolConfig("other", "Other", "jw.example.edu.cn", "https")

        assertTrue(PasswordLoginGatewayFactory.create(zjut) is ZjutSsoLoginManager)
        assertTrue(PasswordLoginGatewayFactory.create(other) is com.tyust.course.academic.AcademicPasswordLoginGateway)
    }

    @Test fun selectingOrRestoringTheGenericProtocolKeepsSpecializedSso() {
        val school = SchoolConfig("tyust", "TYUST", "newjwc.tyust.edu.cn", "https")
        for (choice in listOf("builtin.auto", "builtin.zf", "org.zf.protocol.zf")) {
            AcademicProviderRegistry.choose(school, choice)
            assertTrue(PasswordLoginGatewayFactory.create(school) is TyustSsoLoginManager)
        }
    }

    @Test fun genericAndSchoolSpecificBundledProvidersKeepPluginLogin() {
        for (system in GenericAcademicProtocols.providers.keys) {
            val school = SchoolConfig("generic-$system", "Test", "school.example", "https").apply { academicSystem = system }
            assertTrue(PasswordLoginGatewayFactory.create(school) is AcademicPasswordLoginGateway)
        }
        for (definition in BundledAcademicProviders.definitions) {
            val school = SchoolConfig("bundled", "Test", definition.host, "https").apply { academicSystem = definition.system.id }
            assertTrue(PasswordLoginGatewayFactory.create(school) is AcademicPasswordLoginGateway)
        }
    }

    @Test fun explicitlySelectedSchoolPluginCanStillOverrideSpecializedSso() = runBlocking {
        val school = SchoolConfig("tyust", "TYUST", "newjwc.tyust.edu.cn", "https")
        val manifest = JSONObject().put("id", "test.tyust-sso").put("name", "Synthetic school adapter")
            .put("version", "1.0.0").put("apiVersion", 3).put("kind", "configuration")
            .put("extends", "builtin.zf").put("school", JSONObject()
                .put("id", school.id).put("name", school.name).put("domain", school.domain)
                .put("protocol", school.protocol).put("basePath", school.basePath).put("academicSystem", "zf"))
            .put("capabilities", JSONArray()).put("network", JSONArray()).put("files", JSONObject())
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val pkg = AcademicProviderRegistry.packages().install(bytes, allowDevelopment = true)
        try {
            AcademicProviderRegistry.reload()
            AcademicProviderRegistry.choose(school, pkg.manifest.id)
            assertTrue(PasswordLoginGatewayFactory.create(school) is AcademicPasswordLoginGateway)
            AcademicProviderRegistry.setSchoolEnabled(pkg.manifest.id, school, false)
            assertTrue(PasswordLoginGatewayFactory.create(school) is TyustSsoLoginManager)
        } finally {
            AcademicProviderRegistry.choose(school, null)
            AcademicProviderRegistry.packages().deactivate(pkg.manifest.id)
            AcademicProviderRegistry.reload()
        }
    }

    @Test fun nativeSsoCookieRefreshReplacesTheOldTeachingSession() {
        val school = SchoolConfig("tyust", "TYUST", "newjwc.tyust.edu.cn", "https")
        val key = AcademicGatewayFactory.accountKey(school, "synthetic-student")
        try {
            AcademicGatewayFactory.importCookie(school, key, "JSESSIONID=old; obsolete=old")
            val previous = AcademicGatewayFactory.sharedSession(school, key)!!
            AcademicGatewayFactory.importCookie(school, key, "JSESSIONID=new", username = "synthetic-student")
            val replacement = AcademicGatewayFactory.sharedSession(school, key)!!
            assertNotSame(previous, replacement)
            assertTrue(previous.retired)
            assertEquals("JSESSIONID=new", replacement.cookieHeader())
        } finally { AcademicGatewayFactory.invalidate(school, key) }
    }
}
