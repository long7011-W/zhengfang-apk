package com.tyust.course.academic

import com.tyust.course.academic.plugin.GenericAcademicProtocols
import com.tyust.course.academic.plugin.PluginNetworkPolicy
import com.tyust.course.academic.plugin.PluginException
import com.tyust.course.academic.plugin.PluginJson
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class AcademicSchoolProfilesTest {
    @Test fun hnaeKeepsCasPortAndNormalizesOldServiceSessions() {
        val academic = AcademicSchoolProfiles.detect("https://jw.hnae.edu.cn/frame/homes.action;jsessionid=OLD123")!!
        val cas = AcademicSchoolProfiles.detect("https://cas.hnae.edu.cn:8003/cas/login?service=https%3A%2F%2Fportal.hnae.edu.cn%2Flogin%3Bjsessionid%3DOLD123")!!
        assertEquals(academic, cas)
        assertEquals(AcademicSystem.KINGO, cas.system)
        assertTrue(cas.school().allowedAcademicHosts.contains("cas.hnae.edu.cn:8003"))
        assertFalse(cas.loginUrl.contains("jsessionid"))
        val policy = PluginNetworkPolicy(PluginJson.objects(GenericAcademicProtocols.network(cas.school())))
        policy.requireAllowed(cas.loginUrl.toHttpUrl(), "POST", "auth", null)
        assertThrows(PluginException::class.java) { policy.requireAllowed("https://cas.hnae.edu.cn:8003/cas/login".toHttpUrl(), "POST", "mutation", null) }
        assertNull(AcademicSchoolProfiles.detect(cas.loginUrl.replace(":8003", ":8004")))
        assertNull(AcademicSchoolProfiles.detect(cas.loginUrl.replace("https%3A%2F%2Fjw.hnae.edu.cn%2Fcaslogin", "https%3A%2F%2Fother.example.test%2Fcaslogin")))
    }
    @Test fun lntuCampusAndVpnAliasesResolveToCanonicalHttpsContextOnly() {
        val campus = AcademicSchoolProfiles.detect("http://jwxt.lntu.edu.cn:8080/eams/homeExt.action;jsessionid=OLD123")!!
        val vpn = AcademicSchoolProfiles.detect("https://webvpn.lntu.edu.cn${AcademicSchoolProfiles.LNTU_BASE_PATH}/homeExt.action;jsessionid=OLD123")!!
        assertEquals(campus, vpn)
        assertEquals("https", vpn.address.protocol)
        assertEquals(AcademicSystem.EAMS, vpn.system)
        assertNull(AcademicSchoolProfiles.detect("http://jwxt.lntu.edu.cn:8081/eams"))
        assertNull(AcademicSchoolProfiles.detect("https://webvpn.lntu.edu.cn:8443${AcademicSchoolProfiles.LNTU_BASE_PATH}"))
        assertNull(AcademicSchoolProfiles.detect("https://webvpn.lntu.edu.cn/other/eams"))
        assertNull(AcademicSchoolProfiles.detect("https://user@webvpn.lntu.edu.cn${AcademicSchoolProfiles.LNTU_BASE_PATH}"))
        assertNull(AcademicSchoolProfiles.detect("https://webvpn.lntu.edu.cn.evil.test${AcademicSchoolProfiles.LNTU_BASE_PATH}"))
        val config = GenericAcademicProtocols.configuration(vpn.school()).getJSONObject("protocolOptions")
        assertEquals("jwxt.lntu.edu.cn:8080", config.getString("webVpnTarget"))
        val rules = GenericAcademicProtocols.network(vpn.school())
        for (n in 0 until rules.length()) assertEquals("https://webvpn.lntu.edu.cn", rules.getJSONObject(n).getString("origin"))
    }
    @Test fun kingoDetectionNeedsProtocolEvidenceRatherThanAStudentPageWord() {
        assertEquals(AcademicSystem.KINGO, SystemDetector.classify("KINGOSOFT <script src='/custom/js/SetKingoEncypt.jsp'></script>"))
        assertNull(SystemDetector.classify("学校介绍：青果"))
    }
}
