package org.broeuschmeul.android.gps.usb.provider

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.os.Handler
import androidx.preference.PreferenceManager
import androidx.appcompat.app.AppCompatDelegate
import org.broeuschmeul.android.gps.usb.provider.util.LocaleHelper
import java.util.ArrayList

/**
 * アプリケーションクラスです。
 * 画面間でのログデータ共有や、動的ロケールの初期設定などを行います。
 */
class USBGpsApplication : Application() {

    private val LOG_SIZE = 100
    private val serviceDataListeners = ArrayList<ServiceDataListener>()
    
    var lastLocation: Location? = null
        private set
        
    private val logLines = ArrayList<String>()
    private var mainHandler: Handler? = null

    interface ServiceDataListener {
        fun onNewSentence(sentence: String)
        fun onLocationNotified(location: Location)
    }

    private fun setupDaynightMode() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val on = preferences.getBoolean(getString(R.string.pref_daynight_theme_key), false)

        AppCompatDelegate.setDefaultNightMode(
            if (on) AppCompatDelegate.MODE_NIGHT_AUTO else AppCompatDelegate.MODE_NIGHT_YES
        )
    }

    override fun attachBaseContext(base: Context) {
        // SharedPreferencesから言語設定を読み込み、適用したContextをセット
        super.attachBaseContext(LocaleHelper.onAttach(base))
    }

    override fun onCreate() {
        setupDaynightMode()
        Companion.locationAsked = false
        mainHandler = Handler(mainLooper)
        for (i in 0 until LOG_SIZE) {
            logLines.add("")
        }
        super.onCreate()
    }

    fun getLogLines(): Array<String> {
        return logLines.toTypedArray()
    }

    fun registerServiceDataListener(listener: ServiceDataListener) {
        synchronized(serviceDataListeners) {
            serviceDataListeners.add(listener)
        }
    }

    fun unregisterServiceDataListener(listener: ServiceDataListener) {
        synchronized(serviceDataListeners) {
            serviceDataListeners.remove(listener)
        }
    }

    fun notifyNewSentence(sentence: String) {
        if (logLines.size > LOG_SIZE) {
            logLines.removeAt(0)
        }
        logLines.add(sentence)

        synchronized(serviceDataListeners) {
            mainHandler?.post {
                for (dataListener in serviceDataListeners) {
                    dataListener.onNewSentence(sentence)
                }
            }
        }
    }

    fun notifyNewLocation(location: Location) {
        lastLocation = location
        synchronized(serviceDataListeners) {
            mainHandler?.post {
                for (dataListener in serviceDataListeners) {
                    dataListener.onLocationNotified(location)
                }
            }
        }
    }

    companion object {
        private var locationAsked = true

        @JvmStatic
        fun setLocationAsked() {
            locationAsked = true
        }

        @JvmStatic
        fun wasLocationAsked(): Boolean {
            return locationAsked
        }

        @JvmStatic
        fun setLocationNotAsked() {
            locationAsked = false
        }
    }
}
