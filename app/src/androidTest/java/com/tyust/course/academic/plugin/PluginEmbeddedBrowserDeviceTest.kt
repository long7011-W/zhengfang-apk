package com.tyust.course.academic.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import com.tyust.course.academic.AcademicSessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

/** Uses isolated profiles and the real Gecko service. Live diagnostics require explicit private fixtures and opt-in. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class PluginEmbeddedBrowserDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun profile() = "plugin_" + PluginJson.sha256(UUID.randomUUID().toString().toByteArray())

    /** Opt-in: original script, GET-only school resources, at most three authorized real bank queries. */
    @Test fun authorizedOriginalChapterScriptReadOnlyDiagnostic() {
        assumeTrue("Opt in with -e allowChapterScriptRead true",
            InstrumentationRegistry.getArguments().getString("allowChapterScriptRead") == "true")
        val input = File(context.filesDir, "chapter-script-live.json")
        assumeTrue("No authorized diagnostic supplied", input.isFile)
        val config = JSONObject(input.readText())
        val original = File(context.filesDir, "chapter-script-original.js").readText()
        val pkg = AcademicProviderRegistry.packages().list().first { it.manifest.id == "community.chaoxing" }
        val declaration = pkg.manifest.json.getJSONArray("userscripts").getJSONObject(0)
        val actualBank = InstrumentationRegistry.getArguments().getString("useActualBank") == "true"
        val bankToken = if (actualBank) {
            val owner = PluginServiceAccounts(context).session(pkg, "chaoxing")
            PluginUserscripts.store(context, pkg, owner, declaration.getString("id")).settings().getString("shenchanranToken")
                .also { require(it.isNotBlank()) }
        } else "0123456789abcdef0123456789abcdef"
        val actualQueries = AtomicInteger()
        val queryLimit = InstrumentationRegistry.getArguments().getString("maxActualQueries")?.toInt()?.coerceIn(1,3) ?: 1
        val session = AcademicSessionStore().session("synthetic-script-host", "dev:script-${UUID.randomUUID()}", "https://mooc1.chaoxing.com")
        val profile = profile()
        val trace = ConcurrentLinkedQueue<JSONObject>()
        val stage = AtomicReference("")
        val prompt = AtomicReference<JSONObject>()
        val questionLookup = CountDownLatch(1)
        val questionDocument = CountDownLatch(1)
        val handle = "chapter-original-diagnostic"
        val allowedReads = setOf("/ananas/ueditor/ueditor.parse.js", "/mycourse/studentstudycourselist",
            "/mooc-ans/mycourse/studentstudycourselist", "/knowledge/cards", "/mooc-ans/knowledge/cards",
            "/work/phone/work", "/mooc-ans/work/phone/work", "/stat2/chapter-exam/s/tests/data")
        try {
            for (item in PluginJson.objects(config.getJSONArray("cookies"))) {
                val domain = item.getString("domain").trimStart('.')
                require(domain == "chaoxing.com" || domain.endsWith(".chaoxing.com"))
                val cookie = Cookie.Builder().name(item.getString("name")).value(item.getString("value"))
                    .domain(domain).path(item.getString("path")).apply { if(item.optBoolean("secure")) secure() }.build()
                session.cookies.saveFromResponse("https://$domain/".toHttpUrl(), listOf(cookie))
            }
            Client(profile) { event ->
                when (event.optString("kind")) {
                    "status" -> { stage.set(event.optString("stage")); prompt.set(event.optJSONObject("interaction")); trace.add(JSONObject().put("stage",stage.get())) }
                    "storage" -> {
                        trace.add(JSONObject().put("write",event.optString("key")))
                        if (event.optString("key") == "diagnostic-frame") {
                            trace.add(event.getJSONObject("value"))
                            if(event.getJSONObject("value").optInt("questions")>0 && event.getJSONObject("value").optBoolean("questionId")) questionDocument.countDown()
                            File(context.getExternalFilesDir(null),"chapter-script-diagnostic.json").writeText(JSONArray(trace.toList()).toString(2))
                        }
                        JSONObject().put("ok",true)
                    }
                    "request" -> {
                        val request = event.getJSONObject("request")
                        val url = request.getString("url").toHttpUrl()
                        val method = request.optString("method", "GET")
                        if(url.encodedPath == "/__host_diagnostic__/state") {
                            val sample = JSONObject(url.queryParameter("sample")!!)
                            trace.add(sample)
                            if(sample.optInt("questions")>0 && sample.optBoolean("questionId")) questionDocument.countDown()
                            File(context.getExternalFilesDir(null),"chapter-script-diagnostic.json").writeText(JSONArray(trace.toList()).toString(2))
                            return@Client JSONObject().put("ok",true).put("response",JSONObject().put("status",200).put("url",url.toString()).put("headers",JSONObject()).put("body","{}"))
                        }
                        val record = JSONObject().put("host",url.host).put("path",url.encodedPath).put("method",method)
                        trace.add(record)
                        if (actualBank && url.host.endsWith("wanjuantiku.com") &&
                            url.encodedPath in setOf("/api/status", "/api/checkLeft", "/api/query") &&
                            (url.encodedPath != "/api/query" || actualQueries.incrementAndGet() <= queryLimit)) {
                            val operation = PluginOperation(session,pkg.manifest,"host.effect")
                            try {
                                val args = JSONObject().put("url",url.toString()).put("method",method)
                                    .put("purpose","query").put("headers",request.optJSONObject("headers") ?: JSONObject())
                                if(method == "POST" && !request.isNull("body")) args.put("body",request.getString("body"))
                                val response = runBlocking { PluginHost(operation,context.cacheDir,userscriptHeaders=true).call("http",args) }.getJSONObject("data")
                                val result = runCatching { JSONObject(response.optString("body")) }.getOrNull()
                                record.put("status",response.getInt("status")).put("code",result?.optInt("code"))
                                if(url.encodedPath == "/api/checkLeft") record.put("balance",result?.opt("data"))
                                if(url.encodedPath == "/api/query") {
                                    record.put("answerPresent",!result?.optString("data").isNullOrBlank()).put("balance",result?.opt("left"))
                                    record.put("answerKind",result?.opt("data")?.javaClass?.simpleName)
                                    questionLookup.countDown()
                                }
                                JSONObject().put("ok",true).put("response",response)
                            } catch(error:Throwable) {
                                record.put("error",(error as? PluginException)?.code?.name ?: error.javaClass.simpleName)
                                JSONObject().put("ok",false).put("error","NETWORK_RETRYABLE")
                            } finally { operation.close() }
                        } else if (url.host.endsWith("wanjuantiku.com")) {
                            val body = when(url.encodedPath) {
                                "/api/status" -> """{"status":"t"}"""
                                "/api/checkLeft" -> """{"data":999,"msg":""}"""
                                else -> {
                                    if(url.encodedPath=="/api/query") questionLookup.countDown()
                                    """{"code":0,"data":[],"msg":"Read-only diagnostic"}"""
                                }
                            }
                            JSONObject().put("ok",true).put("response",JSONObject().put("status",200).put("url",url.toString()).put("headers",JSONObject()).put("body",body))
                        } else if (method == "GET" && url.host.endsWith(".chaoxing.com") && url.encodedPath in allowedReads) {
                            val operation = PluginOperation(session,pkg.manifest,"host.effect")
                            try {
                                val response = runBlocking { PluginHost(operation,context.cacheDir,userscriptHeaders=true).call("http",
                                    JSONObject().put("url",url.toString()).put("purpose","query").put("headers",request.optJSONObject("headers") ?: JSONObject())) }.getJSONObject("data")
                                record.put("status",response.getInt("status")).put("quiz",response.optString("body").contains("submitData()"))
                                JSONObject().put("ok",true).put("response",response)
                            } catch(error:Throwable) {
                                record.put("error",(error as? PluginException)?.code?.name ?: error.javaClass.simpleName)
                                JSONObject().put("ok",false).put("error","NETWORK_RETRYABLE")
                            } finally { operation.close() }
                        } else {
                            record.put("blocked",true)
                            JSONObject().put("ok",false).put("error","PERMISSION_DENIED")
                        }
                    }
                    else -> { trace.add(JSONObject().put("event",event.optString("kind")).put("reason",event.optString("reason"))); JSONObject().put("ok",true) }
                }.let { if(it is JSONObject) it else JSONObject().put("ok",true) }
            }.use { client ->
                val network = JSONArray()
                for (host in listOf("mooc1.chaoxing.com","mooc-res2.chaoxing.com")) {
                    for(path in listOf("/mooc-ans/course", "/js", "/mooc-ans/js", "/mooc-ans/work/js", "/mooc2/js", "/mooc2-ans/js"))
                        network.put(JSONObject().put("origin","https://$host").put("pathPrefix",path).put("methods",JSONArray().put("GET")).put("purposes",JSONArray().put("query")))
                }
                if (InstrumentationRegistry.getArguments().getString("fullReadResources") != "false") {
                    for (rule in PluginJson.objects(pkg.manifest.json.getJSONArray("network"))) {
                        if ("GET" in PluginJson.strings(rule.getJSONArray("methods")))
                            network.put(JSONObject(rule.toString()).put("methods",JSONArray().put("GET")))
                    }
                }
                client.configure(JSONObject().put("title","Read-only original script diagnostic").put("origins",JSONArray().put("https://mooc1.chaoxing.com")).put("network",network))
                for (item in PluginJson.objects(config.getJSONArray("cookies")).filter {
                    val domain=it.getString("domain").trimStart('.')
                    domain=="mooc1.chaoxing.com" || "mooc1.chaoxing.com".endsWith(".$domain")
                }) client.call("cookies.write",JSONObject()
                    .put("url","https://mooc1.chaoxing.com/").put("cookie","${item.getString("name")}=${item.getString("value")}; Domain=${item.getString("domain")}; Path=${item.getString("path")}; Secure"))
                val monitor = """
                    const report=value=>GM_xmlhttpRequest({url:'https://mooc1.chaoxing.com/__host_diagnostic__/state?sample='+encodeURIComponent(JSON.stringify(value)),onload(){}});
                    window.addEventListener('unhandledrejection',event=>report({
                      exception:event.reason?.name||'rejection',message:String(event.reason?.message||'').replace(/https?:\S+/g,'[url]').slice(0,200),
                      stack:String(event.reason?.stack||'').split('\n').slice(0,3).map(x=>x.replace(/https?:\S+/g,'[url]')).join('\n')}));
                    setInterval(()=>{
                      try {
                        const d=unsafeWindow.document,f=d.querySelector('#iframe_content iframe');
                        report({frame:!!f,ready:f?.contentDocument?.readyState||'',
                          questions:f?.contentDocument?.querySelectorAll('.Py-mian1').length||0,
                          questionId:!!f?.contentDocument?.querySelector('#oldWorkId,#workLibraryId'),
                          saveFunction:typeof f?.contentWindow?.noSubmit,
                          waivedSaveFunction:typeof f?.contentWindow?.wrappedJSObject?.noSubmit,
                          filled:f?.contentDocument?.querySelectorAll('input:checked').length||0,
                          selected:f?.contentDocument?.querySelectorAll('li.cur').length||0,
                          answered:[...(f?.contentDocument?.querySelectorAll('input.answerInput,input[id^="answer"],textarea[id^="answer"]')||[])].filter(n=>!n.id.startsWith('answertype')&&n.value!=='').length,
                          unexpectedError:(d.querySelector('#log')?.textContent||'').includes('出现无法预料的错误'),
                          noAnswers:(d.querySelector('#log')?.textContent||'').includes('一道题都没查出来'),
                          attemptedSave:(d.querySelector('#log')?.textContent||'').includes('自动保存'),
                          waiting:!!unsafeWindow.wait,progress:d.querySelector('#leftNum')?.textContent||''});
                      }catch(error){report({error:error.name,message:String(error.message).slice(0,200)});}
                    },3000);
                """.trimIndent()
                val values = JSONObject().put("shenchanranToken",bankToken).put("doVideo",false)
                    .put("doDocument",false).put("doLive",false).put("doRenwu",false).put("doWork",true)
                    .put("doNoMission",true).put("autoSubmit",false).put("doAI",false)
                client.call("script.start",JSONObject().put("handle",handle).put("source",monitor+"\n"+original)
                    .put("declaration",declaration).put("info",JSONObject().put("version","3.0.3")).put("values",values))
                val course = config.getString("url").toHttpUrl()
                val url = "https://mooc1.chaoxing.com/mooc-ans/course/999999999303.html?ut=s&classid=" + course.queryParameter("clazzid") + "&courseid=" + course.queryParameter("courseid") + "&cpi=" + course.queryParameter("cpi") + "&coursename=ReadOnly"
                client.call("page.open",JSONObject().put("url",url).put("origins",JSONArray().put("https://mooc1.chaoxing.com")).put("scriptHandle",handle).put("active",false))
                val deadline=System.currentTimeMillis()+45_000
                val acknowledged=mutableSetOf<String>()
                while(stage.get()!="ready" && System.currentTimeMillis()<deadline) {
                    val notice=prompt.get()
                    if(notice?.optString("ruleId")=="notice" && notice.optString("message").contains("牢记脚本官网") && acknowledged.add(notice.getString("id")))
                        client.call("script.interact",JSONObject().put("handle",handle).put("interactionId",notice.getString("id")).put("actionId","confirm"))
                    Thread.sleep(500)
                }
                assertEquals("Original initialization did not finish; stages=${trace.filter { it.has("stage") }}","ready",stage.get())
                assertTrue(client.call("script.control",JSONObject().put("handle",handle).put("action","start")).optBoolean("accepted"))
                assertTrue("Question lookup not reached; see private diagnostic report",questionLookup.await(100,TimeUnit.SECONDS))
                assertTrue("Question document was not observed",questionDocument.await(5,TimeUnit.SECONDS))
                assertTrue("The iframe's page helper is hidden from the userscript",
                    trace.any { it.optString("saveFunction") == "function" })
                if(actualBank) Thread.sleep(if(queryLimit>1) 35_000 else 12_000)
                if(actualBank) {
                    assertTrue("No answer selection was observed",trace.any { it.optInt("selected")>0 || it.optBoolean("attemptedSave") })
                    assertFalse("The original script reported a loop error",trace.any { it.optBoolean("unexpectedError") })
                }
                client.call("script.stop")
            }
        } finally {
            File(context.getExternalFilesDir(null),"chapter-script-diagnostic.json").writeText(JSONArray(trace.toList()).toString(2))
            session.retire()
            File(context.noBackupFilesDir,"embedded-browser/$profile").deleteRecursively()
        }
    }

    @Test fun nestedProgressSettingsPersistAcrossRepeatedUserscriptWrites() {
        assumeTrue("Opt in to the public test document with -e allowBrowserNetwork true",
            InstrumentationRegistry.getArguments().getString("allowBrowserNetwork") == "true")
        val profile = profile()
        val vault = NativePluginVault(context, "storage-fixture-$profile")
        val saved = CountDownLatch(12)
        val errors = ConcurrentLinkedQueue<String>()
        val latest = AtomicReference<JSONObject>()
        try {
            Client(profile) { event ->
                when (event.optString("kind")) {
                    "storage" -> if (event.optString("key") == "timelong") {
                        val value = event.getJSONObject("value")
                        vault.put("settings", JSONObject().put("timelong", value))
                        latest.set(vault.get("settings")!!.getJSONObject("timelong"))
                        saved.countDown()
                    }
                    "error" -> errors.add(event.optString("reason"))
                }
                JSONObject().put("ok", true)
            }.use { client ->
                client.configure()
                val handle = "nested-storage-fixture"
                val declaration = JSONObject("""{
                    "matches":[{"host":"example.com","pathPrefix":"/"}],"exclude":[],
                    "adapter":{"version":"fixture","settings":[],"startSelector":"#fixture-start",
                    "runningSelector":"#fixture-running","logSelector":"#fixture-log"}
                }""")
                val source = """
                    let count=0;
                    function writeProgress(){
                      const record=GM_getValue('timelong',{});
                      record.unused=undefined;
                      record.uFixture={time:++count,today:'2026d9d13',studyDetail:{cFixture:count}};
                      GM_setValue('timelong',record);
                      if(count<12)setTimeout(writeProgress,250);
                    }
                    writeProgress();
                """.trimIndent()
                client.call("script.start", JSONObject().put("handle", handle).put("source", source)
                    .put("declaration", declaration).put("info", JSONObject().put("version", "1.0.0"))
                    .put("values", JSONObject()).put("runAt", "document-end"))
                client.call("page.open", JSONObject().put("url", "https://example.com/")
                    .put("origins", JSONArray().put("https://example.com")).put("scriptHandle", handle)
                    .put("active", false))
                assertTrue("Nested progress writes did not persist; errors=$errors", saved.await(25, TimeUnit.SECONDS))
                client.call("script.stop")
                assertTrue("Successful progress writes reported errors: $errors", errors.isEmpty())
                assertEquals(12, latest.get().getJSONObject("uFixture").getInt("time"))
            }
        } finally {
            vault.clear()
            File(context.noBackupFilesDir, "embedded-browser/$profile").deleteRecursively()
        }
    }

    @Test fun restoredExtensionConnectsBeforeOpeningAnyCourseAndKeepsCookies() {
        val profile = profile()
        try {
            repeat(3) { pass ->
                Client(profile).use { client ->
                    client.configure()
                    // Let last-window and restored-extension disconnect callbacks settle.
                    Thread.sleep(1200)
                    val before = client.call("cookies.read", JSONObject().put("url", "https://example.com/"))
                    assertEquals("Persistent cookie on process start $pass", if (pass == 0) "" else "browser_fixture=retained", before.getString("header"))
                    client.call("cookies.write", JSONObject().put("url", "https://example.com/")
                        .put("cookie", "browser_fixture=retained; Path=/; Secure; Max-Age=3600"))
                    assertEquals("Cookie immediately after writing on pass $pass", "browser_fixture=retained", client.call("cookies.read",
                        JSONObject().put("url", "https://example.com/")).getString("header"))
                }
            }
        } finally { File(context.noBackupFilesDir, "embedded-browser/$profile").deleteRecursively() }
    }

    /** Only the public example.com document is downloaded. GM requests use synthetic Binder replies. */
    @Test fun nativeGmRepliesReachTheRealUserscriptAfterAProcessRestart() {
        assumeTrue("Opt in to the public test document with -e allowBrowserNetwork true",
            InstrumentationRegistry.getArguments().getString("allowBrowserNetwork") == "true")
        val profile = profile()
        try {
            repeat(2) { pass ->
                val received = CountDownLatch(1)
                val observed = AtomicReference<JSONObject>()
                val events = ConcurrentLinkedQueue<String>()
                Client(profile) { event ->
                    events.add(event.optString("kind") + ":" + event.optString("key"))
                    when (event.optString("kind")) {
                        "request" -> JSONObject().put("ok", true).put("response", JSONObject()
                            .put("status", 200).put("url", "https://example.com/native-fixture")
                            .put("headers", JSONObject().put("x-fixture", "native"))
                            .put("body", """{"text":"原生回调","items":[null,7,"done"],"empty":null}"""))
                        "storage" -> {
                            if (event.optString("key") == "callback-probe") {
                                observed.set(event.getJSONObject("value"))
                                received.countDown()
                            }
                            JSONObject().put("ok", true)
                        }
                        else -> JSONObject().put("ok", true)
                    }
                }.use { client ->
                    client.configure()
                    val handle = "native-fixture-$pass"
                    val declaration = JSONObject("""{
                        "matches":[{"host":"example.com","pathPrefix":"/"}],"exclude":[],
                        "adapter":{"version":"fixture","settings":[],"startSelector":"#fixture-start",
                        "runningSelector":"#fixture-running","logSelector":"#fixture-log"}
                    }""")
                    val source = """
                        window.wrappedJSObject.__zfPageFixture = {text:'page-global'};
                        GM_xmlhttpRequest({url:'https://example.com/native-fixture',responseType:'json',
                          onload(r){Promise.resolve().then(()=>GM_setValue('callback-probe',{status:r.status,text:r.response.text,
                            count:r.response.items.length,last:r.response.items[2],empty:r.response.empty===null,
                            pageGlobal:unsafeWindow.__zfPageFixture?.text || '',
                            header:r.responseHeaders.includes('x-fixture: native')}));},
                          onerror(r){GM_setValue('callback-probe',{failed:r.error});}});
                    """.trimIndent()
                    client.call("script.start", JSONObject().put("handle", handle).put("source", source)
                        .put("declaration", declaration).put("info", JSONObject().put("version", "1.0.0"))
                        .put("values", JSONObject()).put("runAt", "document-end"))
                    client.call("page.open", JSONObject().put("url", "https://example.com/")
                        .put("origins", JSONArray().put("https://example.com")).put("scriptHandle", handle)
                        .put("active", false))
                    val completed = received.await(25, TimeUnit.SECONDS)
                    assertTrue("The native GM callback did not reach the script on pass $pass; events=$events", completed)
                    val result = observed.get()
                    assertFalse(result.toString(), result.has("failed"))
                    assertEquals(200, result.getInt("status"))
                    assertEquals("原生回调", result.getString("text"))
                    assertEquals("page-global", result.getString("pageGlobal"))
                    assertEquals(3, result.getInt("count"))
                    assertEquals("done", result.getString("last"))
                    assertTrue(result.getBoolean("empty"))
                    assertTrue(result.getBoolean("header"))
                    client.call("script.stop")
                }
            }
        } finally { File(context.noBackupFilesDir, "embedded-browser/$profile").deleteRecursively() }
    }

    @Test fun nativeControlsReachUserscriptDomListenersAfterSettingsAreWritten() {
        assumeTrue("Opt in to the public test document with -e allowBrowserNetwork true",
            InstrumentationRegistry.getArguments().getString("allowBrowserNetwork") == "true")
        val profile = profile()
        val ready = CountDownLatch(1)
        val available = CountDownLatch(1)
        val clicked = CountDownLatch(1)
        val setup = AtomicReference<JSONObject>()
        try {
            Client(profile) { event ->
                if (event.optString("kind") == "status" && event.optString("stage") == "ready") available.countDown()
                if (event.optString("kind") == "storage") when (event.optString("key")) {
                    "dom-ready" -> { setup.set(event.getJSONObject("value")); ready.countDown() }
                    "dom-clicked" -> clicked.countDown()
                }
                JSONObject().put("ok", true)
            }.use { client ->
                client.configure()
                val handle = "dom-fixture"
                val declaration = JSONObject("""{
                    "matches":[{"host":"example.com","pathPrefix":"/"}],"exclude":[],
                    "adapter":{"version":"fixture","settings":[],"startSelector":"#fixture-start",
                    "runningSelector":"#fixture-running","logSelector":"#fixture-log"}
                }""")
                val source = """
                    GM_setValue('initial-setting',true);
                    setTimeout(()=>{
                      try {
                        const d=unsafeWindow.document, button=d.createElement('button');
                        button.id='fixture-start'; button.textContent='Start fixture'; d.body.appendChild(button);
                        button.addEventListener('click',()=>{
                          button.id='fixture-running'; GM_setValue('dom-clicked',true);
                        });
                        GM_setValue('dom-ready',{ready:unsafeWindow.top===unsafeWindow});
                      } catch(error) {GM_setValue('dom-ready',{error:String(error)});}
                    },250);
                """.trimIndent()
                client.call("script.start", JSONObject().put("handle", handle).put("source", source)
                    .put("declaration", declaration).put("info", JSONObject().put("version", "1.0.0"))
                    .put("values", JSONObject()).put("runAt", "document-end"))
                client.call("page.open", JSONObject().put("url", "https://example.com/")
                    .put("origins", JSONArray().put("https://example.com")).put("scriptHandle", handle)
                    .put("active", false))
                assertTrue("UserScript timer did not run after writing settings", ready.await(20, TimeUnit.SECONDS))
                assertTrue(setup.get().toString(), setup.get().optBoolean("ready"))
                assertTrue("The document did not expose the start control", available.await(5, TimeUnit.SECONDS))
                val result = client.call("script.control", JSONObject().put("handle", handle).put("action", "start"))
                assertTrue(result.toString(), result.getBoolean("accepted"))
                assertTrue("Native click did not reach the userScript DOM listener", clicked.await(5, TimeUnit.SECONDS))
                client.call("script.stop")
            }
        } finally { File(context.noBackupFilesDir, "embedded-browser/$profile").deleteRecursively() }
    }

    @Test fun nativeDialogActionsAndBrowserInputsWorkWithoutOpeningABrowserActivity() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowBrowserNetwork") == "true")
        val profile = profile()
        val dom = CountDownLatch(1)
        val browserPrompts = java.util.concurrent.LinkedBlockingQueue<JSONObject>()
        val domId = AtomicReference<String>()
        val complete = CountDownLatch(1)
        try {
            Client(profile) { event ->
                if (event.optString("kind") == "status") event.optJSONObject("interaction")?.let { domId.set(it.getString("id")); dom.countDown() }
                if (event.optString("kind") == "browser-prompt") browserPrompts.offer(event)
                if (event.optString("kind") == "storage" && event.optString("key") == "native-complete" && event.optBoolean("value")) complete.countDown()
                JSONObject().put("ok", true)
            }.use { client ->
                client.configure()
                val handle = "prompt-fixture"
                val declaration = JSONObject("""{
                    "matches":[{"host":"example.com","pathPrefix":"/"}],"exclude":[],
                    "adapter":{"browserPrompts":true,"version":"fixture","settings":[],"startSelector":"#fixture-start",
                    "runningSelector":"#fixture-running","logSelector":"#fixture-log",
                    "dialogs":[{"id":"notice","selector":"#native-dialog","title":"Native fixture","kind":"notice",
                    "messageSelector":"p","actions":[{"id":"confirm","label":"Continue","selector":"button"}]}]}
                }""")
                val source = """
                    const d=unsafeWindow.document, box=d.createElement('div');
                    box.id='native-dialog';box.innerHTML='<p>Native controls only</p><button>Continue</button>';d.body.appendChild(box);
                    box.querySelector('button').addEventListener('click',()=>{
                      box.remove();setTimeout(()=>{
                        const confirmed=unsafeWindow.confirm('Synthetic confirmation');
                        const text=unsafeWindow.prompt('Synthetic private input');
                        GM_setValue('native-complete',confirmed && text==='fixture-private-input');
                      },100);
                    });
                """.trimIndent()
                client.call("script.start", JSONObject().put("handle", handle).put("source", source).put("declaration", declaration)
                    .put("info", JSONObject().put("version", "1.0.0")).put("values", JSONObject()).put("runAt", "document-end"))
                client.call("page.open", JSONObject().put("url", "https://example.com/").put("origins", JSONArray().put("https://example.com"))
                    .put("scriptHandle", handle).put("active", false))
                assertTrue("DOM notice was not reported", dom.await(20, TimeUnit.SECONDS))
                val answer = JSONObject().put("handle", handle).put("interactionId", domId.get()).put("actionId", "confirm")
                assertTrue(client.call("script.interact", answer).getBoolean("accepted"))
                assertThrows(PluginException::class.java) { client.call("script.interact", answer) }
                val confirmation = browserPrompts.poll(10, TimeUnit.SECONDS)
                assertNotNull("Browser confirmation was silently dismissed", confirmation)
                assertEquals("notice", confirmation!!.getString("promptKind"))
                val confirm = JSONObject().put("handle", handle).put("interactionId", confirmation.getString("interactionId")).put("actionId", "confirm")
                assertTrue(client.call("script.interact", confirm).getBoolean("accepted"))
                assertThrows(PluginException::class.java) { client.call("script.interact", confirm) }
                val input = browserPrompts.poll(10, TimeUnit.SECONDS)
                assertNotNull("Browser text prompt was not forwarded", input)
                assertEquals("input", input!!.getString("promptKind"))
                assertFalse(input.has("defaultValue"))
                assertTrue(client.call("script.interact", JSONObject().put("handle", handle).put("interactionId", input.getString("interactionId"))
                    .put("actionId", "confirm").put("text", "fixture-private-input")).getBoolean("accepted"))
                assertTrue("Responses did not reach the original script", complete.await(10, TimeUnit.SECONDS))
                client.call("script.stop")
            }
        } finally { File(context.noBackupFilesDir, "embedded-browser/$profile").deleteRecursively() }
    }

    @Test fun nativeStartWaitsForDelayedEntryAndTaskListeners() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowBrowserNetwork") == "true")
        val profile = profile()
        val entryReady = CountDownLatch(1)
        val taskReady = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val started = CountDownLatch(1)
        try {
            Client(profile) { event ->
                if (event.optString("kind") == "status") when (event.optString("stage")) {
                    "entry" -> entryReady.countDown()
                    "ready" -> taskReady.countDown()
                }
                if (event.optString("kind") == "storage") when (event.optString("key")) {
                    "entered" -> entered.countDown()
                    "started" -> started.countDown()
                }
                JSONObject().put("ok", true)
            }.use { client ->
                client.configure()
                val handle = "delayed-controls"
                val declaration = JSONObject("""{
                    "matches":[{"host":"example.com","pathPrefix":"/"}],"exclude":[],
                    "adapter":{"version":"fixture","settings":[],"entrySelector":"#delayed-entry",
                    "startSelector":"#delayed-start","runningSelector":"#delayed-running","logSelector":"#delayed-log"}
                }""")
                val source = """
                    const d=unsafeWindow.document, entry=d.createElement('button');
                    entry.id='delayed-entry';entry.textContent='Enter';d.body.appendChild(entry);
                    setTimeout(()=>entry.addEventListener('click',()=>{
                      entry.remove();GM_setValue('entered',true);
                      const start=d.createElement('button');
                      start.id='delayed-start';start.textContent='Start';d.body.appendChild(start);
                      setTimeout(()=>{start.onclick=()=>{
                        start.id='delayed-running';GM_setValue('started',true);
                      };},800);
                    }),800);
                """.trimIndent()
                client.call("script.start", JSONObject().put("handle", handle).put("source", source)
                    .put("declaration", declaration).put("info", JSONObject().put("version", "1.0.0"))
                    .put("values", JSONObject()).put("runAt", "document-end"))
                client.call("page.open", JSONObject().put("url", "https://example.com/")
                    .put("origins", JSONArray().put("https://example.com")).put("scriptHandle", handle).put("active", false))
                assertTrue("Entry was not ready", entryReady.await(20, TimeUnit.SECONDS))
                val input = JSONObject().put("handle", handle).put("action", "start")
                assertTrue(client.call("script.control", input).getBoolean("accepted"))
                assertTrue("Entry was clicked before its listener was attached", entered.await(5, TimeUnit.SECONDS))
                assertTrue("Task control was not ready", taskReady.await(10, TimeUnit.SECONDS))
                assertTrue(client.call("script.control", input).getBoolean("accepted"))
                assertTrue("Task was clicked before its listener was attached", started.await(5, TimeUnit.SECONDS))
                client.call("script.stop")
            }
        } finally { File(context.noBackupFilesDir, "embedded-browser/$profile").deleteRecursively() }
    }

    @Test fun backgroundTimersAndRequestsContinueAfterTheBrowserIsMinimized() {
        backgroundTimersAndRequestsContinue(minimizeBrowser = true)
    }

    @Test fun backgroundTimersAndRequestsContinueAfterHomeAndStopWhenRequested() {
        backgroundTimersAndRequestsContinue(minimizeBrowser = false)
    }

    private fun backgroundTimersAndRequestsContinue(minimizeBrowser: Boolean) {
        assumeTrue("Opt in to the public test document with -e allowBrowserNetwork true",
            InstrumentationRegistry.getArguments().getString("allowBrowserNetwork") == "true")
        val profile = profile()
        val handle = "background-fixture"
        val replies = AtomicReference(CountDownLatch(3))
        val received = AtomicInteger()
        val failures = AtomicInteger()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            Client(profile) { event ->
                when (event.optString("kind")) {
                    "request" -> JSONObject().put("ok", true).put("response", JSONObject()
                        .put("status", 200).put("url", "https://example.com/background-fixture")
                        .put("headers", JSONObject()).put("body", "fixture-ok"))
                    "storage" -> {
                        if (event.optString("key") == "background-tick") {
                            if (event.optBoolean("value")) {
                                received.incrementAndGet()
                                replies.get().countDown()
                            } else failures.incrementAndGet()
                        }
                        JSONObject().put("ok", true)
                    }
                    else -> JSONObject().put("ok", true)
                }
            }.use { client ->
                client.configure()
                val declaration = JSONObject("""{
                    "matches":[{"host":"example.com","pathPrefix":"/"}],"exclude":[],
                    "adapter":{"version":"fixture","settings":[],"startSelector":"#fixture-start",
                    "runningSelector":"#fixture-running","logSelector":"#fixture-log"}
                }""")
                client.call("script.start", JSONObject().put("handle", handle).put("source", """
                    setInterval(() => GM_xmlhttpRequest({url:'https://example.com/background-fixture',
                      onload(r){GM_setValue('background-tick',r.status===200&&r.responseText==='fixture-ok');},
                      onerror(){GM_setValue('background-tick',false);}}), 1000);
                """.trimIndent()).put("declaration", declaration).put("info", JSONObject().put("version", "1.0.0"))
                    .put("values", JSONObject()).put("runAt", "document-end"))
                val page = client.call("page.open", JSONObject().put("url", "https://example.com/")
                    .put("origins", JSONArray().put("https://example.com")).put("scriptHandle", handle).put("active", true))
                context.startActivity(Intent(context, PluginEmbeddedBrowserActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("handle", page.getString("handle")))
                assertNotNull("Browser activity did not appear", device.wait(Until.findObject(By.text("收起")), 10_000))
                PluginForegroundWork.add(context, handle, PluginForegroundWork.Entry("synthetic.background", "后台运行测试") {})
                assertTrue("The script never started its timers", replies.get().await(25, TimeUnit.SECONDS))
                if (minimizeBrowser) device.findObject(By.text("收起")).click()
                device.pressHome()
                val expected = if (minimizeBrowser) 8 else 35
                val backgroundReplies = CountDownLatch(expected)
                replies.set(backgroundReplies)
                val continued = backgroundReplies.await(55, TimeUnit.SECONDS)
                assertTrue("Timers or GM replies stopped in the background: received=${expected-backgroundReplies.count}, failures=${failures.get()}", continued)
                assertEquals("Background requests failed", 0, failures.get())
                assertEquals(page.getString("handle"), client.call("page.status", JSONObject()
                    .put("handle", page.getString("handle"))).getString("handle"))
                context.startActivity(Intent(context, PluginEmbeddedBrowserActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("handle", page.getString("handle")))
                assertNotNull("Browser activity did not reopen", device.wait(Until.findObject(By.text("收起")), 10_000))
                val resumedReplies = CountDownLatch(3)
                replies.set(resumedReplies)
                assertTrue("The script stopped when its browser reopened", resumedReplies.await(10, TimeUnit.SECONDS))
                client.call("script.stop")
                assertThrows(PluginException::class.java) {
                    client.call("page.status", JSONObject().put("handle", page.getString("handle")))
                }
                Thread.sleep(1200) // Drain a reply already in flight when stop was requested.
                val stoppedAt = received.get()
                Thread.sleep(2500)
                assertEquals("The stopped script kept making requests", stoppedAt, received.get())
            }
        } finally {
            PluginForegroundWork.remove(context, handle)
            File(context.noBackupFilesDir, "embedded-browser/$profile").deleteRecursively()
        }
    }

    private inner class Client(
        private val profile: String,
        private val respond: (JSONObject) -> JSONObject = { JSONObject().put("ok", true) }
    ) : AutoCloseable {
        private val connected = CountDownLatch(1)
        private val died = CountDownLatch(1)
        private val remote = AtomicReference<IBinder>()
        private var initialized = false
        private val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(PluginBrowserWire.DESCRIPTOR)
                val event = PluginBrowserWire.read(data)
                data.readStrongBinder()
                val value = respond(event)
                reply!!.writeNoException()
                PluginBrowserWire.write(reply, JSONObject().put("ok", true).put("data", value))
                return true
            }
        }
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                remote.set(service)
                service.linkToDeath({ died.countDown() }, 0)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { died.countDown() }
        }
        init {
            assertTrue(context.bindService(Intent(context, PluginEmbeddedBrowserService::class.java),
                connection, Context.BIND_AUTO_CREATE))
            assertTrue("Browser service did not bind", connected.await(10, TimeUnit.SECONDS))
        }
        fun configure(configuration: JSONObject = JSONObject("""{
                "title":"Synthetic Gecko test","origins":["https://example.com"],
                "network":[{"origin":"https://example.com","pathPrefix":"/","methods":["GET"],"purposes":["query"]}]
            }""")) {
            call("configure", configuration)
            initialized = true
        }
        fun call(action: String, input: JSONObject = JSONObject()): JSONObject =
            PluginBrowserWire.call(remote.get(), JSONObject().put("profile", profile)
                .put("action", action).put("input", input), callback)

        override fun close() {
            try { if (initialized && remote.get().isBinderAlive) call("shutdown") }
            finally { context.unbindService(connection) }
            if (initialized) assertTrue("Browser process did not stop", died.await(10, TimeUnit.SECONDS))
        }
    }
}
