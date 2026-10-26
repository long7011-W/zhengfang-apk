package com.tyust.course.academic

import org.junit.Assert.*
import org.junit.Test

class LntuWebLoginAssistTest {
    private val login = AcademicSchoolProfiles.LNTU_LOGIN_URL
    private val cookie = LntuWebLoginAssist.ORIGIN + AcademicSchoolProfiles.LNTU_BASE_PATH + "/"
    private val completion = LntuWebLoginAssist.COMPLETION_URL

    @Test fun pendingLoginMustMatchTheEntireVerifiedSchoolDeployment() {
        assertTrue(LntuWebLoginAssist.supports(LntuWebLoginAssist.ORIGIN + "/", cookie, completion))
        assertTrue(LntuWebLoginAssist.supports(login, cookie, completion))
        for (url in listOf("https://other.example.test/", LntuWebLoginAssist.ORIGIN + "/other", login + "&type=dynamicLogin"))
            assertFalse(url, LntuWebLoginAssist.supports(url, cookie, completion))
        assertFalse(LntuWebLoginAssist.supports(login, cookie.replace("/eams/", "/other/"), completion))
        assertFalse(LntuWebLoginAssist.supports(login, cookie, completion + "/extra"))
        assertFalse(LntuWebLoginAssist.supports(login, cookie, ""))
    }

    @Test fun passwordsAreNeverOfferedToAnotherOriginPathOrCasService() {
        assertTrue(LntuWebLoginAssist.isLoginPage(login))
        for (url in listOf(
            login.replace("https://", "http://"), login.replace(".edu.cn/", ".edu.cn:8443/"),
            login.replace("https://", "https://user@"), login.replace(".edu.cn/", ".edu.cn.evil.test/"),
            login.replace("/authserver/login?", "/authserver/login/extra?"),
            login.substringBefore('?'), login + "&service=https%3A%2F%2Fother.example.test%2F",
            login.substringBefore('?') + "?service=https%3A%2F%2Fother.example.test%2F", login + "#extra",
            login.replace("/authserver/", "/authserver%2F"), login + "&next=untrusted"
        )) assertFalse(url, LntuWebLoginAssist.isLoginPage(url))
    }

    @Test fun onlyTheVpnPortalCanAdvanceToTheAcademicHomepage() {
        assertTrue(LntuWebLoginAssist.isPortalPage(LntuWebLoginAssist.ORIGIN + "/"))
        assertTrue(LntuWebLoginAssist.isPortalPage(LntuWebLoginAssist.ORIGIN + "/#/"))
        for (url in listOf(login, "https://other.example.test/", LntuWebLoginAssist.ORIGIN + "/login", LntuWebLoginAssist.ORIGIN + "/?next=login"))
            assertFalse(url, LntuWebLoginAssist.isPortalPage(url))
    }

    @Test fun completionAllowsOnlyTheAcademicHomeAndItsServletSessionSuffix() {
        assertTrue(LntuWebLoginAssist.isCompletionPage(completion))
        assertTrue(LntuWebLoginAssist.isCompletionPage("$completion;jsessionid=FIXTURE.node1#"))
        for (url in listOf(login, cookie, completion + "/extra", completion.replace("/homeExt.action", "/loginExt.action"),
            completion.replace(".edu.cn/", ".edu.cn.evil.test/"), completion.replace("https://", "http://")))
            assertFalse(url, LntuWebLoginAssist.isCompletionPage(url))
    }
}
