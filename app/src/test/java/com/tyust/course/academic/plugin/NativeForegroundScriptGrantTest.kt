package com.tyust.course.academic.plugin

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class NativeForegroundScriptGrantTest {
    private val start = "https://course.test/work/answer?courseId=11&classId=22&workId=33&enc=synthetic"
    private fun plan() = JSONObject("""{"id":"original","starts":[{"host":"course.test","pathPrefix":"/work/answer","scope":{"inputList":"works","parameters":{"courseId":"courseId","classId":"classId","workId":"workId"}}}],
        "settings":{"directToWork":false},"actions":[{"id":"save"},{"id":"submit","when":"autoSubmit"}],
        "prompts":[{"id":"start","ruleId":"native","actionId":"confirm","messageIncludes":"Start selected work"},{"id":"saved","ruleId":"saved","actionId":"confirm","messageIncludes":"Saved"},{"id":"submit","ruleId":"submit","actionId":"confirm","when":"autoSubmit"}]}""")
    private fun input(submit: Boolean = false) = JSONObject("""{"works":[{"courseId":"11","classId":"22","workId":"33","name":"Synthetic one"},{"courseId":"11","classId":"22","workId":"44","name":"Synthetic two"}]}""").put("autoSubmit", submit)
    private fun status(url: String = start) = JSONObject().put("handle", "gowned").put("status", "running").put("lastUrl", url)
        .put("actions", JSONArray().put(JSONObject().put("id", "save")).put(JSONObject().put("id", "submit")))
    private fun ready(submit: Boolean = false): NativeForegroundScriptGrant = NativeForegroundScriptGrant(plan(), input(submit)) { true }.also {
        it.requireStart("original", start, null); it.remember("gowned", start)
    }
    private fun action(id: String) = JSONObject().put("handle", "gowned").put("actionId", id)
    private fun prompt(rule: String, message: String, id: String = "p1") = JSONObject().put("id", id).put("ruleId", rule).put("kind", "notice")
        .put("message", message).put("actions", JSONArray().put(JSONObject().put("id", "confirm")))

    @Test fun scopeBindsEveryFieldAndRejectsCaseDuplicateParameters() {
        for (url in listOf(start.replace("workId=33", "workId=55"), start.replace("classId=22", "classId=99"),
            start + "&workId=44", start + "&workid=44", start.replace("https:", "http:"), start.replace("course.test", "course.test:8443"),
            start.replace("/work/answer", "/work/answering"), start.replace("course.test", "other.test"))) {
            assertThrows(url, PluginException::class.java) { NativeForegroundScriptGrant(plan(), input()) { true }.requireStart("original", url, null) }
        }
        NativeForegroundScriptGrant(plan(), input()) { true }.requireStart("original", start, null)
    }
    @Test fun inputIsImmutableAndUnselectedSubmissionsStayDenied() {
        val selected = input(false)
        val grant = NativeForegroundScriptGrant(plan(), selected) { true }
        grant.requireStart("original", start, null); grant.remember("gowned", start)
        selected.put("autoSubmit", true)
        grant.requireInteraction(action("save"), status(), true)
        assertThrows(PluginException::class.java) { grant.requireInteraction(action("submit"), status(), true) }
        val dishonest = input().put("autoSubmit", "true")
        val stringFlag = NativeForegroundScriptGrant(plan(), dishonest) { true }
        stringFlag.requireStart("original", start, null); stringFlag.remember("gowned", start)
        assertThrows(PluginException::class.java) { stringFlag.requireInteraction(action("submit"), status(), true) }
    }
    @Test fun commandsCannotBeReplayedAndEachStartIsConsumedOnce() {
        val grant = ready(true)
        grant.requireInteraction(action("submit"), status(), true)
        assertThrows(PluginException::class.java) { grant.requireInteraction(action("submit"), status(), true) }
        assertThrows(PluginException::class.java) { grant.requireStart("original", start, null) }
        grant.requireStart("original", start.replace("workId=33", "workId=44"), null)
    }
    @Test fun sameQueueCannotUseAnotherItemsPageThroughTheOldHandle() {
        val grant = ready(true)
        assertThrows(PluginException::class.java) { grant.requireInteraction(action("submit"), status(start.replace("workId=33", "workId=44")), true) }
        assertThrows(PluginException::class.java) { grant.requireInteraction(action("save").put("handle", "gforeign"), status(), true) }
        assertThrows(PluginException::class.java) { grant.requireStart("original", start.replace("workId=33", "workId=44"), status()) }
    }
    @Test fun onlyDeclaredNoticeMessagesCanBeConfirmed() {
        val grant = ready()
        val click = action("confirm").put("interactionId", "native:fixture")
        val starting = status().put("interaction", prompt("", "Start selected work", "native:fixture"))
        grant.requireInteraction(click, starting, false)
        assertThrows(PluginException::class.java) { grant.requireInteraction(click, starting, false) }
        for (p in listOf(prompt("", "Submit final results", "native:new"), prompt("submit", "Submit?"), prompt("saved", "Saved").put("kind", "input"))) {
            assertThrows(PluginException::class.java) { grant.requireInteraction(action("confirm").put("interactionId", p.getString("id")), status().put("interaction", p), false) }
        }
    }
    @Test fun stopsCanOnlyTargetOwnedHandlesAndNoPageCanBeOpened() {
        val grant = ready()
        grant.requireControl(JSONObject().put("handle", "gowned").put("action", "stop"), status("https://course.test/work/list"))
        for (command in listOf("open", "settings", "start")) assertThrows(PluginException::class.java) {
            grant.requireControl(JSONObject().put("handle", "gowned").put("action", command), status())
        }
        assertThrows(PluginException::class.java) { grant.requireControl(JSONObject().put("handle", "gforeign").put("action", "stop"), status()) }
    }
    @Test fun configurationIsFixedAndCredentialsAreNeverAccepted() {
        val grant = ready()
        grant.requireConfiguration(JSONObject().put("scriptId", "original").put("values", JSONObject().put("directToWork", false)))
        for (config in listOf(JSONObject().put("directToWork", true), JSONObject().put("directToWork", false).put("autoSubmit", true))) {
            assertThrows(PluginException::class.java) { grant.requireConfiguration(JSONObject().put("scriptId", "original").put("values", config)) }
        }
        assertThrows(PluginException::class.java) { grant.requireConfiguration(JSONObject().put("scriptId", "original").put("values", JSONObject().put("directToWork", false)).put("credential", JSONObject())) }
    }
    @Test fun revokedAccountOrStoppedTaskCannotIssueAnotherAction() {
        var active = true
        val grant = NativeForegroundScriptGrant(plan(), input(true)) { active }
        grant.requireStart("original", start, null); grant.remember("gowned", start); active = false
        assertThrows(PluginException::class.java) { grant.requireInteraction(action("submit"), status(), true) }
        assertThrows(PluginException::class.java) { grant.requireConfiguration(JSONObject()) }
    }
    @Test fun invalidOrDuplicateQueueItemsAreRejectedBeforeStarting() {
        for (works in listOf(JSONArray(), JSONArray().put(JSONObject().put("workId", "33")), JSONArray().put(input().getJSONArray("works").get(0)).put(input().getJSONArray("works").get(0))))
            assertThrows(PluginException::class.java) { NativeForegroundScriptGrant(plan(), JSONObject().put("works", works)) { true } }
    }
    @Test fun configurationCannotChangeAnUnrelatedLiveScript() {
        val config = JSONObject().put("scriptId", "original").put("values", JSONObject().put("directToWork", false))
        val grant = ready()
        grant.requireConfiguration(config, status())
        grant.requireConfiguration(config, status().put("status", "stopped"))
        assertThrows(PluginException::class.java) { grant.requireConfiguration(config, status().put("handle", "gmanual")) }
    }
    @Test fun actualDocumentMustMatchTheWorkBoundToItsHandle() {
        val grant = ready(true)
        assertTrue(grant.allowsDocument("gowned", start))
        assertFalse(grant.allowsDocument("gowned", start.replace("workId=33", "workId=44")))
        assertFalse(grant.allowsDocument("gforeign", start))
        assertFalse(grant.allowsDocument("gowned", start + "&WORKID=33"))
        assertFalse(grant.allowsDocument("gowned", "https://course.test/work/list"))
    }
    @Test fun installValidationRejectsUnsupportedOrBroadenedQueueDeclarations() {
        val json = JSONObject("""{"kind":"native","minAppVersionCode":123,"permissions":["network","userscripts","tasks"],
          "network":[{"origin":"https://course.test","pathPrefix":"/","methods":["GET","POST"],"purposes":["query","mutation"]}],
          "servers":[{"id":"course","authentication":"web","browser":{"engine":"embedded","version":1}}],
          "userscripts":[{"id":"original","serverId":"course","sourcePrefix":"https://updates.test/scripts/1/","updateUrl":"https://updates.test/scripts/1/code.meta.js","downloadUrl":"https://updates.test/scripts/1/code.user.js",
          "matches":[{"host":"course.test","pathPrefix":"/work"}],"exclude":[{"host":"course.test","pathPrefix":"/work/exam"}],"connect":["course.test"],
          "adapter":{"browserPrompts":true,"settings":[{"key":"directToWork","type":"boolean","selector":"#direct"}],"actions":[{"id":"save"},{"id":"submit"}],
          "dialogs":[{"id":"saved","kind":"notice","actions":[{"id":"confirm"}]},{"id":"submit","kind":"notice","actions":[{"id":"confirm"}]}]}}]}""")
        json.put("requires", JSONArray(listOf("browser.session.open", "userscript.status", "userscript.start", "userscript.control", "userscript.configure", "userscript.action", "userscript.interact", "tasks.foreground.start")
            .map { JSONObject().put("name", it).put("version", if (it == "tasks.foreground.start") 2 else 1) }))
        json.put("contributes", JSONObject().put("tasks", JSONArray().put(JSONObject().put("id", "queue").put("foreground",
            JSONObject().put("serverId", "course").put("intervalSeconds", 5).put("mutations", JSONArray()).put("script", plan())))))
        PluginUserscriptPolicy.validate(PluginManifest(json))
        for (variant in 0..5) {
            val candidate = JSONObject(json.toString())
            val foreground = candidate.getJSONObject("contributes").getJSONArray("tasks").getJSONObject(0).getJSONObject("foreground")
            when (variant) {
                0 -> candidate.put("minAppVersionCode", 122)
                1 -> candidate.getJSONArray("requires").getJSONObject(7).put("version", 1)
                2 -> foreground.getJSONObject("script").getJSONObject("settings").put("directToWork", 0)
                3 -> foreground.getJSONObject("script").getJSONArray("starts").getJSONObject(0).put("pathPrefix", "/work/exam")
                4 -> foreground.put("batchInputKey", "works")
                5 -> foreground.getJSONObject("script").getJSONArray("prompts").getJSONObject(0).remove("messageIncludes")
            }
            assertThrows("variant $variant", PluginException::class.java) { PluginUserscriptPolicy.validate(PluginManifest(candidate)) }
        }
    }
}
