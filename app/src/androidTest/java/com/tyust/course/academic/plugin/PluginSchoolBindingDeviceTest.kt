package com.tyust.course.academic.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.BuildConfig
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PluginSchoolBindingDeviceTest : PluginSchoolBindingChecks() {
    override suspend fun verifyExtensionLogin(school: com.tyust.course.model.SchoolConfig) {
        val account = "dev:binding-${java.util.UUID.randomUUID()}"
        val adapter = com.tyust.course.academic.AcademicGatewayFactory.create(school, account) as PluginAcademicAdapter
        try {
            val login = adapter.login(com.tyust.course.academic.Credentials("synthetic", "synthetic"))
            org.junit.Assert.assertEquals(com.tyust.course.academic.AcademicStatus.SUCCESS, login.status)
            org.junit.Assert.assertEquals("synthetic", login.studentId)
            org.junit.Assert.assertEquals("1.2.1", adapter.version)
        } finally {
            adapter.clearLoginState()
            com.tyust.course.academic.AcademicGatewayFactory.invalidate(school, account)
        }
    }
    override fun testContext(): Context {
        assumeTrue(BuildConfig.UI_PREVIEW)
        return ApplicationProvider.getApplicationContext()
    }
}
