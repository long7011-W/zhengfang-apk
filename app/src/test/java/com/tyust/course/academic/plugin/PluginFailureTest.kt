package com.tyust.course.academic.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the interface text produced for host-side failures. Manifest validation reports JSON
 * Schema paths such as "$.category: 不允许额外字段", which users cannot act on; these cases pin
 * the readable replacement and the release/debug split. No Android, Robolectric or crypto is
 * needed, so the suite runs on a plain JVM.
 */
class PluginFailureTest {
    private fun extra(field: String) = PluginException(PluginErrorCode.VALIDATION_FAILED, "\$.$field: 不允许额外字段")

    @Test fun unsupportedManifestFieldNamesTheFieldAndSuggestsAnAppUpdate() {
        val text = PluginFailure.userMessage(extra("category"), debug = false)
        assertTrue(text, text.startsWith("插件包与当前 App 版本不兼容"))
        assertTrue(text, text.contains("「category」"))
        assertFalse("不应把 JSON Schema 路径原样展示", text.contains("不允许额外字段"))
        assertFalse("不应暴露 $. 路径", text.contains("\$."))
    }

    @Test fun nestedMissingFieldIsReportedByItsPath() {
        val text = PluginFailure.userMessage(PluginException(PluginErrorCode.VALIDATION_FAILED, "\$.contributes.pages[0].id: 缺少字段"), debug = false)
        assertEquals("插件包缺少必需字段「contributes.pages[0].id」，请重新下载或联系插件作者", text)
    }

    @Test fun otherValidationMessagesFallBackToAGenericSentence() {
        val text = PluginFailure.userMessage(PluginException(PluginErrorCode.VALIDATION_FAILED, "包内容与文件摘要不一致"), debug = false)
        assertEquals("插件包内容校验未通过，请重新下载；若持续失败请联系插件作者", text)
    }

    @Test fun knownErrorCodesMapToTheirOwnSentence() {
        val expected = mapOf(
            PluginErrorCode.BAD_SIGNATURE to "插件包签名无效或来源不可信，已拒绝安装",
            PluginErrorCode.UNSUPPORTED to "当前 App 版本不支持该插件需要的能力，请升级 App",
            PluginErrorCode.UNTRUSTED_URL to "插件请求超出了它声明的网络范围，已拦截",
            PluginErrorCode.CONFLICT to "插件版本已变化，请重新选择后重试",
            PluginErrorCode.NETWORK_RETRYABLE to "网络暂时不可用，请稍后重试",
            PluginErrorCode.TIMEOUT to "网络暂时不可用，请稍后重试",
        )
        for ((code, sentence) in expected) {
            assertEquals(code.name, sentence, PluginFailure.userMessage(PluginException(code, "raw detail"), debug = false))
        }
    }

    @Test fun unknownCodeAndPlainExceptionNeverLeakTheRawMessage() {
        val coded = PluginFailure.userMessage(PluginException(PluginErrorCode.NOT_OPEN, "raw detail 12345"), debug = false)
        assertTrue(coded, coded.contains("NOT_OPEN"))
        assertFalse(coded, coded.contains("raw detail"))
        assertEquals("操作未完成，请重试", PluginFailure.userMessage(IllegalStateException("boom"), debug = false))
    }

    @Test fun debugBuildsKeepTheRawDetailForDiagnosis() {
        val text = PluginFailure.userMessage(extra("category"), debug = true)
        assertTrue(text, text.contains("插件包与当前 App 版本不兼容"))
        assertTrue("调试构建应保留原始信息", text.contains("不允许额外字段"))
    }

    @Test fun fieldNameExtractionRejectsForeignMessages() {
        // 不是本校验器产出的消息（缺少 "$." 前缀，或字段名含非法字符）一律走通用文案，不猜测字段。
        val generic = "插件包内容校验未通过，请重新下载；若持续失败请联系插件作者"
        assertEquals(generic, PluginFailure.userMessage(PluginException(PluginErrorCode.VALIDATION_FAILED, "category: 不允许额外字段"), debug = false))
        assertEquals(generic, PluginFailure.userMessage(PluginException(PluginErrorCode.VALIDATION_FAILED, "\$.ca tegory: 不允许额外字段"), debug = false))
        assertEquals(generic, PluginFailure.userMessage(PluginException(PluginErrorCode.VALIDATION_FAILED, "\$.category: <script>不允许额外字段"), debug = false))
    }
}
