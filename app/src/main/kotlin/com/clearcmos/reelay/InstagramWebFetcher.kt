package com.clearcmos.reelay

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Loads an Instagram page through a hidden WebView and returns its server-rendered HTML.
 *
 * Instagram only serves the media JSON to clients whose TLS handshake looks like a
 * browser; OkHttp and curl receive an empty HTML shell (verified 2026-08-29, see
 * CLAUDE.md). Android System WebView is Chromium, so its handshake passes. Every
 * subresource request is answered with an empty body so only the document is fetched.
 */
class InstagramWebFetcher(private val context: Context) {
    data class Page(val finalUrl: String, val html: String)

    suspend fun fetch(url: String): Page = withContext(Dispatchers.Main) {
        val webView = WebView(context)
        try {
            withTimeout(TIMEOUT_MS) { load(webView, url) }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Instagram page did not load within ${TIMEOUT_MS / 1000}s", e)
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(webView: WebView, url: String): Page = suspendCancellableCoroutine { cont ->
        webView.settings.apply {
            // Only needed for evaluateJavascript; every external script is blocked below.
            javaScriptEnabled = true
            userAgentString = USER_AGENT
            loadsImagesAutomatically = false
            blockNetworkImage = true
        }
        webView.webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.isForMainFrame) return null
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val host = request.url.host ?: return true
                    return !(host == "instagram.com" || host.endsWith(".instagram.com"))
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && cont.isActive) {
                        cont.resumeWithException(IOException("Instagram page failed to load: ${error.description}"))
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    if (request.isForMainFrame && cont.isActive) {
                        cont.resumeWithException(IOException("Instagram returned HTTP ${errorResponse.statusCode}"))
                    }
                }

                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    if (!cont.isActive || finishedUrl == "about:blank") return
                    view.evaluateJavascript("[location.href, document.documentElement.outerHTML]") { result ->
                        if (!cont.isActive) return@evaluateJavascript
                        runCatching {
                            val array = Json.parseToJsonElement(result).jsonArray
                            Page(array[0].jsonPrimitive.content, array[1].jsonPrimitive.content)
                        }.fold(
                            onSuccess = { cont.resume(it) },
                            onFailure = {
                                cont.resumeWithException(IOException("Could not read the Instagram page", it))
                            }
                        )
                    }
                }
            }
        cont.invokeOnCancellation { webView.stopLoading() }
        webView.loadUrl(url)
    }

    companion object {
        private const val TIMEOUT_MS = 25_000L

        /** Desktop Chrome UA; the same UA plus a Chromium TLS stack is what Instagram serves media JSON to. */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }
}
