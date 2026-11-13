package com.miru.anime.player

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.miru.anime.data.HistoryEntry
import com.miru.anime.data.Library
import com.miru.anime.data.Prefs
import com.miru.anime.data.episodeKey
import com.miru.anime.model.Episode
import com.miru.anime.model.Langs
import com.miru.anime.net.Http
import com.miru.anime.source.AnimeSama
import com.miru.anime.ui.MiruIcons
import com.miru.anime.ui.MiruTheme
import com.miru.anime.ui.errorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Lecteur : ouvre la page de la saison sur Anime-Sama (avec son propre lecteur vidéo),
 * choisit le bon épisode et n'affiche que la vidéo, en plein écran.
 */
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val SLUG = "slug"
        private const val TITLE = "title"
        private const val COVER = "cover"
        private const val STEM = "stem"
        private const val SEASON = "season"
        private const val LANG = "lang"
        private const val INDEX = "index"

        /** Temps passé sur un épisode à partir duquel on le considère comme vu. */
        private const val WATCHED_AFTER_MS = 15 * 60_000L

        fun start(
            context: Context,
            slug: String,
            title: String,
            cover: String,
            stem: String,
            seasonName: String,
            lang: String,
            index: Int,
        ) {
            val intent = Intent(context, PlayerActivity::class.java)
                .putExtra(SLUG, slug)
                .putExtra(TITLE, title)
                .putExtra(COVER, cover)
                .putExtra(STEM, stem)
                .putExtra(SEASON, seasonName)
                .putExtra(LANG, lang)
                .putExtra(INDEX, index)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        /** N'affiche que le lecteur de la page, sans le reste du site ni les pubs. */
        private const val CSS =
            "html,body{background:#000!important;overflow:hidden!important}" +
                "header,nav,footer,.pub-btag,.ad-container,.ct-cinema-fermer{display:none!important}" +
                ".ct-lecteur{position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;" +
                "width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;" +
                "margin:0!important;padding:0!important;border-radius:0!important;z-index:2147483646!important;" +
                "background:#000!important;transform:none!important}" +
                ".ct-lecteur #playerDF{position:absolute!important;left:0!important;top:0!important;" +
                "width:100%!important;height:100%!important;border:0!important}"
    }

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var slug = ""
    private var title by mutableStateOf("")
    private var cover = ""
    private var stem by mutableStateOf("")
    private var seasonName by mutableStateOf("")
    private var lang by mutableStateOf("vostfr")
    private var episodes by mutableStateOf<List<Episode>>(emptyList())
    private var index by mutableIntStateOf(0)

    private var status by mutableStateOf<String?>("Chargement…")
    private var failure by mutableStateOf<String?>(null)
    private var lecteurs by mutableStateOf<List<String>>(emptyList())
    private var lecteur by mutableIntStateOf(0)
    private var inPip by mutableStateOf(false)
    private var barVisible by mutableStateOf(true)
    private var barTick by mutableIntStateOf(0)

    /** Page de saison actuellement chargée dans la vue web. */
    private var loadedUrl: String? = null
    private var selectJob: Job? = null

    /** Temps passé sur l'épisode en cours (pour le marquer vu). */
    private var watchedMs = 0L
    private var resumedAt = 0L

    private val currentEpisode: Episode? get() = episodes.getOrNull(index)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        goFullscreen()
        buildWebView()
        setContent { MiruTheme { PlayerScreen() } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenView != null) hideCustomView() else finish()
            }
        })
        openEpisode(index)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        finishEpisode()
        readIntent(intent)
        openEpisode(index)
    }

    private fun readIntent(i: Intent) {
        slug = i.getStringExtra(SLUG).orEmpty()
        title = i.getStringExtra(TITLE).orEmpty()
        cover = i.getStringExtra(COVER).orEmpty()
        stem = i.getStringExtra(STEM).orEmpty()
        seasonName = i.getStringExtra(SEASON).orEmpty()
        lang = i.getStringExtra(LANG) ?: "vostfr"
        index = i.getIntExtra(INDEX, 0)
        episodes = emptyList()
    }

    private fun goFullscreen() {
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
    }

    // ------------------------------------------------------------------ Vue web

    /** Partie principale d'un nom d'hôte : « anime-sama » pour anime-sama.to. */
    private fun mainName(host: String?): String =
        host.orEmpty().removePrefix("www.").split('.').let { if (it.size >= 2) it[it.size - 2] else it.firstOrNull().orEmpty() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView() {
        root = FrameLayout(this).apply { setBackgroundColor(AColor.BLACK) }
        web = WebView(this).apply {
            setBackgroundColor(AColor.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.userAgentString = Http.USER_AGENT
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    // Bloque les redirections publicitaires : on reste sur Anime-Sama.
                    return mainName(request.url.host) != mainName(Uri.parse(AnimeSama.base).host) && !request.isRedirect
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    view.evaluateJavascript(injectCss(), null)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript(injectCss(), null)
                    selectEpisodeOnPage()
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

                override fun onHideCustomView() = hideCustomView()
            }
        }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun hideCustomView() {
        fullscreenView?.let { root.removeView(it) }
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    private fun injectCss(): String =
        "(function(){if(document.getElementById('miru-css'))return;var s=document.createElement('style');" +
            "s.id='miru-css';s.textContent=${JSONObject.quote(CSS)};(document.head||document.documentElement).appendChild(s);})()"

    // ------------------------------------------------------------------ Épisodes

    private fun key(i: Int) = episodeKey(slug, stem, i)

    private fun openEpisode(i: Int) {
        failure = null
        status = "Chargement…"
        index = i
        watchedMs = 0L
        resumedAt = SystemClock.elapsedRealtime()
        showBar()
        lifecycleScope.launch {
            try {
                if (episodes.isEmpty()) {
                    episodes = AnimeSama.episodes(slug, stem, lang)
                        ?: AnimeSama.episodes(slug, stem, "vostfr")?.also { lang = "vostfr" }
                        ?: emptyList()
                }
                val ep = currentEpisode
                if (ep == null) {
                    status = null
                    failure = "Épisode introuvable."
                    return@launch
                }
                Library.record(
                    HistoryEntry(slug, title, cover, stem, seasonName, lang, ep.index, ep.label, System.currentTimeMillis()),
                )
                val url = AnimeSama.seasonUrl(slug, stem, lang)
                if (url != loadedUrl) {
                    loadedUrl = url
                    lecteurs = emptyList()
                    web.loadUrl(url, mapOf("Referer" to "${AnimeSama.base}/catalogue/$slug/"))
                } else {
                    selectEpisodeOnPage()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                status = null
                failure = errorText(e)
            }
        }
    }

    /** Choisit l'épisode (et le lecteur préféré) dans les menus de la page. */
    private fun selectEpisodeOnPage() {
        selectJob?.cancel()
        val target = index
        val pref = JSONObject.quote(Prefs.preferredHost)
        val script = """
            (function(){
              var s=document.getElementById('selectEpisodes');
              if(!s||s.options.length<=$target) return null;
              if(s.selectedIndex!==$target){ s.selectedIndex=$target; s.dispatchEvent(new Event('change',{bubbles:true})); }
              var l=document.getElementById('selectLecteurs'), names=[], cur=0;
              if(l){
                var pref=$pref;
                if(pref!=='Auto' && !window.__miruPref$target){
                  window.__miruPref$target=true;
                  for(var i=0;i<l.options.length;i++){ if(l.options[i].text.toLowerCase().indexOf(pref.toLowerCase())>=0){ if(l.selectedIndex!==i){ l.selectedIndex=i; l.dispatchEvent(new Event('change',{bubbles:true})); } break; } }
                }
                for(var j=0;j<l.options.length;j++) names.push(l.options[j].text);
                cur=l.selectedIndex;
              }
              return {lecteurs:names, lecteur:cur};
            })()
        """.trimIndent()
        selectJob = lifecycleScope.launch {
            repeat(40) {
                val result = evaluate(script)
                if (result != null && result != "null") {
                    runCatching {
                        val o = JSONObject(result)
                        val arr = o.optJSONArray("lecteurs")
                        lecteurs = (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }
                        lecteur = o.optInt("lecteur")
                    }
                    status = null
                    return@launch
                }
                delay(400)
            }
            status = null
            failure = "Le lecteur de la page n'a pas été trouvé.\nLe site a peut-être changé."
        }
    }

    private suspend fun evaluate(script: String): String? = kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
        web.evaluateJavascript(script) { if (cont.isActive) cont.resumeWith(Result.success(it)) }
    }

    private fun chooseLecteur(i: Int) {
        web.evaluateJavascript(
            "(function(){var l=document.getElementById('selectLecteurs');if(!l)return;l.selectedIndex=$i;" +
                "l.dispatchEvent(new Event('change',{bubbles:true}));})()",
            null,
        )
        lecteur = i
    }

    /** Fin d'un épisode : on le marque vu si on l'a regardé assez longtemps. */
    private fun finishEpisode(forceWatched: Boolean = false) {
        pauseTimer()
        if (episodes.isNotEmpty() && (forceWatched || watchedMs >= WATCHED_AFTER_MS)) {
            Library.setWatched(listOf(key(index)), true)
        }
        watchedMs = 0L
    }

    private fun pauseTimer() {
        if (resumedAt > 0) watchedMs += SystemClock.elapsedRealtime() - resumedAt
        resumedAt = 0L
    }

    private fun previous() {
        if (index > 0) {
            finishEpisode()
            openEpisode(index - 1)
        }
    }

    /** Épisode suivant, ou premier épisode de la saison suivante. */
    private fun next() {
        if (index + 1 < episodes.size) {
            finishEpisode(forceWatched = true)
            openEpisode(index + 1)
            return
        }
        lifecycleScope.launch {
            try {
                AnimeSama.details(slug)
                val s = AnimeSama.nextSeason(slug, stem)
                if (s == null) {
                    toast("C'était le dernier épisode.")
                    return@launch
                }
                var newLang = lang
                val eps = AnimeSama.episodes(slug, s.stem, lang)
                    ?: AnimeSama.episodes(slug, s.stem, "vostfr")?.also { newLang = "vostfr" }
                if (eps.isNullOrEmpty()) {
                    toast("${s.name} n'a pas encore d'épisode.")
                    return@launch
                }
                finishEpisode(forceWatched = true)
                stem = s.stem
                seasonName = s.name
                lang = newLang
                episodes = eps
                openEpisode(0)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                toast(errorText(e))
            }
        }
    }

    private fun showBar() {
        barVisible = true
        barTick++
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ Cycle de vie et image dans l'image

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (status == null && failure == null) {
            runCatching {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        if (resumedAt == 0L) resumedAt = SystemClock.elapsedRealtime()
    }

    override fun onStop() {
        super.onStop()
        if (!inPip) {
            pauseTimer()
            web.onPause()
        }
    }

    override fun onDestroy() {
        finishEpisode()
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Interface

    @Composable
    private fun PlayerScreen() {
        var showLecteurs by remember { mutableStateOf(false) }
        var showEpisodes by remember { mutableStateOf(false) }
        val ep = currentEpisode
        val busy = status != null || failure != null

        // La barre du haut se cache toute seule au bout de quelques secondes.
        LaunchedEffect(barTick, busy) {
            if (!busy) {
                delay(4_000)
                barVisible = false
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { root }, modifier = Modifier.fillMaxSize())

            if (inPip) return@Box

            if (barVisible || busy) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(seasonName, ep?.label, Langs.label(lang)).joinToString(" · "),
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                    if (lecteurs.size > 1) {
                        TextButton(onClick = { showLecteurs = true; showBar() }) {
                            Text(lecteurs.getOrNull(lecteur)?.substringAfter("·")?.trim() ?: "Lecteurs", color = Color.White)
                        }
                    }
                    IconButton(onClick = { showEpisodes = true; showBar() }) {
                        Icon(Icons.AutoMirrored.Filled.List, "Épisodes", tint = Color.White)
                    }
                    IconButton(onClick = { previous() }, enabled = index > 0) {
                        Icon(MiruIcons.SkipPrevious, "Épisode précédent", tint = if (index > 0) Color.White else Color.Gray)
                    }
                    IconButton(onClick = { next() }) {
                        Icon(MiruIcons.SkipNext, "Épisode suivant", tint = Color.White)
                    }
                }
            } else {
                // Bande invisible en haut : un appui fait réapparaître la barre.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clickable { showBar() },
                )
            }

            val st = status
            val fail = failure
            if (fail != null) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.8f))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(fail, color = Color.White, textAlign = TextAlign.Center)
                    Button(onClick = { loadedUrl = null; openEpisode(index) }) { Text("Réessayer") }
                }
            } else if (st != null) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(st, color = Color.White, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }

        if (showLecteurs) {
            AlertDialog(
                onDismissRequest = { showLecteurs = false },
                title = { Text("Lecteurs") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        itemsIndexed(lecteurs) { i, name ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showLecteurs = false
                                        chooseLecteur(i)
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(name, Modifier.weight(1f))
                                if (i == lecteur) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showLecteurs = false }) { Text("Fermer") } },
            )
        }

        if (showEpisodes) {
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (index - 2).coerceAtLeast(0))
            AlertDialog(
                onDismissRequest = { showEpisodes = false },
                title = { Text(seasonName) },
                text = {
                    LazyColumn(Modifier.heightIn(max = 360.dp), state = listState) {
                        itemsIndexed(episodes) { i, e ->
                            val seen = Library.progress.value[key(i)]?.watched == true
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showEpisodes = false
                                        if (i != index) {
                                            finishEpisode()
                                            openEpisode(i)
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    e.label,
                                    Modifier.weight(1f),
                                    color = when {
                                        i == index -> MaterialTheme.colorScheme.primary
                                        seen -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    fontWeight = if (i == index) FontWeight.Bold else FontWeight.Normal,
                                )
                                if (seen) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showEpisodes = false }) { Text("Fermer") } },
            )
        }
    }
}
