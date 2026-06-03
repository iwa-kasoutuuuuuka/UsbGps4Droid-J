package org.broeuschmeul.android.gps.usb.provider.ui

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.Preference.OnPreferenceChangeListener
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.PreferenceManager
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.View
import android.widget.TextView
import org.broeuschmeul.android.gps.usb.provider.BuildConfig
import org.broeuschmeul.android.gps.usb.provider.R
import org.broeuschmeul.android.gps.usb.provider.driver.USBGpsProviderService
import org.broeuschmeul.android.gps.usb.provider.util.SuperuserManager
import java.util.HashMap

/**
 * アプリの各種設定を管理する PreferenceFragmentClass です。
 * デバイス選択、通信速度設定、自動起動、NMEAログ出力の有無、言語切り替えなどをハンドリングします。
 */
class USBGpsSettingsFragment : PreferenceFragmentCompat(),
    OnPreferenceChangeListener, OnSharedPreferenceChangeListener {

    // 画面アクティブ時に新しく接続されたUSBデバイスを検知するチェック用タスク
    private val usbCheckRunnable = Runnable {
        var lastNum = usbManager.deviceList.values.size

        while (!Thread.interrupted()) {
            val newNum = usbManager.deviceList.values.size

            if (lastNum != newNum) {
                mainHandler.post {
                    updateDevicePreferenceSummary()
                    updateDevicesList()
                }
                lastNum = newNum
            }

            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                break
            }
        }
        log("USB Device Check thread ending")
    }

    private var usbCheckThread: Thread? = null

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var devicePreference: ListPreference
    private lateinit var deviceSpeedPreference: ListPreference

    private lateinit var usbManager: UsbManager
    private lateinit var activityManager: ActivityManager

    private lateinit var mainHandler: Handler

    private var callback: PreferenceScreenListener? = null

    // ネストされた設定画面を開くためのコールバックインタフェース
    interface PreferenceScreenListener {
        fun onNestedScreenClicked(preferenceFragment: PreferenceFragmentCompat)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.main_prefs)

        devicePreference = findPreference<ListPreference>(USBGpsProviderService.PREF_GPS_DEVICE)!!
        deviceSpeedPreference = findPreference<ListPreference>(USBGpsProviderService.PREF_GPS_DEVICE_SPEED)!!
        devicePreference.onPreferenceChangeListener = this

        val activity = activity ?: return
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        mainHandler = Handler(activity.mainLooper)

        setupNestedPreferences()
    }

    private fun onDaynightModeChanged(on: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (on) AppCompatDelegate.MODE_NIGHT_AUTO else AppCompatDelegate.MODE_NIGHT_YES
        )
        activity?.recreate()
    }

    private fun setupNestedPreferences() {
        findPreference<Preference>(USBGpsProviderService.PREF_ABOUT)?.setOnPreferenceClickListener {
            displayAboutDialog()
            true
        }

        findPreference<Preference>(getString(R.string.pref_gps_location_provider_key))
            ?.setOnPreferenceClickListener {
                callback?.onNestedScreenClicked(ProviderPreferences())
                false
            }

        findPreference<Preference>(getString(R.string.pref_sirf_screen_key))
            ?.setOnPreferenceClickListener {
                callback?.onNestedScreenClicked(SirfPreferences())
                false
            }

        findPreference<Preference>(getString(R.string.pref_recording_screen_key))
            ?.setOnPreferenceClickListener {
                callback?.onNestedScreenClicked(RecordingPreferences())
                false
            }

        findPreference<Preference>(getString(R.string.pref_daynight_theme_key))
            ?.setOnPreferenceChangeListener { _, newValue ->
                onDaynightModeChanged(newValue as Boolean)
                true
            }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is PreferenceScreenListener) {
            callback = context
        } else {
            throw IllegalStateException("Owner must implement PreferenceScreenListener interface")
        }
    }

    @Suppress("DEPRECATION")
    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        if (activity is PreferenceScreenListener) {
            callback = activity
        } else {
            throw IllegalStateException("Owner must implement PreferenceScreenListener interface")
        }
    }

    override fun onResume() {
        usbCheckThread = Thread(usbCheckRunnable)
        usbCheckThread?.start()

        val timePreference = findPreference<CheckBoxPreference>(USBGpsProviderService.PREF_SET_TIME)

        if (timePreference != null && !SuperuserManager.instance.hasPermission() && timePreference.isChecked) {
            SuperuserManager.instance.request(object : SuperuserManager.PermissionListener {
                override fun onGranted() {}

                override fun onDenied() {
                    mainHandler.post {
                        timePreference.isChecked = false
                    }
                }
            })
        }

        updateDevicePreferenceList()
        updateDevicesList()
        super.onResume()
    }

    override fun onPause() {
        usbCheckThread?.interrupt()
        super.onPause()
    }

    private fun isServiceRunning(): Boolean {
        val services = activityManager.getRunningServices(Int.MAX_VALUE)
        for (service in services) {
            if (USBGpsProviderService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun updateDevicePreferenceSummary() {
        devicePreference.value = "current"
        devicePreference.summary = getString(R.string.pref_gps_device_summary, getSelectedDeviceSummary())
        deviceSpeedPreference.summary = getString(
            R.string.pref_gps_device_speed_summary,
            sharedPreferences.getString(
                USBGpsProviderService.PREF_GPS_DEVICE_SPEED, getString(R.string.defaultGpsDeviceSpeed)
            )
        )
    }

    private fun getSelectedDeviceSummary(): String {
        val productId = sharedPreferences.getInt(
            USBGpsProviderService.PREF_GPS_DEVICE_PRODUCT_ID, DEFAULT_GPS_PRODUCT_ID
        )
        val vendorId = sharedPreferences.getInt(
            USBGpsProviderService.PREF_GPS_DEVICE_VENDOR_ID, DEFAULT_GPS_VENDOR_ID
        )

        var deviceDisplayedName = "Device not connected - $vendorId: $productId"

        for (usbDevice in usbManager.deviceList.values) {
            if (usbDevice.vendorId == vendorId && usbDevice.productId == productId) {
                deviceDisplayedName = "USB ${usbDevice.deviceProtocol} ${usbDevice.deviceName} | $vendorId: $productId"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    deviceDisplayedName = "${usbDevice.manufacturerName} ${usbDevice.productName} | $vendorId: $productId"
                }
                break
            }
        }

        return deviceDisplayedName
    }

    private fun updateDevicesList() {
        val connectedUsbDevices = usbManager.deviceList
        val entryValues = Array(connectedUsbDevices.size) { "" }
        val entries = Array(connectedUsbDevices.size) { "" }

        var i = 0
        for (device in connectedUsbDevices.values) {
            var entryValue = "${device.deviceName} - ${device.vendorId} : ${device.productId}"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                entryValue = "${device.manufacturerName} ${device.productName} - ${device.vendorId} : ${device.productId}"
            }

            entryValues[i] = device.deviceName
            entries[i] = entryValue
            i++
        }

        devicePreference.entryValues = entryValues
        devicePreference.entries = entries
    }

    private fun updateDevicePreferenceList() {
        val mockProvider = sharedPreferences.getString(
            USBGpsProviderService.PREF_MOCK_GPS_NAME, getString(R.string.defaultMockGpsName)
        )

        updateDevicePreferenceSummary()
        updateDevicesList()

        val pref = findPreference<Preference>(USBGpsProviderService.PREF_GPS_LOCATION_PROVIDER)
        if (sharedPreferences.getBoolean(USBGpsProviderService.PREF_REPLACE_STD_GPS, true)) {
            val s = getString(R.string.pref_gps_location_provider_summary)
            pref?.summary = s
            log("loc. provider: $s")
        } else {
            val s = getString(R.string.pref_mock_gps_name_summary, mockProvider)
            pref?.summary = s
            log("loc. provider: $s")
        }
    }

    private fun displayAboutDialog() {
        val activity = activity ?: return
        val messageView = activity.layoutInflater.inflate(R.layout.about, null, false)
        val textView = messageView.findViewById<TextView>(R.id.about_license)
        textView.movementMethod = LinkMovementMethod.getInstance()

        val defaultColor = textView.textColors.defaultColor
        textView.setTextColor(defaultColor)

        val sourcesTextView = messageView.findViewById<TextView>(R.id.about_sources)
        sourcesTextView.setTextColor(defaultColor)

        val versionTextView = messageView.findViewById<TextView>(R.id.about_version_text)
        versionTextView.text = getString(R.string.about_version, getString(R.string.version_name))

        AlertDialog.Builder(activity)
            .setTitle(R.string.about_title)
            .setIcon(R.drawable.gplv3_icon)
            .setView(messageView)
            .show()
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        if (preference.key == USBGpsProviderService.PREF_GPS_DEVICE) {
            val deviceName = newValue as String
            log("Device clicked: $newValue")

            if (deviceName.isNotEmpty() && usbManager.deviceList.containsKey(deviceName)) {
                val device = usbManager.deviceList[deviceName]
                if (device != null) {
                    sharedPreferences.edit().apply {
                        putInt(getString(R.string.pref_gps_device_product_id_key), device.productId)
                        putInt(getString(R.string.pref_gps_device_vendor_id_key), device.vendorId)
                        apply()
                    }
                }
            }

            updateDevicePreferenceSummary()
            return true
        }
        return false
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (prefs == null || key == null) return
        log("Shared preferences changed: $key")

        when (key) {
            USBGpsProviderService.PREF_START_GPS_PROVIDER -> {
                val valBoolean = prefs.getBoolean(key, false)
                val pref = findPreference<Preference>(USBGpsProviderService.PREF_START_GPS_PROVIDER)
                if (pref is SwitchPreferenceCompat && pref.isChecked != valBoolean) {
                    pref.isChecked = valBoolean
                }
            }
            USBGpsProviderService.PREF_TRACK_RECORDING -> {
                val valBoolean = prefs.getBoolean(key, false)
                val pref = findPreference<Preference>(USBGpsProviderService.PREF_TRACK_RECORDING)
                if (pref is SwitchPreferenceCompat && pref.isChecked != valBoolean) {
                    pref.isChecked = valBoolean
                }
            }
            USBGpsProviderService.PREF_GPS_DEVICE,
            USBGpsProviderService.PREF_GPS_DEVICE_SPEED -> {
                updateDevicePreferenceSummary()
            }
            USBGpsProviderService.PREF_SET_TIME -> {
                if (prefs.getBoolean(key, false)) {
                    val suManager = SuperuserManager.instance
                    if (!suManager.hasPermission()) {
                        (findPreference<Preference>(key) as? CheckBoxPreference)?.isChecked = false

                        suManager.request(object : SuperuserManager.PermissionListener {
                            override fun onGranted() {
                                mainHandler.post {
                                    (findPreference<Preference>(key) as? CheckBoxPreference)?.isChecked = true
                                }
                            }

                            override fun onDenied() {
                                mainHandler.post {
                                    AlertDialog.Builder(activity)
                                        .setMessage(R.string.warning_set_time_needs_su)
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show()
                                }
                            }
                        })
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    class ProviderPreferences : PreferenceFragmentCompat() {
        private lateinit var sharedPrefs: SharedPreferences

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            sharedPrefs = preferenceManager.sharedPreferences ?: PreferenceManager.getDefaultSharedPreferences(requireContext())
            addPreferencesFromResource(R.xml.provider_prefs)
            updatePreferenceDetails()
        }

        private fun updatePreferenceDetails() {
            val mockProvider = sharedPrefs.getString(
                USBGpsProviderService.PREF_MOCK_GPS_NAME, getString(R.string.defaultMockGpsName)
            )

            findPreference<Preference>(USBGpsProviderService.PREF_MOCK_GPS_NAME)?.let { pref ->
                pref.summary = getString(R.string.pref_mock_gps_name_summary, mockProvider)
            }

            val maxConnRetries = sharedPrefs.getString(
                USBGpsProviderService.PREF_CONNECTION_RETRIES, getString(R.string.defaultConnectionRetries)
            )

            findPreference<Preference>(USBGpsProviderService.PREF_CONNECTION_RETRIES)?.let { pref ->
                pref.summary = getString(R.string.pref_connection_retries_summary, maxConnRetries)
            }
        }
    }

    class SirfPreferences : PreferenceFragmentCompat(), OnSharedPreferenceChangeListener {
        private lateinit var sharedPrefs: SharedPreferences

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            sharedPrefs = preferenceManager.sharedPreferences ?: PreferenceManager.getDefaultSharedPreferences(requireContext())
            sharedPrefs.registerOnSharedPreferenceChangeListener(this)
            addPreferencesFromResource(R.xml.sirf_prefs)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (sharedPreferences == null || key == null) return
            if (USBGpsProviderService.PREF_SIRF_ENABLE_GLL == key ||
                USBGpsProviderService.PREF_SIRF_ENABLE_GGA == key ||
                USBGpsProviderService.PREF_SIRF_ENABLE_RMC == key ||
                USBGpsProviderService.PREF_SIRF_ENABLE_VTG == key ||
                USBGpsProviderService.PREF_SIRF_ENABLE_GSA == key ||
                USBGpsProviderService.PREF_SIRF_ENABLE_GSV == key ||
                USBGpsProviderService.PREF_SIRF_ENABLE_ZDA == key ||
                USBGpsProviderService.PREF_SIRF_ENABLE_SBAS == key ||
                USBGpsProviderService.PREF_SIRF_ENABLE_NMEA == key ||
                USBGpsProviderService.PREF_SIRF_ENABLE_STATIC_NAVIGATION == key
            ) {
                enableSirfFeature(key)
            }
        }

        private fun enableSirfFeature(key: String) {
            val activity = activity ?: return
            val configIntent = Intent(activity, USBGpsProviderService::class.java).apply {
                action = USBGpsProviderService.ACTION_CONFIGURE_SIRF_GPS
                putExtra(key, sharedPrefs.getBoolean(key, false))
            }
            activity.startService(configIntent)
        }

        override fun onDestroy() {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(this)
            super.onDestroy()
        }
    }

    class RecordingPreferences : PreferenceFragmentCompat() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.recording_prefs)
        }
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    companion object {
        private val TAG = USBGpsSettingsFragment::class.java.simpleName
        const val DEFAULT_GPS_PRODUCT_ID = 8963
        const val DEFAULT_GPS_VENDOR_ID = 1659
    }
}
