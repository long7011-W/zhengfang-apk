package com.tyust.course.academic

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Only the verified LNTU deployment can receive a pending password login. */
internal object LntuWebLoginAssist {
    const val ORIGIN = "https://webvpn.lntu.edu.cn"
    const val COMPLETION_URL = ORIGIN + AcademicSchoolProfiles.LNTU_BASE_PATH + "/homeExt.action"
    private val login = AcademicSchoolProfiles.LNTU_LOGIN_URL.toHttpUrlOrNull()!!
    private val cookie = (ORIGIN + AcademicSchoolProfiles.LNTU_BASE_PATH + "/").toHttpUrlOrNull()!!

    fun supports(startUrl: String, cookieUrl: String, completionUrl: String): Boolean =
        (startUrl == "$ORIGIN/" || isLoginPage(startUrl)) &&
            cookieUrl.toHttpUrlOrNull() == cookie && completionUrl == COMPLETION_URL

    private fun trusted(url: HttpUrl): Boolean = url.isHttps && url.host == login.host &&
        url.port == 443 && url.username.isEmpty() && url.password.isEmpty()

    fun isLoginPage(raw: String): Boolean {
        val url = raw.toHttpUrlOrNull() ?: return false
        return trusted(url) && url.encodedPath == login.encodedPath && url.fragment == null &&
            url.queryParameterNames == setOf("service") &&
            url.queryParameterValues("service") == login.queryParameterValues("service")
    }

    fun isPortalPage(raw: String): Boolean {
        val url = raw.toHttpUrlOrNull() ?: return false
        return trusted(url) && url.encodedPath == "/" && url.query == null
    }

    fun isCompletionPage(raw: String): Boolean =
        AcademicPasswordLoginGateway.matchesCompletion(COMPLETION_URL, raw, servletSession = true)

    /** Invoke the school's click handler so its encryption and captcha checks still run. */
    fun submitScript(username: String, password: String): String = """
        (function() {
          var page = new URL(window.location.href);
          if (window.top !== window || page.origin !== ${quote(ORIGIN)} ||
              page.pathname !== ${quote(login.encodedPath)} || page.hash ||
              Array.from(page.searchParams.keys()).length !== 1 ||
              page.searchParams.get('service') !== ${quote(login.queryParameter("service")!!)}) return 'untrusted';
          var field = document.querySelector('#pwdLoginDiv input#password[type=password][name=userPassword]');
          var form = field && field.form;
          var user = form && form.querySelector('input#username[name=username]');
          var button = form && form.querySelector('#login_submit');
          if (!form || form.id !== 'loginFromId' || !user || !button || field.disabled ||
              !form.querySelector('input#saltPassword[name=password]') ||
              !form.querySelector('input#pwdEncryptSalt') ||
              typeof window.checkNeedCaptcha !== 'function') return 'form-unavailable';
          var action = new URL(form.action || form.getAttribute('action') || page.href, page.href);
          // WebVPN's DOM accessors expose the original CAS action while transporting it through the proxy.
          var sameCas = (action.origin === page.origin && action.pathname === page.pathname) ||
            (action.origin === 'https://authserver.lntu.edu.cn' && action.pathname === '/authserver/login');
          if (form.method.toLowerCase() !== 'post' || (form.target && form.target !== '_self') ||
              !sameCas || action.username || action.password || action.hash ||
              (action.search && (Array.from(action.searchParams.keys()).length !== 1 ||
                action.searchParams.get('service') !== page.searchParams.get('service')))) return 'untrusted';
          if (form.getAttribute('data-academic-login-submitted') === 'true') return 'already-submitted';
          var account = ${quote(username)};
          if ((user.value && user.value !== account) || field.value) return 'manual';
          form.setAttribute('data-academic-login-submitted', 'true');
          var setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
          setValue.call(user, account);
          user.dispatchEvent(new Event('input', {bubbles: true}));
          user.dispatchEvent(new Event('change', {bubbles: true}));
          // Run the same synchronous check used by the school's username blur handler.
          // Its legacy jQuery may not receive a synthetic focusout event from WebView.
          window.checkNeedCaptcha();
          setValue.call(field, ${quote(password)});
          field.dispatchEvent(new Event('input', {bubbles: true}));
          field.dispatchEvent(new Event('change', {bubbles: true}));
          button.click();
          return 'submitted';
        })();
    """.trimIndent()

    /** Cookies alone do not prove login: wait for the academic page's identity link. */
    fun signedInScript(username: String): String = """
        (function() {
          if (window.top !== window || location.origin !== ${quote(ORIGIN)} ||
              location.pathname.replace(/;jsessionid=[A-Za-z0-9._-]{1,128}${'$'}/, '') !==
                ${quote(AcademicSchoolProfiles.LNTU_BASE_PATH + "/homeExt.action")}) return false;
          var expected = ${quote(username)};
          return Array.from(document.querySelectorAll('a[href]')).some(function(a) {
            var url = new URL(a.getAttribute('href'), location.href);
            var path = url.pathname.replace(/;jsessionid=[A-Za-z0-9._-]{1,128}${'$'}/, '');
            var match = /^.+?\s*[（(]([^()（）\s]+)[）)]${'$'}/.exec(a.textContent.trim());
            return url.origin === location.origin &&
              [${quote(AcademicSchoolProfiles.LNTU_BASE_PATH + "/homeExt.action")},
               ${quote(AcademicSchoolProfiles.LNTU_BASE_PATH + "/security/my.action")},
               '/eams/homeExt.action', '/eams/security/my.action'].indexOf(path) !== -1 &&
              match && (!expected || match[1] === expected);
          });
        })();
    """.trimIndent()

    private fun quote(value: String): String = JSONObject.quote(value)
        .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
}
