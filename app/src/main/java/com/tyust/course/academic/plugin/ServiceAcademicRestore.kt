package com.tyust.course.academic.plugin

import com.tyust.course.manager.SessionToken
import com.tyust.course.manager.UserManager
import com.tyust.course.utils.SessionRecoveryResult
import com.tyust.course.utils.SessionRenewer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal sealed interface ServiceAcademicRestore {
    data object Restoring : ServiceAcademicRestore
    data object Ready : ServiceAcademicRestore
    data object NeedsConsent : ServiceAcademicRestore
    data class NeedsLogin(val message: String) : ServiceAcademicRestore
}

/** Restore consent and credentials before loading a page. Never replay a page/action after delivery. */
internal suspend fun restoreServiceAcademicLogin(
    runtime: ServicePluginSession,
    renew: suspend (SessionToken) -> SessionRecoveryResult = { expected ->
        suspendCancellableCoroutine { continuation ->
            SessionRenewer.request(expected) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }
): ServiceAcademicRestore {
    if (!runtime.hasAcademicConsent()) return ServiceAcademicRestore.NeedsConsent
    fun restore(): Boolean = try {
        runtime.restoreAcademicAuthorization()
    } catch (error: PluginException) {
        // Ownership/revocation is checked separately and must never be mistaken for expired login.
        if (error.code !in setOf(PluginErrorCode.SESSION_EXPIRED, PluginErrorCode.STALE_CONTEXT)) throw error
        runtime.hasAcademicConsent()
        false
    }
    if (restore()) return ServiceAcademicRestore.Ready
    if (!runtime.hasAcademicConsent()) return ServiceAcademicRestore.NeedsConsent
    val result = renew(UserManager.getInstance().sessionState.token)
    // A concurrent logout, account switch, revocation or package replacement must win after waiting.
    if (!runtime.hasAcademicConsent()) return ServiceAcademicRestore.NeedsConsent
    if (restore()) return ServiceAcademicRestore.Ready
    val message = (result as? SessionRecoveryResult.NeedsLogin)?.message.orEmpty()
    return ServiceAcademicRestore.NeedsLogin(message.ifBlank { "已记住此插件的授权，但教务登录已失效，请重新登录学校账号。" })
}
