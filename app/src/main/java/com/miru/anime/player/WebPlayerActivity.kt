package com.miru.anime.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.miru.anime.net.Http
import com.miru.anime.source.AnimeSama

/**
 * Lecteur de secours : ouvre la page du lecteur (Sibnet, Vidmoly…) dans une vue web plein écran.
 * Les fenêtres de pub et les redirections vers d'autres sites sont bloquées.
 */
class WebPlayerActivity : ComponentActivity() {

    companion object {
        fun start(context: Context, url: String) {
            context.startActivity(Intent(context, WebPlayerActivity::class.java).putExtra("url", url))
        }
    }

    private var web: WebView? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    /** Partie principale du nom d'hôte : « vidmoly » pour vidmoly.to comme pour vidmoly.biz. */
    private fun mainName(host: String?): String =
        host.orEmpty().removePrefix("www.").split('.').let { if (it.size >= 2) it[it.size - 2] else it.firstOrNull().orEmpty() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url")
        if (url == null) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val playerName = mainName(Uri.parse(url).host)
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.userAgentString = Http.USER_AGENT
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    // Bloque les redirections publicitaires vers d'autres sites.
                    return mainName(request.url.host) != playerName && !request.isRedirect
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean =
                    false

                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    fullscreenView?.let { root.removeView(it) }
                    fullscreenView = view
                    fullscreenCallback = callback
                    root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }

                override fun onHideCustomView() {
                    fullscreenView?.let { root.removeView(it) }
                    fullscreenView = null
                    fullscreenCallback?.onCustomViewHidden()
                    fullscreenCallback = null
                }
            }
        }
        web = webView
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        webView.loadUrl(url, mapOf("Referer" to AnimeSama.base + "/"))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenView != null) {
                    webView.webChromeClient?.onHideCustomView()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onPause() {
        super.onPause()
        web?.onPause()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }
}
