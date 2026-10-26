package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import org.junit.Assert.*
import org.junit.Test

class AcademicWebLoginCompletionTest {
    private val completion = "https://webvpn.lntu.edu.cn${AcademicSchoolProfiles.LNTU_BASE_PATH}/homeExt.action"

    @Test fun eamsAcceptsTheSameCompletionPageWithAServletSessionSuffix() {
        assertTrue(AcademicPasswordLoginGateway.matchesCompletion(completion, completion, true))
        assertTrue(AcademicPasswordLoginGateway.matchesCompletion(completion, "$completion;jsessionid=SYNTHETIC123.node1#", true))
        assertFalse(AcademicPasswordLoginGateway.matchesCompletion(completion, "$completion;jsessionid=SYNTHETIC123"))
    }

    @Test fun sessionSuffixDoesNotRelaxOriginPortOrExactPath() {
        for (page in listOf(completion.replace("https:", "http:"), completion.replace(".edu.cn/", ".edu.cn:8443/"),
            completion.replace("webvpn.lntu.edu.cn", "webvpn.lntu.edu.cn.evil.test"),
            completion.replace("https://", "https://user@"), "$completion/extra;jsessionid=SYNTHETIC123",
            "$completion;other=1;jsessionid=SYNTHETIC123", "$completion;jsessionid=SYNTHETIC123/extra",
            "$completion%3Bjsessionid=SYNTHETIC123")) {
            assertFalse(page, AcademicPasswordLoginGateway.matchesCompletion(completion, page, true))
        }
    }

    @Test fun browserCookiesReplaceStaleValuesWithoutReplacingThePendingSession() {
        val school = SchoolConfig("test", "合成学校", "webvpn.lntu.edu.cn", "https").apply {
            academicSystem = "eams"; basePath = AcademicSchoolProfiles.LNTU_BASE_PATH
        }
        val session = AcademicSessionStore().session(school.id, "synthetic", school.fullBasePath)
        AcademicGatewayFactory.importBrowserCookie(school, session, "JSESSIONID=before-login; retained=synthetic")
        val epoch = session.epoch
        AcademicGatewayFactory.importBrowserCookie(school, session, "JSESSIONID=after-login; vpn_ticket=synthetic")
        assertEquals(epoch, session.epoch)
        assertTrue(session.cookieHeader().contains("JSESSIONID=after-login"))
        assertFalse(session.cookieHeader().contains("before-login"))
        assertTrue(session.cookieHeader().contains("retained=synthetic"))
        assertTrue(session.cookieHeader().contains("vpn_ticket=synthetic"))
    }
}
