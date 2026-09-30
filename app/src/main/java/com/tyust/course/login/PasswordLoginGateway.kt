package com.tyust.course.login

import com.tyust.course.model.SchoolConfig
import com.tyust.course.academic.plugin.AcademicProviderRegistry
import com.tyust.course.academic.plugin.GenericAcademicProtocols

interface PasswordLoginGateway {
    fun login(
        school: SchoolConfig,
        username: String,
        password: String,
        callback: PasswordLoginCallback
    )

    fun submitCaptcha(captchaCode: String, callback: PasswordLoginCallback)

    fun refreshCaptcha(callback: (ByteArray?) -> Unit)

    fun clearSensitiveState()
}

object PasswordLoginGatewayFactory {
    /** Generic teaching protocols do not replace a school's dedicated identity-provider login. */
    fun usesPluginAuthentication(school: SchoolConfig): Boolean {
        if (school.id == TYUST_SCHOOL_ID || school.id == ZJUT_SCHOOL_ID) {
            val provider = runCatching { AcademicProviderRegistry.resolve(school) }.getOrNull()
            if (provider == null || provider.manifest.id in GenericAcademicProtocols.providers.values) return false
        }
        return AcademicProviderRegistry.overrides(school, "auth.start")
    }

    fun create(school: SchoolConfig): PasswordLoginGateway =
        if (usesPluginAuthentication(school)) com.tyust.course.academic.AcademicPasswordLoginGateway(school)
        else if (school.id == TYUST_SCHOOL_ID) TyustSsoLoginManager()
        else if (school.id == ZJUT_SCHOOL_ID) ZjutSsoLoginManager()
        else if (com.tyust.course.academic.AcademicGatewayFactory.supports(school)) com.tyust.course.academic.AcademicPasswordLoginGateway(school)
        else PasswordLoginManager()

    private const val TYUST_SCHOOL_ID = "tyust"
    private const val ZJUT_SCHOOL_ID = "zjut"
}
