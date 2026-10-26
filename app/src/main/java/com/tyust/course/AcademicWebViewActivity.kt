package com.tyust.course

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.WebChromeClient
import com.tyust.course.academic.AcademicUrlPolicy
import com.tyust.course.academic.AcademicRedirects
import com.tyust.course.academic.AcademicPasswordLoginGateway
import com.tyust.course.academic.LntuWebLoginAssist
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import com.tyust.course.ui.system.GlassPageScaffold
import com.tyust.course.ui.system.SystemIconButton
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.theme.CourseSelectorTheme
import com.tyust.course.login.WebLoginNavigation
import com.tyust.course.ui.screen.WebLoginAddressBar

/**
 * Interactive login browser for captcha and SSO pages. Navigation may cross
 * domains; cookie export remains bound to the configured academic address.
 * It has no JavaScript bridge and uses a separate WebView storage directory.
 */
class AcademicWebViewActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.tyust.course.manager.AppThemeCoordinator.wrapContext(newBase))
    }
    companion object {
        const val EXTRA_START_URL = "academic_webview_start_url"
        const val EXTRA_ALLOWED_HOSTS = "academic_webview_allowed_hosts"
        const val EXTRA_COOKIE_RESULT = CookieWebViewActivity.EXTRA_COOKIE_RESULT
        const val EXTRA_COOKIE_URL = "academic_cookie_url"
        const val EXTRA_PAGE_URL = "academic_page_url"
        const val EXTRA_SEARCH_KEYWORD = "academic_search_keyword"
        const val EXTRA_COMPLETION_URL = "academic_completion_url"
        const val EXTRA_SERVLET_SESSION = "academic_servlet_session"
        const val EXTRA_USERNAME = "academic_login_username"
        const val EXTRA_PASSWORD = "academic_login_password"
        private var suffixConfigured = false
    }

    private var webView: WebView? = null
    private var startUrl = ""
    private var cookieUrl = ""
    private var searchUrl = ""
    private var completionUrl = ""
    private var servletSession = false
    private var assistedLogin = false
    private var assistedUsername = ""
    private var assistedPassword = ""
    private var submissionAttempted = false
    private var submissionInFlight = false
    private var readinessRetries = 0
    private var portalVisited = false
    private var currentUrl by mutableStateOf("")
    private var allowedHosts: Set<String> = emptySet()
    private var loadingProgress by mutableFloatStateOf(0f)
    private var pageError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAdaptiveOrientation()
        startUrl = intent.getStringExtra(EXTRA_START_URL).orEmpty()
        cookieUrl = intent.getStringExtra(EXTRA_COOKIE_URL).orEmpty().ifBlank { startUrl }
        completionUrl = intent.getStringExtra(EXTRA_COMPLETION_URL).orEmpty()
        servletSession = intent.getBooleanExtra(EXTRA_SERVLET_SESSION, false)
        assistedLogin = LntuWebLoginAssist.supports(startUrl, cookieUrl, completionUrl)
        if (assistedLogin) {
            assistedUsername = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
            assistedPassword = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        }
        // Credentials are only held for this attempt, never in browser URLs or saved state.
        intent.removeExtra(EXTRA_USERNAME)
        intent.removeExtra(EXTRA_PASSWORD)
        submissionAttempted = savedInstanceState?.getBoolean("submissionAttempted") ?: false
        portalVisited = savedInstanceState?.getBoolean("portalVisited") ?: false
        val keyword = intent.getStringExtra(EXTRA_SEARCH_KEYWORD).orEmpty()
        searchUrl = WebLoginNavigation.searchUrl(keyword.ifBlank { "教务系统 登录" })
        val initialUrl = if (keyword.isNotBlank()) searchUrl else startUrl
        currentUrl = initialUrl
        allowedHosts = (intent.getStringArrayListExtra(EXTRA_ALLOWED_HOSTS).orEmpty())
            .map { normalizeHost(it) }
            .filter { it.isNotBlank() }
            .toSet()
        if (!WebLoginNavigation.isWebUrl(startUrl) || !isCookieUrlAllowed() ||
            (completionUrl.isNotBlank() && !AcademicUrlPolicy.isAllowed(completionUrl, Uri.parse(startUrl).scheme.orEmpty(), allowedHosts))) {
            Toast.makeText(this, "教务地址不在允许范围内", Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !suffixConfigured) {
            WebView.setDataDirectorySuffix("academic")
            suffixConfigured = true
        }
        val browser = createWebView()
        webView = browser
        setContent {
            CourseSelectorTheme {
                BackHandler { navigateBack() }
                val keyboardOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
                GlassPageScaffold(
                    title = "教务网页登录",
                    subtitle = Uri.parse(currentUrl).host,
                    modifier = Modifier.imePadding(),
                    topBar = {
                        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            IconButton(::navigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                            Column(Modifier.weight(1f)) {
                                Text("教务网页登录", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium)
                                if (!keyboardOpen) Text(Uri.parse(currentUrl).host.orEmpty(), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton({ browser.loadUrl(startUrl) }) { Icon(Icons.Default.School, "教务入口") }
                            IconButton({ browser.reload() }) { Icon(Icons.Default.Refresh, "刷新网页") }
                            if (keyboardOpen) androidx.compose.material3.TextButton(::finishWithCookie) { Text("完成") }
                        }
                    },
                    onBack = ::navigateBack,
                    actions = {
                        SystemIconButton(Icons.Default.School, "教务入口", { browser.loadUrl(startUrl) })
                        SystemIconButton(Icons.Default.Refresh, "刷新网页", { browser.reload() })
                    }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
                        WebLoginAddressBar(currentUrl, ::navigateToInput, { browser.loadUrl(searchUrl) })
                        Spacer(Modifier.height(8.dp))
                        if (loadingProgress < 1f) {
                            LinearProgressIndicator(progress = { loadingProgress }, modifier = Modifier.fillMaxWidth())
                        }
                        AndroidView(
                            factory = { browser },
                            modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).testTag("academic-webview")
                        )
                        if (!keyboardOpen) Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = pageError ?: if (assistedLogin) "请完成学校验证，进入教务后将自动返回应用" else "完成学校验证后，点“完成登录”返回应用",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (pageError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            SystemPrimaryButton("完成登录", ::finishWithCookie, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        fun loadWhenLaidOut() {
            browser.post { if (!isFinishing && !isDestroyed) browser.doOnLayout {
                if (!isFinishing && !isDestroyed && it.width > 0 && it.height > 0) {
                    val saved = savedInstanceState?.getBundle("browser")
                    if (saved == null || browser.restoreState(saved) == null) browser.loadUrl(initialUrl)
                }
            } }
        }
        if (savedInstanceState?.containsKey("browser") == true) {
            loadWhenLaidOut()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            CookieManager.getInstance().removeAllCookies { loadWhenLaidOut() }
        } else {
            // Before API 28 WebView has no per-process storage suffix. Clear only
            // the configured school cookies instead of unrelated browser sessions.
            for (host in allowedHosts) {
                val url = Uri.parse(startUrl).scheme + "://" + host + "/"
                CookieManager.getInstance().getCookie(url).orEmpty().split(';').forEach { part ->
                    val name = part.substringBefore('=').trim()
                    if (name.isNotEmpty()) CookieManager.getInstance().setCookie(url, name + "=; Max-Age=0; Path=/")
                }
            }
            loadWhenLaidOut()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        val webViewInstance = this
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        com.tyust.course.manager.AppThemeCoordinator.preserveWebContentColors(settings)
        settings.domStorageEnabled = true
        settings.defaultTextEncodingName = "UTF-8"
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webViewInstance, true)
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loadingProgress = newProgress / 100f
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageError = null
                currentUrl = url
                loadingProgress = 0f
            }
            override fun onPageFinished(view: WebView, url: String) {
                loadingProgress = 1f
                currentUrl = url
                if (view.url == url) continueSchoolLogin(view, url)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    pageError = "网页暂时无法加载，请点击右上角刷新"
                    loadingProgress = 1f
                }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) pageError = "网页返回 HTTP ${response.statusCode}，可修改网址或搜索学校入口"
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.scheme in setOf("data", "blob", "about") || WebLoginNavigation.isWebUrl(request.url.toString())) return null
                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return handleNavigation(request.url.toString(), request.isForMainFrame)
            }

            @Deprecated("API 21 compatibility")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleNavigation(url, true)
            }
        }
    }

    private fun finishWithCookie() {
        if (isFinishing || isDestroyed) return
        if (!isCookieUrlAllowed()) return
        if (completionUrl.isNotBlank() && !AcademicPasswordLoginGateway.matchesCompletion(completionUrl, webView?.url.orEmpty(), servletSession)) {
            pageError = "请先完成学校验证并进入教务主页"
            return
        }
        val cookie = CookieManager.getInstance().getCookie(cookieUrl).orEmpty().trim()
        if (cookie.isBlank()) {
            Toast.makeText(this, "请先登录并进入 ${Uri.parse(cookieUrl).host} 的教务主页", Toast.LENGTH_LONG).show()
            return
        }
        CookieManager.getInstance().flush()
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_COOKIE_RESULT, cookie).putExtra(EXTRA_PAGE_URL, webView?.url))
        finish()
    }

    private fun continueSchoolLogin(browser: WebView, url: String) {
        if (!assistedLogin || isFinishing || isDestroyed) return
        when {
            LntuWebLoginAssist.isLoginPage(url) && !submissionAttempted && !submissionInFlight &&
                assistedUsername.isNotBlank() && assistedPassword.isNotEmpty() -> {
                submissionInFlight = true
                val script = LntuWebLoginAssist.submitScript(assistedUsername, assistedPassword)
                browser.evaluateJavascript(script) { result ->
                    submissionInFlight = false
                    if (isFinishing || isDestroyed) return@evaluateJavascript
                    if (BuildConfig.DEBUG) android.util.Log.d("AcademicWebLogin", "LNTU form result=$result")
                    if (browser.url == url && result == "\"form-unavailable\"" && readinessRetries++ < 40) {
                        browser.postDelayed({ if (browser.url == url) continueSchoolLogin(browser, url) }, 250)
                    } else {
                        submissionAttempted = true
                        assistedPassword = ""
                        if (browser.url == url && result !in setOf("\"submitted\"", "\"already-submitted\"", "\"manual\"")) {
                            pageError = "学校登录页暂不支持自动填写，请在网页中继续登录"
                        }
                    }
                }
            }
            LntuWebLoginAssist.isPortalPage(url) && !portalVisited -> {
                portalVisited = true
                browser.loadUrl(completionUrl)
            }
            LntuWebLoginAssist.isCompletionPage(url) -> {
                browser.evaluateJavascript(LntuWebLoginAssist.signedInScript(assistedUsername)) { result ->
                    if (result == "true" && !isFinishing && !isDestroyed && browser.url == url) finishWithCookie()
                }
            }
        }
    }

    private fun navigateBack() {
        if (webView?.canGoBack() == true) webView?.goBack()
        else { setResult(Activity.RESULT_CANCELED); finish() }
    }

    private fun isCookieUrlAllowed(): Boolean =
        AcademicUrlPolicy.isAllowed(cookieUrl, Uri.parse(startUrl).scheme.orEmpty(), allowedHosts)

    private fun navigateToInput(input: String) {
        val target = WebLoginNavigation.resolveInput(input)
        if (target == null) Toast.makeText(this, "请输入网址或搜索关键词", Toast.LENGTH_SHORT).show()
        else webView?.loadUrl(target)
    }

    private fun handleNavigation(url: String, mainFrame: Boolean): Boolean {
        if (mainFrame) {
            val source = startUrl.toHttpUrlOrNull()
            val target = url.toHttpUrlOrNull()
            if (source != null && target != null) {
                val secure = AcademicRedirects.preferVerifiedHttps(source, target)
                if (secure != target) { webView?.loadUrl(secure.toString()); return true }
            }
        }
        if (WebLoginNavigation.isWebUrl(url) || url == "about:blank" || url.startsWith("javascript:", true)) return false
        if (mainFrame) Toast.makeText(this, "请使用网页方式继续登录", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun normalizeHost(raw: String): String {
        val value = raw.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
        return value.lowercase().trim('.')
    }

    override fun onDestroy() {
        assistedPassword = ""
        assistedUsername = ""
        webView?.apply { stopLoading(); destroy() }
        webView = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("submissionAttempted", submissionAttempted)
        outState.putBoolean("portalVisited", portalVisited)
        webView?.let { browser -> outState.putBundle("browser", Bundle().also { browser.saveState(it) }) }
        super.onSaveInstanceState(outState)
    }
}
