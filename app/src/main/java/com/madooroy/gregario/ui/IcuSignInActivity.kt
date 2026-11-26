package com.madooroy.gregario.ui

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.madooroy.gregario.Gregario
import com.madooroy.gregario.icu.IcuOAuth
import timber.log.Timber

/**
 * "Connect intervals.icu": intervals.icu's own sign-in and approval page, shown inside Gregario (the Peloton tablet
 * has no browser). The redirect back to [IcuOAuth.REDIRECT] never loads: it is caught here and exchanged for a token.
 */
class IcuSignInActivity : Activity() {
    private val oauth = IcuOAuth()
    private lateinit var pending: IcuOAuth.Pending
    private lateinit var web: WebView
    private lateinit var status: TextView
    private var riderId = ""
    private var handled = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val gregario = Gregario.get(application)
        riderId = saved?.getString("rider") ?: gregario.settings.rider.value
        pending = saved?.let { b -> b.getString("verifier")?.let { v -> IcuOAuth.Pending(v, b.getString("state").orEmpty()) } } ?: oauth.start()

        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; setPadding(24, 16, 24, 16)
            text = "Sign in to intervals.icu and tap Approve. Gregario never sees your password."
        }
        val cancel = Button(this).apply { text = "Cancel"; setOnClickListener { finish() } }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.parseColor("#1B2430"))
            addView(status, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); addView(cancel)
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true      // intervals.icu's pages need it
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, url: String) = intercept(url)
                @TargetApi(24) override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = intercept(request.url.toString())
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { if (IcuOAuth.isRedirect(url)) { view.stopLoading(); intercept(url) } }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })
        web.loadUrl(oauth.authorizeUrl(pending))
    }

    /** true = the redirect back from intervals.icu (handled here, never loaded). */
    private fun intercept(url: String): Boolean {
        if (!IcuOAuth.isRedirect(url)) return false
        if (handled) return true
        handled = true
        web.stopLoading()
        status.text = "Connecting…"
        val gregario = Gregario.get(application)
        kotlin.concurrent.thread(name = "gregario-icu-signin") {
            val result = runCatching { oauth.finish(url, pending) }
            runOnUiThread {
                result.onSuccess { grant ->
                    Timber.i("intervals.icu connected (scope ${grant.scope})")
                    gregario.icuSignedIn(grant, riderId)
                    finish()
                }.onFailure { e ->
                    Timber.w("intervals.icu sign-in failed: ${e.message}")
                    status.text = (e.message ?: "Sign-in failed") + " · Cancel, then tap Connect again"
                }
            }
        }
        return true
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("rider", riderId); out.putString("verifier", pending.verifier); out.putString("state", pending.state)
    }

    @Deprecated("Back steps back through intervals.icu's pages first")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
