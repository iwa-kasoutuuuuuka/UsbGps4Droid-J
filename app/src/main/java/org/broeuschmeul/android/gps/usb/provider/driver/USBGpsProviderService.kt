package org.broeuschmeul.android.gps.usb.provider.driver

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import androidx.preference.PreferenceManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.util.Log
import android.widget.Toast
import org.broeuschmeul.android.gps.usb.provider.BuildConfig
import org.broeuschmeul.android.gps.usb.provider.R
import org.broeuschmeul.android.gps.usb.provider.ui.GpsInfoActivity
import org.broeuschmeul.android.gps.usb.provider.ui.USBGpsSettingsFragment
import org.broeuschmeul.android.gps.usb.provider.util.LocaleHelper
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 外部 USB GPS からの位置情報を取得し、内蔵 GPS の位置情報と置換供給したり、
 * NMEA データをテキストファイルに記録したりするバックグラウンドサービスです。
 */
class USBGpsProviderService : Service(), USBGpsManager.NmeaListener, LocationListener {

    private var gpsManager: USBGpsManager? = null
    private var writer: PrintWriter? = null
    private var trackFile: File? = null
    private var preludeWritten = false
    private var debugToasts = false

    private lateinit var notificationManager: NotificationManager

    override fun attachBaseContext(base: Context) {
        // 多言語設定をロードしたContextを適用します
        super.attachBaseContext(LocaleHelper.onAttach(base))
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val edit = sharedPreferences.edit()

        debugToasts = sharedPreferences.getBoolean(PREF_TOAST_LOGGING, false)

        val vendorId = sharedPreferences.getInt(
            PREF_GPS_DEVICE_VENDOR_ID,
            USBGpsSettingsFragment.DEFAULT_GPS_VENDOR_ID
        )
        val productId = sharedPreferences.getInt(
            PREF_GPS_DEVICE_PRODUCT_ID,
            USBGpsSettingsFragment.DEFAULT_GPS_PRODUCT_ID
        )

        val maxConRetries = sharedPreferences.getString(
            PREF_CONNECTION_RETRIES,
            this.getString(R.string.defaultConnectionRetries)
        )?.toIntOrNull() ?: 5

        log("prefs device addr: $vendorId - $productId")

        when (intent.action) {
            ACTION_START_GPS_PROVIDER -> {
                if (gpsManager == null) {
                    var mockProvider = LocationManager.GPS_PROVIDER
                    if (!sharedPreferences.getBoolean(PREF_REPLACE_STD_GPS, true)) {
                        mockProvider = sharedPreferences.getString(
                            PREF_MOCK_GPS_NAME,
                            getString(R.string.defaultMockGpsName)
                        ) ?: "usb_gps"
                    }
                    gpsManager = USBGpsManager(this, vendorId, productId, maxConRetries)
                    val enabled = gpsManager!!.enable()

                    if (sharedPreferences.getBoolean(PREF_START_GPS_PROVIDER, false) != enabled) {
                        edit.putBoolean(PREF_START_GPS_PROVIDER, enabled)
                        edit.apply()
                    }

                    if (enabled) {
                        gpsManager!!.enableMockLocationProvider(mockProvider)

                        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        } else {
                            PendingIntent.FLAG_CANCEL_CURRENT
                        }
                        val launchIntent = PendingIntent.getActivity(
                            this,
                            0,
                            Intent(this, GpsInfoActivity::class.java),
                            pendingFlags
                        )

                        sharedPreferences.edit()
                            .putInt(getString(R.string.pref_disable_reason_key), 0)
                            .apply()

                        val builder: NotificationCompat.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            notificationManager.createNotificationChannel(
                                NotificationChannel(
                                    NOTIFICATION_CHANNEL_ID,
                                    getString(R.string.app_name),
                                    NotificationManager.IMPORTANCE_HIGH
                                )
                            )
                            NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                        } else {
                            NotificationCompat.Builder(this, "")
                        }

                        val notification = builder
                            .setContentIntent(launchIntent)
                            .setSmallIcon(R.drawable.ic_stat_notify)
                            .setAutoCancel(true)
                            .setContentTitle(getString(R.string.foreground_service_started_notification_title))
                            .setContentText(getString(R.string.foreground_gps_provider_started_notification))
                            .build()

                        startForeground(R.string.foreground_gps_provider_started_notification, notification)

                        showToast(R.string.msg_gps_provider_started)

                        if (sharedPreferences.getBoolean(PREF_TRACK_RECORDING, false)) {
                            startTracking()
                        }
                    } else {
                        stopSelf()
                    }
                } else {
                    // 既に動作中のため、一旦終了し再起動
                    stopSelf()
                    startService(
                        Intent(this, USBGpsProviderService::class.java).setAction(intent.action)
                    )
                }
            }
            ACTION_START_TRACK_RECORDING -> {
                startTracking()
            }
            ACTION_STOP_TRACK_RECORDING -> {
                gpsManager?.removeNmeaListener(this)
                endTrack()
                showToast(getString(R.string.msg_nmea_recording_stopped))
                if (sharedPreferences.getBoolean(PREF_TRACK_RECORDING, true)) {
                    edit.putBoolean(PREF_TRACK_RECORDING, false)
                    edit.commit()
                }
            }
            ACTION_STOP_GPS_PROVIDER -> {
                if (sharedPreferences.getBoolean(PREF_START_GPS_PROVIDER, true)) {
                    edit.putBoolean(PREF_START_GPS_PROVIDER, false)
                    edit.commit()
                }
                stopSelf()
            }
            ACTION_CONFIGURE_SIRF_GPS, ACTION_ENABLE_SIRF_GPS -> {
                gpsManager?.let { manager ->
                    val extras = intent.extras
                    if (extras != null) {
                        manager.enableSirfConfig(extras)
                    } else {
                        manager.enableSirfConfig(sharedPreferences)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val manager = gpsManager
        gpsManager = null
        if (manager != null) {
            if (manager.disableReason != 0) {
                showToast(
                    getString(
                        R.string.msg_gps_provider_stopped_by_problem,
                        getString(manager.disableReason)
                    )
                )
            } else {
                showToast(R.string.msg_gps_provider_stopped)
            }
            manager.removeNmeaListener(this)
            manager.disableMockLocationProvider()
            manager.disable()
        }
        endTrack()
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val edit = sharedPreferences.edit()

        if (sharedPreferences.getBoolean(PREF_START_GPS_PROVIDER, true)) {
            edit.putBoolean(PREF_START_GPS_PROVIDER, false)
            edit.apply()
        }

        super.onDestroy()
    }

    private fun hasPermission(perm: String): Boolean {
        return PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(this, perm)
    }

    private fun startTracking() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val edit = sharedPreferences.edit()
        if (trackFile == null) {
            if (gpsManager != null) {
                if (hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    beginTrack()
                    gpsManager!!.addNmeaListener(this)
                    if (!sharedPreferences.getBoolean(PREF_TRACK_RECORDING, false)) {
                        edit.putBoolean(PREF_TRACK_RECORDING, true)
                        edit.apply()
                    }

                    showToast(R.string.msg_nmea_recording_started)
                } else {
                    Toast.makeText(this, "UsbGps logger - No storage permission", Toast.LENGTH_SHORT).show()
                    edit.putBoolean(PREF_TRACK_RECORDING, false).apply()
                }
            } else {
                endTrack()
            }
        } else {
            showToast(R.string.msg_nmea_recording_already_started)
        }
    }

    private fun showToast(messageId: Int) {
        if (debugToasts) {
            Toast.makeText(this, messageId, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showToast(message: String) {
        if (debugToasts) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun beginTrack() {
        @SuppressLint("SimpleDateFormat")
        val fmt = SimpleDateFormat("_yyyy-MM-dd_HH-mm-ss'.nmea'")

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val trackDirName = sharedPreferences.getString(
            PREF_TRACK_FILE_DIR,
            this.getString(R.string.defaultTrackFileDirectory)
        ) ?: "/sdcard/nmea"
        val trackFilePrefix = sharedPreferences.getString(
            PREF_TRACK_FILE_PREFIX,
            this.getString(R.string.defaultTrackFilePrefix)
        ) ?: "usbnmeatrack"

        trackFile = File(trackDirName, trackFilePrefix + fmt.format(Date()))
        log("Writing the prelude of the NMEA file: ${trackFile!!.absolutePath}")
        val trackDir = trackFile!!.parentFile
        try {
            if (!trackDir.mkdirs() && !trackDir.isDirectory) {
                Log.e(LOG_TAG, "Error while creating parent dir of NMEA file: ${trackDir.absolutePath}")
            }
            writer = PrintWriter(BufferedWriter(FileWriter(trackFile!!)))
            preludeWritten = true
        } catch (e: IOException) {
            Log.e(LOG_TAG, "Error while writing the prelude of the NMEA file: ${trackFile!!.absolutePath}", e)
            stopSelf()
        }
    }

    private fun endTrack() {
        if (trackFile != null && writer != null) {
            log("Ending the NMEA file: ${trackFile!!.absolutePath}")
            preludeWritten = false
            writer?.close()
            writer = null
            trackFile = null
        }
    }

    private fun addNMEAString(data: String) {
        if (!preludeWritten) {
            beginTrack()
        }
        log("Adding data in the NMEA file: $data")
        if (trackFile != null && writer != null) {
            writer?.print(data)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        log("trying access IBinder")
        return null
    }

    override fun onLocationChanged(location: Location) {}

    override fun onProviderDisabled(provider: String) {
        log("The GPS has been disabled.....stopping the NMEA tracker service.")
        stopSelf()
    }

    override fun onProviderEnabled(provider: String) {}

    override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) {}

    override fun onNmeaReceived(timestamp: Long, data: String) {
        addNMEAString(data)
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(LOG_TAG, message)
    }

    /**
     * サービスを自動開始するための BootReceiver です。
     */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            if (BuildConfig.DEBUG) Log.d(LOG_TAG, intent.action ?: "null")

            if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
                sharedPreferences.getBoolean(PREF_START_ON_BOOT, false)
            ) {
                Handler(context.mainLooper).postDelayed({
                    if (BuildConfig.DEBUG) Log.d(LOG_TAG, "Boot start")
                    context.startService(
                        Intent(context, USBGpsProviderService::class.java).apply {
                            action = ACTION_START_GPS_PROVIDER
                        }
                    )
                }, 2000)
            }
        }
    }

    companion object {
        const val ACTION_START_TRACK_RECORDING = "org.broeuschmeul.android.gps.usb.provider.action.START_TRACK_RECORDING"
        const val ACTION_STOP_TRACK_RECORDING = "org.broeuschmeul.android.gps.usb.provider.action.STOP_TRACK_RECORDING"
        const val ACTION_START_GPS_PROVIDER = "org.broeuschmeul.android.gps.usb.provider.action.START_GPS_PROVIDER"
        const val ACTION_STOP_GPS_PROVIDER = "org.broeuschmeul.android.gps.usb.provider.action.STOP_GPS_PROVIDER"
        const val ACTION_CONFIGURE_SIRF_GPS = "org.broeuschmeul.android.gps.usb.provider.action.CONFIGURE_SIRF_GPS"
        const val ACTION_ENABLE_SIRF_GPS = "org.broeuschmeul.android.gps.usb.provider.action.ENABLE_SIRF_GPS"

        const val PREF_START_GPS_PROVIDER = "startGps"
        const val PREF_START_ON_BOOT = "startOnBoot"
        const val PREF_GPS_LOCATION_PROVIDER = "gpsLocationProviderKey"
        const val PREF_REPLACE_STD_GPS = "replaceStdtGps"
        const val PREF_FORCE_ENABLE_PROVIDER = "forceEnableProvider"
        const val PREF_MOCK_GPS_NAME = "mockGpsName"
        const val PREF_CONNECTION_RETRIES = "connectionRetries"
        const val PREF_TRACK_RECORDING = "trackRecording"
        const val PREF_TRACK_FILE_DIR = "trackFileDirectory"
        const val PREF_TRACK_FILE_PREFIX = "trackFilePrefix"
        const val PREF_GPS_DEVICE = "usbDevice"
        const val PREF_GPS_DEVICE_VENDOR_ID = "usbDeviceVendorId"
        const val PREF_GPS_DEVICE_PRODUCT_ID = "usbDeviceProductId"
        const val PREF_GPS_DEVICE_SPEED = "gpsDeviceSpeed"
        const val PREF_TOAST_LOGGING = "showToasts"
        const val PREF_SET_TIME = "setTime"
        const val PREF_ABOUT = "about"

        private val LOG_TAG = USBGpsProviderService::class.java.simpleName

        const val PREF_SIRF_GPS = "sirfGps"
        const val PREF_SIRF_ENABLE_GGA = "enableGGA"
        const val PREF_SIRF_ENABLE_RMC = "enableRMC"
        const val PREF_SIRF_ENABLE_GLL = "enableGLL"
        const val PREF_SIRF_ENABLE_VTG = "enableVTG"
        const val PREF_SIRF_ENABLE_GSA = "enableGSA"
        const val PREF_SIRF_ENABLE_GSV = "enableGSV"
        const val PREF_SIRF_ENABLE_ZDA = "enableZDA"
        const val PREF_SIRF_ENABLE_SBAS = "enableSBAS"
        const val PREF_SIRF_ENABLE_NMEA = "enableNMEA"
        const val PREF_SIRF_ENABLE_STATIC_NAVIGATION = "enableStaticNavigation"

        private const val NOTIFICATION_CHANNEL_ID = "service_notification"
    }
}
