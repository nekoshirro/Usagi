package org.draken.usagi.core.network.webview

import android.content.Context
import android.util.AndroidRuntimeException
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.draken.usagi.core.exceptions.CloudFlareException
import org.draken.usagi.core.network.CommonHeaders
import org.draken.usagi.core.network.cookies.MutableCookieJar
import org.draken.usagi.core.network.proxy.ProxyProvider
import org.draken.usagi.core.parser.MangaParserRepository
import org.draken.usagi.core.parser.MangaRepository
import org.draken.usagi.core.util.ext.configureForParser
import org.draken.usagi.core.util.ext.printStackTraceDebug
import org.json.JSONTokener
import tsuki.model.MangaSource
import tsuki.util.runCatchingCancellable
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Singleton
class WebViewExecutor
	@Inject
	constructor(
		@ApplicationContext private val context: Context,
		private val proxyProvider: ProxyProvider,
		private val cookieJar: MutableCookieJar,
		private val mangaRepositoryFactoryProvider: Provider<MangaRepository.Factory>,
	) {
		private var webViewCached: WeakReference<WebView>? = null
		private val mutex = Mutex()

		val defaultUserAgent: String? by lazy {
			try {
				WebSettings.getDefaultUserAgent(context)
			} catch (e: AndroidRuntimeException) {
				e.printStackTraceDebug()
				// Probably WebView is not available
				null
			}
		}

		suspend fun evaluateJs(
			baseUrl: String?,
			script: String,
		): String? =
			mutex.withLock {
				withContext(Dispatchers.Main.immediate) {
					val webView = obtainWebView()
					try {
						if (!baseUrl.isNullOrEmpty()) {
							suspendCoroutine { cont ->
								webView.webViewClient = ContinuationResumeWebViewClient(cont)
								webView.loadDataWithBaseURL(baseUrl, " ", "text/html", null, null)
							}
						}
						suspendCoroutine { cont ->
							webView.evaluateJavascript(script) { result ->
								cont.resume(result?.takeUnless { it == "null" })
							}
						}
					} finally {
						webView.reset()
					}
				}
			}

		suspend fun tryResolveCaptcha(
			exception: CloudFlareException,
			timeout: Long,
		): Boolean =
			mutex.withLock {
				runCatchingCancellable {
					withContext(Dispatchers.Main.immediate) {
						val webView = obtainWebView()
						try {
							exception.source.getUserAgent()?.let {
								webView.settings.userAgentString = it
							}
							withTimeout(timeout) {
								suspendCancellableCoroutine { cont ->
									webView.webViewClient =
										CaptchaContinuationClient(
											cookieJar = cookieJar,
											targetUrl = exception.url,
											continuation = cont,
										)
									webView.loadUrl(exception.url)
								}
							}
						} finally {
							webView.reset()
						}
					}
				}.onFailure { e ->
					exception.addSuppressed(e)
					e.printStackTraceDebug()
				}.isSuccess
			}

		suspend fun fetchHtml(
			url: String,
			userAgent: String? = null,
			timeout: Long = 20_000L,
			method: String = "GET",
			body: String? = null,
			contentType: String? = null,
		): String? =
			mutex.withLock {
				runCatchingCancellable {
					withContext(Dispatchers.Main.immediate) {
						val webView = obtainWebView()
						try {
							userAgent?.let { webView.settings.userAgentString = it }
							withTimeout(timeout) {
								val pageLoaded = CompletableDeferred<Unit>()
								webView.webViewClient =
									object : WebViewClient() {
										override fun onPageFinished(
											view: WebView?,
											loadedUrl: String?,
										) {
											if (!pageLoaded.isCompleted) {
												pageLoaded.complete(Unit)
											}
										}
									}
								val loadTarget =
									if (method.equals("POST", ignoreCase = true)) {
										val parsed = android.net.Uri.parse(url)
										"${parsed.scheme}://${parsed.host}/"
									} else {
										url
									}
								webView.loadUrl(loadTarget)
								pageLoaded.await()
								var html: String? = null
								while (html == null) {
									val raw =
										suspendCancellableCoroutine { cont ->
											webView.evaluateJavascript(
												"(function(){return document.title + '\\n' + document.documentElement.outerHTML;})()",
											) { res ->
												cont.resume(res)
											}
										}
									val decoded =
										if (raw != null && raw != "null") {
											JSONTokener(raw).nextValue() as? String
										} else {
											null
										}
									if (decoded != null) {
										val nl = decoded.indexOf('\n')
										val title = if (nl >= 0) decoded.substring(0, nl) else ""
										val docBody = if (nl >= 0) decoded.substring(nl + 1) else decoded
										val isChallenge =
											title.contains("Just a moment", ignoreCase = true) ||
												docBody.contains("cf-challenge") ||
												docBody.contains("challenge-platform")
										if (!isChallenge && docBody.length > 200) {
											html = docBody
										}
									}
									if (html == null) {
										delay(400L)
									}
								}
								if (method.equals("POST", ignoreCase = true)) {
									val urlJson = org.json.JSONObject.quote(url)
									val bodyJson = body?.let { org.json.JSONObject.quote(it) } ?: "undefined"
									val headersJson =
										if (contentType != null) {
											"{'Content-Type':${org.json.JSONObject.quote(contentType)},'X-Requested-With':'XMLHttpRequest'}"
										} else {
											"{'X-Requested-With':'XMLHttpRequest'}"
										}
									val script =
										"(function(){window.__uDone=false;window.__uRes=null;" +
											"fetch($urlJson,{method:'POST',credentials:'include',headers:$headersJson,body:$bodyJson})" +
											".then(function(r){return r.text();})" +
											".then(function(t){window.__uRes=t;window.__uDone=true;})" +
											".catch(function(){window.__uDone=true;});})();"
									webView.evaluateJavascript(script, null)
									while (true) {
										val done =
											suspendCancellableCoroutine<String?> { cont ->
												webView.evaluateJavascript("window.__uDone") { cont.resume(it) }
											}
										if (done == "true") break
										delay(150L)
									}
									val rawRes =
										suspendCancellableCoroutine<String?> { cont ->
											webView.evaluateJavascript("window.__uRes") { cont.resume(it) }
										}
									html =
										if (rawRes != null && rawRes != "null") {
											JSONTokener(rawRes).nextValue() as? String
										} else {
											null
										}
								}
								html
							}
						} finally {
							webView.reset()
						}
					}
				}.onFailure { it.printStackTraceDebug() }.getOrNull()
			}

		private suspend fun obtainWebView(): WebView {
			webViewCached?.get()?.let {
				return it
			}
			return withContext(Dispatchers.Main.immediate) {
				webViewCached?.get()?.let {
					return@withContext it
				}
				WebView(context).also {
					it.configureForParser(null)
					webViewCached = WeakReference(it)
					proxyProvider.applyWebViewConfig()
					it.onResume()
					it.resumeTimers()
				}
			}
		}

		private fun MangaSource.getUserAgent(): String? {
			val repository = mangaRepositoryFactoryProvider.get().create(this) as? MangaParserRepository
			return repository?.getRequestHeaders()?.get(CommonHeaders.USER_AGENT)
		}

		@MainThread
		private fun WebView.reset() {
			stopLoading()
			webViewClient = WebViewClient()
			settings.userAgentString = defaultUserAgent
			loadDataWithBaseURL(null, " ", "text/html", null, null)
			clearHistory()
		}
	}
