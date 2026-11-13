package com.miru.anime.data

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("miru", Context.MODE_PRIVATE)
    }

    /** Adresse actuelle d'Anime-Sama (le site change souvent de domaine). */
    var domain: String
        get() = sp.getString("domain", null) ?: "anime-sama.to"
        set(value) { sp.edit().putString("domain", value).apply() }

    /** true si l'adresse a été tapée à la main : on ne la remplace pas automatiquement. */
    var domainManual: Boolean
        get() = sp.getBoolean("domain_manual", false)
        set(value) { sp.edit().putBoolean("domain_manual", value).apply() }

    var lastDomainCheck: Long
        get() = sp.getLong("last_domain_check", 0L)
        set(value) { sp.edit().putLong("last_domain_check", value).apply() }

    /** Langue choisie par défaut : « vostfr » ou « vf ». */
    var preferredLang: String
        get() = sp.getString("lang", null) ?: "vostfr"
        set(value) { sp.edit().putString("lang", value).apply() }

    /** Lecteur essayé en premier (nom d'hébergeur, ex. « Sibnet »), ou « Auto ». */
    var preferredHost: String
        get() = sp.getString("host", null) ?: "Auto"
        set(value) { sp.edit().putString("host", value).apply() }

    var autoNext: Boolean
        get() = sp.getBoolean("auto_next", true)
        set(value) { sp.edit().putBoolean("auto_next", value).apply() }

    /** Durée sautée par le bouton « Passer l'intro », en secondes. */
    var skipSeconds: Int
        get() = sp.getInt("skip_seconds", 85)
        set(value) { sp.edit().putInt("skip_seconds", value).apply() }

    var lastAppUpdateCheck: Long
        get() = sp.getLong("last_app_check", 0L)
        set(value) { sp.edit().putLong("last_app_check", value).apply() }
}
