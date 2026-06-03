package org.broeuschmeul.android.gps.usb.provider.ui

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.location.Location
import android.os.Bundle
import androidx.preference.PreferenceManager
import androidx.appcompat.widget.SwitchCompat
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import org.broeuschmeul.android.gps.nmea.util.NmeaParser
import org.broeuschmeul.android.gps.usb.provider.R
import org.broeuschmeul.android.gps.usb.provider.USBGpsApplication
import org.broeuschmeul.android.gps.usb.provider.driver.USBGpsProviderService
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GPS の状態表示および動作ログを表示するメインのアクティビティです。
 * 画面が大きく横向きの場合は、設定画面を横並びで表示するダブルパネルに対応しています。
 */
class GpsInfoActivity : USBGpsBaseActivity(), USBGpsApplication.ServiceDataListener {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var applicationInstance: USBGpsApplication

    private var startSwitch: SwitchCompat? = null
    private lateinit var numSatellites: TextView
    private lateinit var accuracyText: TextView
    private lateinit var locationText: TextView
    private lateinit var elevationText: TextView
    private lateinit var speedText: TextView
    private lateinit var bearingText: TextView
    private lateinit var logText: TextView
    private lateinit var timeText: TextView
    private lateinit var logTextScroller: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        val bundle = if (isDoublePanel()) null else savedInstanceState
        super.onCreate(bundle)

        if (isDoublePanel()) {
            setContentView(R.layout.activity_info_double)
        } else {
            setContentView(R.layout.activity_info)
        }

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        applicationInstance = application as USBGpsApplication

        setupUI()

        if (isDoublePanel()) {
            showSettingsFragment(R.id.settings_holder, false)
        }
    }

    private fun setupUI() {
        if (!isDoublePanel()) {
            startSwitch = findViewById<SwitchCompat>(R.id.service_start_switch).apply {
                setOnCheckedChangeListener { _, isChecked ->
                    sharedPreferences
                        .edit()
                        .putBoolean(USBGpsProviderService.PREF_START_GPS_PROVIDER, isChecked)
                        .apply()
                }
            }
        }

        numSatellites = findViewById(R.id.num_satellites_text)
        accuracyText = findViewById(R.id.accuracy_text)
        locationText = findViewById(R.id.location_text)
        elevationText = findViewById(R.id.elevation_text)
        speedText = findViewById(R.id.speed_text)
        bearingText = findViewById(R.id.bearing_text)
        timeText = findViewById(R.id.gps_time_text)

        logText = findViewById(R.id.log_box)
        logTextScroller = findViewById(R.id.log_box_scroller)
    }

    private fun isDoublePanel(): Boolean {
        val configuration = resources.configuration
        return (configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE &&
                configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun getCardinalDirection(bearing: Float): String {
        val directions = arrayOf(
            getString(R.string.bearing_n),
            getString(R.string.bearing_ne),
            getString(R.string.bearing_e),
            getString(R.string.bearing_se),
            getString(R.string.bearing_s),
            getString(R.string.bearing_sw),
            getString(R.string.bearing_w),
            getString(R.string.bearing_nw)
        )
        val index = ((bearing + 22.5) % 360 / 45).toInt()
        return directions[index % 8]
    }

    private fun updateData() {
        val running = sharedPreferences.getBoolean(USBGpsProviderService.PREF_START_GPS_PROVIDER, false)

        if (!isDoublePanel()) {
            startSwitch?.isChecked = running
        }

        var accuracyValue = "N/A"
        var numSatellitesValue = "N/A"
        var lat = "N/A"
        var lon = "N/A"
        var elevation = "N/A"
        var speedVal = "N/A"
        var bearingVal = "N/A"
        var bearingDegree = "N/A"
        var gpsTime = "N/A"
        var systemTime = "N/A"

        var location: Location? = applicationInstance.lastLocation
        if (!running) {
            location = null
        }

        if (location != null) {
            accuracyValue = location.accuracy.toString()
            location.extras?.let { extras ->
                numSatellitesValue = extras.getInt(NmeaParser.SATELLITE_KEY).toString()
            }
            val df = DecimalFormat("#.#####")
            lat = df.format(location.latitude)
            lon = df.format(location.longitude)
            elevation = location.altitude.toString()

            if (location.hasSpeed()) {
                val speedKmh = location.speed * 3.6f
                speedVal = DecimalFormat("#.#").format(speedKmh)
            }
            if (location.hasBearing()) {
                bearingDegree = DecimalFormat("#").format(location.bearing)
                bearingVal = getCardinalDirection(location.bearing)
            }

            gpsTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
                .format(Date(location.time))

            location.extras?.let { extras ->
                systemTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
                    .format(Date(extras.getLong(NmeaParser.SYSTEM_TIME_FIX)))
            }
        }

        numSatellites.text = getString(R.string.number_of_satellites_placeholder, numSatellitesValue)
        accuracyText.text = getString(R.string.accuracy_placeholder, accuracyValue)
        locationText.text = getString(R.string.location_placeholder, lat, lon)
        elevationText.text = getString(R.string.elevation_placeholder, elevation)
        speedText.text = getString(R.string.speed_placeholder, speedVal)
        bearingText.text = getString(R.string.bearing_placeholder, bearingVal, bearingDegree)
        timeText.text = getString(R.string.gps_time_placeholder, gpsTime, systemTime)
        updateLog()
    }

    fun updateLog() {
        val atBottom = (logText.bottom - (logTextScroller.height + logTextScroller.scrollY)) == 0

        logText.text = TextUtils.join("\n", applicationInstance.getLogLines())

        if (atBottom) {
            logText.post {
                logTextScroller.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    override fun onResume() {
        updateData()
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        (application as USBGpsApplication).registerServiceDataListener(this)
        super.onResume()
    }

    override fun onPause() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        (application as USBGpsApplication).unregisterServiceDataListener(this)
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (!isDoublePanel()) {
            menuInflater.inflate(R.menu.menu_main, menu)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onNewSentence(sentence: String) {
        updateLog()
    }

    override fun onLocationNotified(location: Location) {
        updateData()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (sharedPreferences == null || key == null) return
        if (key == USBGpsProviderService.PREF_START_GPS_PROVIDER) {
            updateData()
        }
        super.onSharedPreferenceChanged(sharedPreferences, key)
    }
}
