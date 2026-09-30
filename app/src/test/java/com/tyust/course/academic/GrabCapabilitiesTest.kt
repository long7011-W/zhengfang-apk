package com.tyust.course.academic

import org.junit.Assert.*
import org.junit.Test

class GrabCapabilitiesTest {
    @Test fun pluginLimitsAreSerialForAllSixFamilies() {
        for (system in listOf(AcademicSystem.ZF, AcademicSystem.ZF_OLD, AcademicSystem.QZ,
            AcademicSystem.QZ_OLD, AcademicSystem.JINZHI, AcademicSystem.CHENGFANG)) {
            val policy = GrabCapabilities.evaluate(system, true, GrabCapabilities.selectionOperations)
            assertTrue(policy.available); assertEquals(1, policy.workers(true))
        }
        assertEquals(2, GrabCapabilities.evaluate(AcademicSystem.LEGACY_ZF, false, emptySet()).workers(true))
    }
    @Test fun knownUnsupportedPackageIsBlockedWithoutBlockingFutureImplementations() {
        assertFalse(GrabCapabilities.evaluate(AcademicSystem.CHENGFANG, true, GrabCapabilities.selectionOperations,
            GrabCapabilities.UNVERIFIED_CHENGFANG).available)
        assertTrue(GrabCapabilities.evaluate(AcademicSystem.CHENGFANG, true, GrabCapabilities.selectionOperations,
            "replacement-with-verified-submission").available)
        assertFalse(GrabCapabilities.evaluate(AcademicSystem.ZF, true, setOf("study.grades")).available)
        assertFalse(GrabCapabilities.evaluate(AcademicSystem.ZF, true, GrabCapabilities.selectionOperations,
            unavailableReason = "本校尚未支持选课").scheduling)
    }
    @Test fun manualCoursesCannotSilentlyBecomeExact() {
        val policy = GrabCapabilities.evaluate(AcademicSystem.ZF, true, GrabCapabilities.selectionOperations)
        val manual = AcademicGrabItem("account", "school", "课程", teacher = "王老师", time = "周一 1-2节", useExactMatch = false)
        assertNull(policy.validate(listOf(manual)))
        assertNotNull(policy.validate(listOf(manual.copy(useExactMatch = true))))
        assertNull(policy.validate(listOf(manual.copy(useExactMatch = true, stableSectionId = "class"))))
    }
    @Test fun runtimeRestrictionsAreScopedToAccountAndProviderVersion() {
        GrabCapabilityFailures.record("one", "digest1", "不支持")
        assertEquals("不支持", GrabCapabilityFailures.reason("one", "digest1"))
        assertEquals("", GrabCapabilityFailures.reason("two", "digest1"))
        assertEquals("", GrabCapabilityFailures.reason("one", "digest2"))
    }
}
