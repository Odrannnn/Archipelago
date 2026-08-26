package eu.odran.archipelago

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import kotlin.concurrent.thread

/** Displays archipelago.gg with the companion's private website session. */
class AuthenticatedWebActivity : CompanionActivity() {
    private lateinit var webView: WebView
    private lateinit var titleView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        val target = intent.getStringExtra(EXTRA_URL)?.let(Uri::parse)
        if (target == null || target.scheme != "https" ||
            !target.host.equals(Uri.parse(ArchipelagoWebHostClient.BASE_URL).host, ignoreCase = true)
        ) {
            Toast.makeText(this, "Invalid authenticated website address", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        titleView = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE) ?: "archipelago.gg"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(32, 39, 48))
            setPadding(24, 18, 24, 18)
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // archipelago.gg's shared page wrapper has no mobile viewport tag.
            // Match a phone browser's wide virtual viewport and scale it to fit.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (!title.isNullOrBlank()) titleView.text = title
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "https" && uri.host.equals(target.host, ignoreCase = true)) return false
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    return true
                }
            }
        }
        WebView.setWebContentsDebuggingEnabled(false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        SystemBarInsets.apply(window, root)
        setContentView(root)

        titleView.text = "Authenticating with archipelago.gg…"
        thread(name = "website-browser-session") {
            runCatching { ArchipelagoWebHostClient(this).authenticatedBrowserCookie() }
                .onSuccess { cookie -> runOnUiThread { installCookieAndOpen(cookie, target) } }
                .onFailure { error -> runOnUiThread {
                    Toast.makeText(
                        this,
                        "Could not open authenticated website: ${error.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                } }
        }
    }

    private fun installCookieAndOpen(cookie: String, target: Uri) {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, false)
        cookies.setCookie(
            ArchipelagoWebHostClient.BASE_URL,
            "session=$cookie; Path=/; Secure; HttpOnly; SameSite=Lax",
        ) { installed ->
            runOnUiThread {
                if (!installed) {
                    Toast.makeText(this, "Could not transfer the website session", Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                cookies.flush()
                webView.loadUrl(target.toString())
            }
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "authenticated_url"
        private const val EXTRA_TITLE = "authenticated_title"

        fun intent(context: Context, url: String, title: String): Intent =
            Intent(context, AuthenticatedWebActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
    }
}
