package com.manacinema.app.localization

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocalizationManager {

    private var currentLanguage: Language = Language.ENGLISH

    fun getStringResources(language: Language): AppStrings = when (language) {
        Language.ENGLISH -> EnglishStrings
        Language.HINDI -> HindiStrings
        Language.BENGALI -> BengaliStrings
        Language.TELUGU -> TeluguStrings
        Language.TAMIL -> TamilStrings
    }

    fun getCurrentStrings(): AppStrings = getStringResources(currentLanguage)

    fun setLanguage(language: Language) {
        currentLanguage = language
    }

    fun getCurrentLanguage(): Language = currentLanguage

    fun applyLanguage(context: Context, language: Language): Context {
        currentLanguage = language
        val locale = language.locale
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
