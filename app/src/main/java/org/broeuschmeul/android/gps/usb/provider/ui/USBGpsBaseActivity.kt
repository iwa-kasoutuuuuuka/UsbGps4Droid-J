package org.broeuschmeul.android.gps.usb.provider.ui

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.preference.PreferenceManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import android.view.MenuItem
import org.broeuschmeul.android.gps.usb.provider.R
import org.broeuschmeul.android.gps.usb.provider.USBGpsApplication
import org.broeuschmeul.android.gps.usb.provider.driver.USBGpsProviderService
import org.broeuschmeul.android.gps.usb.provider.util.LocaleHelper

/**
 * 本アプリにおけるすべてのActivityの基底クラスです。
 * 実行時パーミッションの制御、多言語設定の適用、テーマの切り替えなどを一元管理します。
 */
abstract class USBGpsBaseActivity : AppCompatActivity(),
    USBGpsSettingsFragment.PreferenceScreenListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var notificationManager: NotificationManager
    private lateinit var activityManager: ActivityManager

    private var shouldInitialise = true
    private var resSettingsHolder: Int = 0
    private var tryingToStart = false
    private var homeAsUp = false
    private var lastDaynightSetting = false
    private var lastLanguageSetting = "default"

    override fun attachBaseContext(newBase: Context) {
        // 多言語ロケールを反映したContextを適用します
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        if (savedInstanceState != null) {
            shouldInitialise = false
        }

        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !USBGpsApplication.wasLocationAsked()
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                USBGpsApplication.setLocationAsked()
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    LOCATION_REQUEST
                )
            }
        }

        lastDaynightSetting = getDaynightSetting()
        lastLanguageSetting = getLanguageSetting()
    }

    private fun getDaynightSetting(): Boolean {
        return sharedPreferences.getBoolean(getString(R.string.pref_daynight_theme_key), false)
    }

    private fun getLanguageSetting(): String {
        return sharedPreferences.getString(getString(R.string.pref_language_key), "default") ?: "default"
    }

    /**
     * 設定の変更に基づいて、必要があればActivityを再作成（recreate）します。
     */
    private fun handleSettingsChange() {
        val newDaynightSetting = getDaynightSetting()
        val newLanguageSetting = getLanguageSetting()
        if (lastDaynightSetting != newDaynightSetting || lastLanguageSetting != newLanguageSetting) {
            recreate()
        }
    }

    override fun onResume() {
        handleSettingsChange()
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        // サービスが本当に動作しているかチェックします
        if (!isServiceRunning()) {
            sharedPreferences
                .edit()
                .putBoolean(USBGpsProviderService.PREF_START_GPS_PROVIDER, false)
                .apply()
        }
        super.onResume()
    }

    override fun onPause() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    /**
     * 指定のホルダーIDに設定用フラグメントを表示します。
     */
    fun showSettingsFragment(whereId: Int, homeAsUp: Boolean) {
        resSettingsHolder = whereId
        if (shouldInitialise) {
            supportFragmentManager.beginTransaction()
                .add(whereId, USBGpsSettingsFragment())
                .commit()
        }
        this.homeAsUp = homeAsUp
    }

    private fun clearStopNotification() {
        notificationManager.cancel(R.string.service_closed_because_connection_problem_notification_title)
    }

    private fun showStopDialog() {
        val reason = sharedPreferences.getInt(getString(R.string.pref_disable_reason_key), 0)

        if (reason > 0) {
            if (reason == R.string.msg_mock_location_disabled) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.service_closed_because_connection_problem_notification_title)
                    .setMessage(
                        getString(
                            R.string.service_closed_because_connection_problem_notification,
                            getString(R.string.msg_mock_location_disabled)
                        )
                    )
                    .setPositiveButton(R.string.button_open_mock_location_settings) { _, _ ->
                        clearStopNotification()
                        try {
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (e: ActivityNotFoundException) {
                            AlertDialog.Builder(this@USBGpsBaseActivity)
                                .setMessage(R.string.warning_no_developer_options)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                    .show()
            } else {
                if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.service_closed_because_connection_problem_notification_title)
                        .setMessage(
                            getString(
                                R.string.service_closed_because_connection_problem_notification,
                                getString(reason)
                            )
                        )
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            clearStopNotification()
                        }
                        .show()
                }
            }
        }
    }

    private fun hasPermission(perm: String): Boolean {
        return PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(this, perm)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_REQUEST) {
            if (hasPermission(permissions[0])) {
                if (tryingToStart) {
                    tryingToStart = false
                    val serviceIntent = Intent(this, USBGpsProviderService::class.java).apply {
                        action = USBGpsProviderService.ACTION_START_GPS_PROVIDER
                    }
                    startService(serviceIntent)
                }
            } else {
                tryingToStart = false
                sharedPreferences.edit()
                    .putBoolean(USBGpsProviderService.PREF_START_GPS_PROVIDER, false)
                    .apply()

                AlertDialog.Builder(this)
                    .setMessage(R.string.error_location_permission_required)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        } else if (requestCode == STORAGE_REQUEST) {
            if (hasPermission(permissions[0])) {
                val serviceIntent = Intent(this, USBGpsProviderService::class.java).apply {
                    action = USBGpsProviderService.ACTION_START_TRACK_RECORDING
                }
                startService(serviceIntent)
            } else {
                sharedPreferences.edit()
                    .putBoolean(USBGpsProviderService.PREF_TRACK_RECORDING, false)
                    .apply()

                AlertDialog.Builder(this)
                    .setMessage(R.string.error_storage_permission_required)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    fun isServiceRunning(): Boolean {
        val services = activityManager.getRunningServices(Int.MAX_VALUE)
        for (service in services) {
            if (USBGpsProviderService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (prefs == null || key == null) return
        when (key) {
            getString(R.string.pref_language_key) -> {
                val newLang = prefs.getString(key, "default") ?: "default"
                LocaleHelper.setLocale(this, newLang)
                recreate()
            }
            USBGpsProviderService.PREF_START_GPS_PROVIDER -> {
                val valBoolean = prefs.getBoolean(key, false)
                if (valBoolean) {
                    if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        if (!isServiceRunning()) {
                            val serviceIntent = Intent(this, USBGpsProviderService::class.java).apply {
                                action = USBGpsProviderService.ACTION_START_GPS_PROVIDER
                            }
                            startService(serviceIntent)
                        }
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            tryingToStart = true
                            requestPermissions(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                                LOCATION_REQUEST
                            )
                        }
                    }
                } else {
                    showStopDialog()
                    if (isServiceRunning()) {
                        val serviceIntent = Intent(this, USBGpsProviderService::class.java).apply {
                            action = USBGpsProviderService.ACTION_STOP_GPS_PROVIDER
                        }
                        startService(serviceIntent)
                    }
                }
            }
            USBGpsProviderService.PREF_TRACK_RECORDING -> {
                val valBoolean = prefs.getBoolean(key, false)
                if (valBoolean) {
                    if (!hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            requestPermissions(
                                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                                STORAGE_REQUEST
                            )
                        }
                    } else {
                        if (prefs.getBoolean(USBGpsProviderService.PREF_START_GPS_PROVIDER, false)) {
                            val serviceIntent = Intent(this, USBGpsProviderService::class.java).apply {
                                action = USBGpsProviderService.ACTION_START_TRACK_RECORDING
                            }
                            startService(serviceIntent)
                        }
                    }
                } else {
                    if (prefs.getBoolean(USBGpsProviderService.PREF_START_GPS_PROVIDER, false)) {
                        val serviceIntent = Intent(this, USBGpsProviderService::class.java).apply {
                            action = USBGpsProviderService.ACTION_STOP_TRACK_RECORDING
                        }
                        startService(serviceIntent)
                    }
                }
            }
            USBGpsProviderService.PREF_SIRF_GPS -> {
                if (prefs.getBoolean(USBGpsProviderService.PREF_START_GPS_PROVIDER, false)) {
                    if (prefs.getBoolean(USBGpsProviderService.PREF_SIRF_GPS, false)) {
                        val configIntent = Intent(this, USBGpsProviderService::class.java).apply {
                            action = USBGpsProviderService.ACTION_CONFIGURE_SIRF_GPS
                        }
                        startService(configIntent)
                    }
                }
            }
        }
    }

    override fun onNestedScreenClicked(preferenceFragment: PreferenceFragmentCompat) {
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportFragmentManager.beginTransaction()
            .replace(resSettingsHolder, preferenceFragment, TAG_NESTED)
            .addToBackStack(TAG_NESTED)
            .commit()
    }

    override fun onBackPressed() {
        if (fragmentManager.backStackEntryCount == 0) {
            super.onBackPressed()
        } else {
            supportActionBar?.setDisplayHomeAsUpEnabled(homeAsUp)
            fragmentManager.popBackStack()
        }
    }

    override fun onOptionsItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(menuItem)
    }

    override fun onDestroy() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG_NESTED = "NESTED_PREFERENCE_SCREEN"
        private const val LOCATION_REQUEST = 238472383
        private const val STORAGE_REQUEST = 8972842
    }
}
