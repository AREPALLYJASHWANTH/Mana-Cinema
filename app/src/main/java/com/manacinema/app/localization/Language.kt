package com.manacinema.app.localization

import java.util.Locale

enum class Language(
    val code: String,
    val displayName: String,
    val locale: Locale
) {
    ENGLISH("en", "English", Locale("en")),
    HINDI("hi", "Hindi", Locale("hi")),
    BENGALI("bn", "Bengali", Locale("bn")),
    TELUGU("te", "Telugu", Locale("te")),
    TAMIL("ta", "Tamil", Locale("ta"));

    companion object {
        fun fromCode(code: String): Language =
            entries.firstOrNull { it.code == code } ?: ENGLISH

        fun fromLocale(locale: Locale): Language =
            entries.firstOrNull { it.code == locale.language } ?: ENGLISH
    }
}
