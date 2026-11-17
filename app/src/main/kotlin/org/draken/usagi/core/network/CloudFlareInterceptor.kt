package org.draken.usagi.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.IOException
import org.draken.usagi.core.exceptions.CloudFlareBlockedException
import org.draken.usagi.core.exceptions.CloudFlareProtectedException
import org.draken.usagi.core.network.webview.WebViewExecutor
import tsuki.model.MangaSource
import tsuki.network.CloudFlareHelper
import javax.inject.Provider

class CloudFlareInterceptor(
	private val webViewExecutorProvider: Provider<WebViewExecutor>,
) : Interceptor {
	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)
		return when (CloudFlareHelper.checkResponseForProtection(response)) {
			CloudFlareHelper.PROTECTION_BLOCKED -> {
				response.closeThrowing(
					CloudFlareBlockedException(
						url = request.url.toString(),
						source = request.tag(MangaSource::class.java),
					),
				)
			}

			CloudFlareHelper.PROTECTION_CAPTCHA -> {
				val isImage = request.header("Accept")?.contains("image/") == true
				val isSupportedMethod = request.method == "GET" || request.method == "POST"
				if (isSupportedMethod && !isImage) {
					val reqBody =
						if (request.method == "POST") {
							request.body?.let { b ->
								okio.Buffer().also { b.writeTo(it) }.readUtf8()
							}
						} else {
							null
						}
					val html =
						runBlocking {
							webViewExecutorProvider.get().fetchHtml(
								url = request.url.toString(),
								userAgent = request.header("User-Agent"),
								method = request.method,
								body = reqBody,
								contentType = request.body?.contentType()?.toString(),
							)
						}
					if (html != null) {
						val protocol = response.protocol
						response.close()
						return Response
							.Builder()
							.request(request)
							.protocol(protocol)
							.code(200)
							.message("OK")
							.body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
							.build()
					}
				}
				response.closeThrowing(
					CloudFlareProtectedException(
						url = request.url.toString(),
						source = request.tag(MangaSource::class.java),
						headers = request.headers,
					),
				)
			}

			else -> {
				response
			}
		}
	}

	private fun Response.closeThrowing(error: IOException): Nothing {
		try {
			close()
		} catch (e: Exception) {
			error.addSuppressed(e)
		}
		throw error
	}
}
