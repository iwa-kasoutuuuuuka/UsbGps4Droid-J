package org.broeuschmeul.android.gps.usb.provider.util

import android.annotation.TargetApi
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.preference.PreferenceManager
import java.util.Locale

/**
 * アプリの表示言語（ロケール）を動的に切り替えるためのヘルパークラスです。
 */
object LocaleHelper {

    private const val SELECTED_LANGUAGE = "appLanguage"

    /**
     * アプリ起動時やコンテキスト初期化時にロケールを適用したコンテキストを取得します。
     */
    fun onAttach(context: Context): Context {
        val lang = getPersistedData(context, "default")
        return setLocale(context, lang)
    }

    /**
     * 指定された言語コードのロケールを設定します。
     * @param language "default"（システム標準）、"en"（英語）、または "ja"（日本語）
     */
    fun setLocale(context: Context, language: String): Context {
        persist(context, language)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            updateResources(context, language)
        } else {
            updateResourcesLegacy(context, language)
        }
    }

    private fun getPersistedData(context: Context, defaultLanguage: String): String {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return preferences.getString(SELECTED_LANGUAGE, defaultLanguage) ?: defaultLanguage
    }

    private fun persist(context: Context, language: String) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putString(SELECTED_LANGUAGE, language).apply()
    }

    @TargetApi(Build.VERSION_CODES.N)
    private fun updateResources(context: Context, language: String): Context {
        val locale = getLocaleFromCode(language)
        Locale.setDefault(locale)

        val configuration = context.resources.configuration
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)

        return context.createConfigurationContext(configuration)
    }

    @Suppress("DEPRECATION")
    private fun updateResourcesLegacy(context: Context, language: String): Context {
        val locale = getLocaleFromCode(language)
        Locale.setDefault(locale)

        val resources = context.resources
        val configuration = resources.configuration
        configuration.locale = locale
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            configuration.setLayoutDirection(locale)
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)

        return context
    }

    private fun getLocaleFromCode(language: String): Locale {
        if (language == "default") {
            return Resources.getSystem().configuration.locale ?: Locale.getDefault()
        }
        return Locale(language)
    }
}
