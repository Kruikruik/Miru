package com.miru.anime.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int) : IOException("Erreur HTTP $code")

data class Page(val body: String, val finalUrl: String)

object Http {
    /** Navigateur Android classique : certains hébergeurs refusent les clients inconnus. */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request()
                val builder = req.newBuilder()
                if (req.header("User-Agent") == null) builder.header("User-Agent", USER_AGENT)
                if (req.header("Accept-Language") == null) builder.header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.6")
                chain.proceed(builder.build())
            }
            .build()
    }

    /** Client court pour tester rapidement si une adresse répond. */
    val quickClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    suspend fun page(
        url: String,
        headers: Map<String, String> = emptyMap(),
        http: OkHttpClient = client,
    ): Page = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpException(resp.code)
            Page(body, resp.request.url.toString())
        }
    }

    suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String =
        page(url, headers).body

    suspend fun getJson(url: String): JSONObject {
        val text = getString(url, mapOf("Accept" to "application/vnd.github+json"))
        return withContext(Dispatchers.Default) { JSONObject(text) }
    }
}
