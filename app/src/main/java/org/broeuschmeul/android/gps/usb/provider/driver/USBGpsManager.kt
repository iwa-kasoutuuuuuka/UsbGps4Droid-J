package org.broeuschmeul.android.gps.usb.provider.driver

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.usb.*
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import androidx.preference.PreferenceManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.util.Log
import org.broeuschmeul.android.gps.nmea.util.NmeaParser
import org.broeuschmeul.android.gps.sirf.util.SirfUtils
import org.broeuschmeul.android.gps.usb.provider.BuildConfig
import org.broeuschmeul.android.gps.usb.provider.R
import org.broeuschmeul.android.gps.usb.provider.USBGpsApplication
import org.broeuschmeul.android.gps.usb.provider.ui.GpsInfoActivity
import org.broeuschmeul.android.gps.usb.provider.util.SuperuserManager
import java.io.*
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * USB GPS デバイスとの接続・通信を管理するコアクラスです。
 */
class USBGpsManager(
    private val callingService: Service,
    private var gpsVendorId: Int,
    private var gpsProductId: Int,
    maxRetries: Int
) {

    private var debug = true
    private var usbManager: UsbManager = callingService.getSystemService(Service.USB_SERVICE) as UsbManager

    interface NmeaListener {
        fun onNmeaReceived(timestamp: Long, nmea: String)
    }

    private val permissionAndDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null && usbManager.hasPermission(device)) {
                            debugLog("USBアクセス権限が承認されました。")
                            if (enabled) {
                                openConnection(device)
                            }
                        }
                    } else {
                        debugLog("デバイスのアクセス権限が拒否されました: $device")
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                synchronized(this) {
                    if (connectedGps != null && enabled) {
                        connectedGps?.close()
                    }
                }
            }
        }
    }

    /**
     * 接続された USB GPS との通信処理を管理する内部スレッドクラスです。
     */
    private inner class ConnectedGps : Thread {
        private val gpsUsbDev: UsbDevice
        private val intf: UsbInterface?
        private var endpointIn: UsbEndpoint? = null
        private var endpointOut: UsbEndpoint? = null
        private val connection: UsbDeviceConnection
        private var closed = false
        private val input: InputStream
        private val output: OutputStream
        private val printOutput: PrintStream
        var isReady = false
            private set

        private var lastRead: Long = 0

        constructor(device: UsbDevice) : this(device, defaultDeviceSpeed)

        constructor(device: UsbDevice, deviceSpeed: String) {
            this.gpsUsbDev = device
            debugLog("インターフェースを探索中。検出数: ${device.interfaceCount}")

            var foundInterface: UsbInterface? = null

            for (j in 0 until device.interfaceCount) {
                debugLog("インターフェース番号 $j をチェック中")
                val deviceInterface = device.getInterface(j)
                debugLog("検出したインターフェースクラス: ${deviceInterface.interfaceClass}")

                var foundInEndpoint: UsbEndpoint? = null
                var foundOutEndpoint: UsbEndpoint? = null

                for (i in deviceInterface.endpointCount - 1 downTo 0) {
                    debugLog("エンドポイント番号 $i をチェック中")
                    val interfaceEndpoint = deviceInterface.getEndpoint(i)

                    if (interfaceEndpoint.direction == UsbConstants.USB_DIR_IN) {
                        debugLog("IN エンドポイントを検出、タイプ: ${interfaceEndpoint.type}")
                        if (interfaceEndpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            debugLog("有効な IN Bulk エンドポイントです")
                            foundInEndpoint = interfaceEndpoint
                        }
                    }
                    if (interfaceEndpoint.direction == UsbConstants.USB_DIR_OUT) {
                        debugLog("OUT エンドポイントを検出、タイプ: ${interfaceEndpoint.type}")
                        if (interfaceEndpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            debugLog("有効な OUT Bulk エンドポイントです")
                            foundOutEndpoint = interfaceEndpoint
                        }
                    }

                    if (foundInEndpoint != null && foundOutEndpoint != null) {
                        endpointIn = foundInEndpoint
                        endpointOut = foundOutEndpoint
                        break
                    }
                }

                if (endpointIn != null && endpointOut != null) {
                    foundInterface = deviceInterface
                    break
                }
            }

            intf = foundInterface
            val timeout = 100
            connection = usbManager.openDevice(device)

            intf?.let {
                debugLog("インターフェースをクレームします")
                val resclaim = connection.claimInterface(it, true)
                debugLog("クレーム結果: $resclaim")
            }

            val tmpIn = object : InputStream() {
                private val buffer = ByteArray(128)
                private val usbBuffer = ByteArray(64)
                private val oneByteBuffer = ByteArray(1)
                private val bufferWrite = ByteBuffer.wrap(buffer)
                private val bufferRead = ByteBuffer.wrap(buffer).apply { limit(0) } as ByteBuffer
                private var isStreamClosed = false

                override fun read(): Int {
                    var nb = 0
                    while (nb == 0 && !isStreamClosed) {
                        nb = read(oneByteBuffer, 0, 1)
                    }
                    return if (nb > 0) {
                        oneByteBuffer[0].toInt() and 0xFF
                    } else {
                        -1
                    }
                }

                override fun available(): Int {
                    return bufferRead.remaining()
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    var nb = 0
                    val outBuf = ByteBuffer.wrap(b, off, len)
                    if (!bufferRead.hasRemaining() && !isStreamClosed) {
                        val n = connection.bulkTransfer(endpointIn, usbBuffer, 64, 10000)
                        if (n > 0) {
                            if (n > bufferWrite.remaining()) {
                                bufferRead.rewind()
                                bufferWrite.clear()
                            }
                            bufferWrite.put(usbBuffer, 0, n)
                            bufferRead.limit(bufferWrite.position())
                        } else {
                            if (BuildConfig.DEBUG || debug) {
                                Log.e(LOG_TAG, "データ読み込みエラー: $nb")
                            }
                        }
                    }
                    if (bufferRead.hasRemaining()) {
                        nb = Math.min(bufferRead.remaining(), len)
                        outBuf.put(bufferRead.array(), bufferRead.position() + bufferRead.arrayOffset(), nb)
                        bufferRead.position(bufferRead.position() + nb)
                    }
                    return nb
                }

                override fun close() {
                    super.close()
                    isStreamClosed = true
                }
            }

            val tmpOut = object : OutputStream() {
                private val buffer = ByteArray(128)
                private val oneByteBuffer = ByteArray(1)
                private val bufferWrite = ByteBuffer.wrap(buffer)
                private var isStreamClosed = false

                override fun write(oneByte: Int) {
                    oneByteBuffer[0] = oneByte.toByte()
                    write(oneByteBuffer, 0, 1)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    bufferWrite.clear()
                    bufferWrite.put(b, off, len)
                    var n = 0
                    if (!isStreamClosed) {
                        n = connection.bulkTransfer(endpointOut, buffer, len, timeout)
                    } else {
                        if (BuildConfig.DEBUG || debug) {
                            Log.e(LOG_TAG, "データ書き込み失敗: OutputStreamがクローズされています")
                        }
                    }
                    if (n != len) {
                        throw IOException("データ書き込みエラーが発生しました")
                    }
                }

                override fun close() {
                    super.close()
                    isStreamClosed = true
                }
            }

            var tmpOut2: PrintStream? = null
            try {
                tmpOut2 = PrintStream(tmpOut, false, "US-ASCII")
            } catch (e: UnsupportedEncodingException) {
                if (BuildConfig.DEBUG || debug) {
                    Log.e(LOG_TAG, "USB出力ストリーム取得エラー", e)
                }
            }

            input = tmpIn
            output = tmpOut
            printOutput = tmpOut2!!

            if (endpointIn == null || endpointOut == null) {
                if (BuildConfig.DEBUG || debug) {
                    Log.e(LOG_TAG, "デバイスのエンドポイントが見つかりません。")
                }
                disable(R.string.msg_gps_provider_cant_connect)
                close()
                return
            }

            val speedList = intArrayOf(deviceSpeed.toInt(), 1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200)
            val data = byteArrayOf(0xC0.toByte(), 0x12, 0x00, 0x00, 0x00, 0x00, 0x08)
            val connectionSpeedBuffer = ByteBuffer.wrap(data, 0, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val sirfBin2Nmea = SirfUtils.genSirfCommandFromPayload(callingService.getString(R.string.sirf_bin_to_nmea))
            val datax = ByteArray(7)
            val connectionSpeedInfoBuffer = ByteBuffer.wrap(datax, 0, 7).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val res1 = connection.controlTransfer(0x21, 34, 0, 0, null, 0, timeout)

            if (sirfGps) {
                debugLog("SiRFバイナリからNMEAへの切り替えを試みます")
                try {
                    connection.bulkTransfer(endpointOut, sirfBin2Nmea, sirfBin2Nmea.size, timeout)
                } catch (e: NullPointerException) {
                    if (BuildConfig.DEBUG || debug) {
                        Log.e(LOG_TAG, "接続エラーが発生しました")
                    }
                    close()
                    return
                }
            }

            if (setDeviceSpeed) {
                debugLog("接続速度を設定中: $deviceSpeed")
                try {
                    connectionSpeedBuffer.putInt(0, deviceSpeed.toInt())
                    connection.controlTransfer(0x21, 32, 0, 0, data, 7, timeout)
                } catch (e: NullPointerException) {
                    if (BuildConfig.DEBUG || debug) {
                        Log.e(LOG_TAG, "通信速度の設定に失敗しました")
                    }
                    close()
                }
            } else {
                val autoConf = Thread {
                    try {
                        var res0 = connection.controlTransfer(0xA1, 33, 0, 0, datax, 7, timeout)
                        this@USBGpsManager.deviceSpeed = connectionSpeedInfoBuffer.getInt(0).toString()

                        debugLog("接続情報: ${Arrays.toString(datax)}")
                        debugLog("自動検出された接続速度: ${this@USBGpsManager.deviceSpeed}")

                        sleep(4000)
                        debugLog("対応速度リストを検証中: ${Arrays.toString(speedList)}")
                        for (speed in speedList) {
                            if (!isReady && !closed) {
                                this@USBGpsManager.deviceSpeed = speed.toString()
                                debugLog("通信速度 $speed を試行中")
                                connectionSpeedBuffer.putInt(0, speed)

                                val res2 = connection.controlTransfer(0x21, 32, 0, 0, data, 7, timeout)
                                if (sirfGps) {
                                    debugLog("SiRFバイナリからNMEAへの切り替えを試行中")
                                    connection.bulkTransfer(endpointOut, sirfBin2Nmea, sirfBin2Nmea.size, timeout)
                                }
                                debugLog("初期化結果: $res1 $res2")
                                sleep(4000)
                            }
                        }

                        res0 = connection.controlTransfer(0xA1, 33, 0, 0, datax, 7, timeout)
                        debugLog("最終接続情報: ${Arrays.toString(datax)}")
                        debugLog("最終確定接続速度: ${connectionSpeedInfoBuffer.getInt(0)}")

                        if (!closed) {
                            sleep(10000)
                        }
                    } catch (e: InterruptedException) {
                        if (BuildConfig.DEBUG || debug) {
                            Log.e(LOG_TAG, "自動検出スレッドが中断されました", e)
                        }
                    } finally {
                        if ((!closed && !isReady) || (lastRead + 4000 < SystemClock.uptimeMillis())) {
                            setMockLocationProviderOutOfService()
                            if (BuildConfig.DEBUG || debug) {
                                Log.e(LOG_TAG, "自動ボーレート検出処理に失敗しました。")
                            }
                            this@ConnectedGps.close()
                            this@USBGpsManager.disableIfNeeded()
                        }
                    }
                }
                debugLog("自動検出スレッドを開始します")
                isReady = false
                autoConf.start()
            }
        }

        override fun run() {
            try {
                val reader = BufferedReader(InputStreamReader(input, "US-ASCII"), 128)
                var s: String?
                var now = SystemClock.uptimeMillis()

                lastRead = now + 45000
                while (enabled && (now < lastRead + 4000) && !closed) {
                    try {
                        s = reader.readLine()
                    } catch (e: IOException) {
                        s = null
                    }

                    if (s != null) {
                        if (notifyNmeaSentence(s + "\r\n")) {
                            isReady = true
                            lastRead = SystemClock.uptimeMillis()

                            if (problemNotified) {
                                problemNotified = false
                                disableReason = 0
                                debugLog("接続状態が改善したため、再試行回数をリセットします。")
                                nbRetriesRemaining = maxConnectionRetries
                                notificationManager.cancel(R.string.connection_problem_notification_title)
                            }
                        }
                    } else {
                        SystemClock.sleep(100)
                    }
                    now = SystemClock.uptimeMillis()
                }

                if (now > lastRead + 4000) {
                    if (BuildConfig.DEBUG || debug) {
                        Log.e(LOG_TAG, "読み込みタイムアウトが発生しました。")
                    }
                } else if (closed) {
                    debugLog("接続が閉じられたため、読み込みスレッドを終了します。")
                } else {
                    debugLog("プロバイダが無効化されたため、読み込みスレッドを終了します。")
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG || debug) {
                    Log.e(LOG_TAG, "データ受信中にエラーが発生しました", e)
                }
                setMockLocationProviderOutOfService()
            } finally {
                debugLog("読み込みスレッドを正常終了します。")
                this.close()
                disableIfNeeded()
            }
        }

        fun write(buffer: ByteArray) {
            try {
                do {
                    sleep(100)
                } while (enabled && !isReady && !closed)
                if (enabled && isReady && !closed) {
                    output.write(buffer)
                    output.flush()
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG || debug) {
                    Log.e(LOG_TAG, "書き込み例外が発生しました", e)
                }
            }
        }

        fun write(buffer: String) {
            try {
                do {
                    sleep(100)
                } while (enabled && !isReady && !closed)
                if (enabled && isReady && !closed) {
                    printOutput.print(buffer)
                    printOutput.flush()
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG || debug) {
                    Log.e(LOG_TAG, "文字列書き込み例外が発生しました", e)
                }
            }
        }

        fun close() {
            isReady = false
            closed = true
            try {
                debugLog("USB GPS 出力ストリームを閉じています")
                input.close()
            } catch (e: IOException) {
                if (BuildConfig.DEBUG || debug) {
                    Log.e(LOG_TAG, "ストリームクローズ中のエラー", e)
                }
            } finally {
                try {
                    debugLog("USB GPS 入力ストリームを閉じています")
                    printOutput.close()
                    output.close()
                } catch (e: IOException) {
                    if (BuildConfig.DEBUG || debug) {
                        Log.e(LOG_TAG, "ストリームクローズ中のエラー", e)
                    }
                } finally {
                    debugLog("USBインターフェースの解放を試みます: $connection")
                    var released = false
                    intf?.let {
                        released = connection.releaseInterface(it)
                    }

                    if (released) {
                        debugLog("USBインターフェースが解放されました: $connection")
                    } else if (intf != null) {
                        debugLog("USBインターフェースの解放に失敗しました: $connection")
                    }

                    debugLog("USBデバイスの接続を閉じています: $connection")
                    connection.close()
                }
            }
        }
    }

    private var timeSetAlready = false
    private var shouldSetTime = false

    private var parser: NmeaParser
    private var enabled = false
    private var notificationPool: ExecutorService? = null
    private var connectionAndReadingPool: ScheduledExecutorService? = null

    private val nmeaListeners = Collections.synchronizedList(LinkedList<NmeaListener>())

    private var locationManager: LocationManager = callingService.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(callingService)
    private var connectedGps: ConnectedGps? = null

    var disableReason = 0

    private var connectionProblemNotificationBuilder: NotificationCompat.Builder
    private var serviceStoppedNotificationBuilder: NotificationCompat.Builder

    private var appContext: Context = callingService.applicationContext
    private var notificationManager: NotificationManager = callingService.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var maxConnectionRetries: Int = maxRetries + 1
    private var nbRetriesRemaining: Int = maxConnectionRetries
    private var problemNotified = false

    private var connected = false
    private var setDeviceSpeed = false
    private var sirfGps = false
    private var deviceSpeed = "auto"
    private var defaultDeviceSpeed = "4800"

    init {
        deviceSpeed = sharedPreferences.getString(
            USBGpsProviderService.PREF_GPS_DEVICE_SPEED,
            callingService.getString(R.string.defaultGpsDeviceSpeed)
        ) ?: "4800"

        shouldSetTime = sharedPreferences.getBoolean(USBGpsProviderService.PREF_SET_TIME, false)
        timeSetAlready = true

        defaultDeviceSpeed = callingService.getString(R.string.defaultGpsDeviceSpeed)
        setDeviceSpeed = deviceSpeed != callingService.getString(R.string.autoGpsDeviceSpeed)
        sirfGps = sharedPreferences.getBoolean(USBGpsProviderService.PREF_SIRF_GPS, false)
        parser = NmeaParser(10f, appContext).apply {
            setLocationManager(this@USBGpsManager.locationManager)
        }

        val stopIntent = Intent(USBGpsProviderService.ACTION_STOP_GPS_PROVIDER)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_CANCEL_CURRENT
        }
        val stopPendingIntent = PendingIntent.getService(
            appContext,
            0,
            stopIntent,
            pendingFlags
        )

        connectionProblemNotificationBuilder = NotificationCompat.Builder(appContext)
            .setContentIntent(stopPendingIntent)
            .setSmallIcon(R.drawable.ic_stat_notify)

        val restartIntent = Intent(USBGpsProviderService.ACTION_START_GPS_PROVIDER)
        val restartPendingIntent = PendingIntent.getService(
            appContext,
            0,
            restartIntent,
            pendingFlags
        )

        serviceStoppedNotificationBuilder = NotificationCompat.Builder(appContext)
            .setContentIntent(restartPendingIntent)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(appContext.getString(R.string.service_closed_because_connection_problem_notification_title))
            .setContentText(appContext.getString(R.string.service_closed_because_connection_problem_notification))
    }



    @Synchronized
    fun isEnabled(): Boolean {
        return enabled
    }

    fun isMockLocationEnabled(): Boolean {
        var isMockLocation: Boolean
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val opsManager = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                isMockLocation = opsManager.checkOp(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    android.os.Process.myUid(),
                    BuildConfig.APPLICATION_ID
                ) == AppOpsManager.MODE_ALLOWED
            } else {
                @Suppress("DEPRECATION")
                isMockLocation = Settings.Secure.getString(
                    appContext.contentResolver,
                    "mock_location"
                ) != "0"
            }
        } catch (e: Exception) {
            return false
        }
        return isMockLocation
    }

    private fun openConnection(device: UsbDevice) {
        if (getDeviceFromAttached() != device) {
            return
        }

        // 10秒待機後に時間同期フラグをリセットします
        Handler(appContext.mainLooper).postDelayed({
            timeSetAlready = false
        }, 10000)

        connected = true

        if (setDeviceSpeed) {
            log("デバイス設定速度: $deviceSpeed")
        } else {
            log("デフォルト速度を使用します: $defaultDeviceSpeed")
            deviceSpeed = defaultDeviceSpeed
        }

        log("USB読み込みタスクを開始します")
        connectedGps = ConnectedGps(device, deviceSpeed)
        if (isEnabled()) {
            connectionAndReadingPool?.execute(connectedGps)
            log("USB読み込みスレッドを開始しました")
        }
    }

    private fun getDeviceFromAttached(): UsbDevice? {
        debugLog("すべての接続済みUSBデバイスを確認中...")
        for (connectedDevice in usbManager.deviceList.values) {
            debugLog("デバイスチェック: ${connectedDevice.productId} / ${connectedDevice.vendorId}")
            if (connectedDevice.vendorId == gpsVendorId && connectedDevice.productId == gpsProductId) {
                debugLog("対象のUSB GPSデバイスを検出しました")
                return connectedDevice
            }
        }
        return null
    }

    @Synchronized
    fun enable(): Boolean {
        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        notificationManager.cancel(R.string.service_closed_because_connection_problem_notification_title)

        if (!enabled) {
            log("USB GPS マネージャーを有効化します")

            if (!isMockLocationEnabled()) {
                if (BuildConfig.DEBUG || debug) Log.e(LOG_TAG, "仮の位置情報プロバイダが無効です。")
                disable(R.string.msg_mock_location_disabled)
                return this.enabled
            } else if (PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(
                    callingService, Manifest.permission.ACCESS_FINE_LOCATION
                )
            ) {
                if (BuildConfig.DEBUG || debug) Log.e(LOG_TAG, "位置情報のパーミッションがありません。")
                disable(R.string.msg_no_location_permission)
                return this.enabled
            } else {
                var gpsDev = getDeviceFromAttached()

                val connectThread = Runnable {
                    try {
                        debugLog("接続用スレッドを開始します")
                        connected = false
                        gpsDev = getDeviceFromAttached()

                        if (nbRetriesRemaining > 0) {
                            connectedGps?.close()

                            val device = gpsDev
                            if (device != null) {
                                debugLog("GPS デバイス: ${device.deviceName}")
                                val permFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    PendingIntent.FLAG_MUTABLE
                                } else {
                                    0
                                }
                                val permissionIntent = PendingIntent.getBroadcast(
                                    callingService,
                                    0,
                                    Intent(ACTION_USB_PERMISSION),
                                    permFlags
                                )

                                if (usbManager.hasPermission(device)) {
                                    debugLog("パーミッションは既にあります")
                                    openConnection(device)
                                } else {
                                    debugLog("パーミッションを要求中...")
                                    usbManager.requestPermission(device, permissionIntent)
                                }
                            } else {
                                if (BuildConfig.DEBUG || debug) {
                                    Log.e(
                                        LOG_TAG,
                                        "接続エラーが発生しました。デバイスが見つかりません - $gpsVendorId:$gpsProductId"
                                    )
                                }
                                disable(R.string.msg_usb_provider_device_not_connected)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        nbRetriesRemaining--
                        if (!connected) {
                            disableIfNeeded()
                        }
                    }
                }

                if (gpsDev != null) {
                    this.enabled = true
                    callingService.registerReceiver(permissionAndDetachReceiver, permissionFilter)
                    debugLog("USB GPS マネージャーが有効化されました")

                    notificationPool = Executors.newSingleThreadExecutor()
                    connectionAndReadingPool = Executors.newSingleThreadScheduledExecutor()

                    connectionAndReadingPool?.scheduleWithFixedDelay(
                        connectThread,
                        1000,
                        1000,
                        TimeUnit.MILLISECONDS
                    )

                    if (sirfGps) {
                        enableSirfConfig(sharedPreferences)
                    }
                }
            }

            if (!this.enabled) {
                if (BuildConfig.DEBUG || debug) Log.e(LOG_TAG, "接続に失敗しました: デバイスなし")
                disable(R.string.msg_usb_provider_device_not_connected)
            }
        }
        return this.enabled
    }

    @Synchronized
    private fun disableIfNeeded() {
        if (enabled) {
            problemNotified = true
            if (nbRetriesRemaining > 0) {
                if (BuildConfig.DEBUG || debug) Log.e(LOG_TAG, "接続が切断されました")

                val pbMessage = appContext.resources.getQuantityString(
                    R.plurals.connection_problem_notification,
                    nbRetriesRemaining,
                    nbRetriesRemaining
                )

                val connectionProblemNotification = connectionProblemNotificationBuilder
                    .setWhen(System.currentTimeMillis())
                    .setContentTitle(appContext.getString(R.string.connection_problem_notification_title))
                    .setContentText(pbMessage)
                    .setNumber(1 + maxConnectionRetries - nbRetriesRemaining)
                    .build()

                notificationManager.notify(
                    R.string.connection_problem_notification_title,
                    connectionProblemNotification
                )
            } else {
                disable(R.string.msg_two_many_connection_problems)
            }
        }
    }

    @Synchronized
    fun disable(reasonId: Int) {
        debugLog("USB GPS マネージャーを停止します。理由: ${callingService.getString(reasonId)}")
        disableReason = reasonId
        disable()
    }

    @Synchronized
    fun disable() {
        notificationManager.cancel(R.string.connection_problem_notification_title)

        if (disableReason != 0) {
            val partialServiceStoppedNotification = serviceStoppedNotificationBuilder
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true)
                .setContentTitle(
                    appContext.getString(R.string.service_closed_because_connection_problem_notification_title)
                )
                .setContentText(
                    appContext.getString(
                        R.string.service_closed_because_connection_problem_notification,
                        appContext.getString(disableReason)
                    )
                )

            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
            if (disableReason == R.string.msg_mock_location_disabled) {
                val mockLocationsSettingsIntent = PendingIntent.getActivity(
                    appContext,
                    0,
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                    pendingFlags
                )

                partialServiceStoppedNotification
                    .setContentIntent(mockLocationsSettingsIntent)
                    .setStyle(
                        NotificationCompat.BigTextStyle().bigText(
                            appContext.getString(
                                R.string.service_closed_because_connection_problem_notification,
                                appContext.getString(R.string.msg_mock_location_disabled_full)
                            )
                        )
                    )
            } else if (disableReason == R.string.msg_no_location_permission) {
                val mockLocationsSettingsIntent = PendingIntent.getActivity(
                    appContext,
                    0,
                    Intent(callingService, GpsInfoActivity::class.java),
                    pendingFlags
                )

                USBGpsApplication.setLocationNotAsked()

                partialServiceStoppedNotification
                    .setContentIntent(mockLocationsSettingsIntent)
                    .setStyle(
                        NotificationCompat.BigTextStyle().bigText(
                            appContext.getString(
                                R.string.service_closed_because_connection_problem_notification,
                                appContext.getString(R.string.msg_no_location_permission)
                            )
                        )
                    )
            }

            val serviceStoppedNotification = partialServiceStoppedNotification.build()
            notificationManager.notify(
                R.string.service_closed_because_connection_problem_notification_title,
                serviceStoppedNotification
            )

            sharedPreferences.edit()
                .putInt(appContext.getString(R.string.pref_disable_reason_key), disableReason)
                .apply()
        }

        if (enabled) {
            debugLog("USB GPS マネージャーを停止しています...")
            callingService.unregisterReceiver(permissionAndDetachReceiver)

            enabled = false
            connectionAndReadingPool?.shutdown()

            val closeAndShutdown = Runnable {
                try {
                    connectionAndReadingPool?.awaitTermination(10, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }

                if (connectionAndReadingPool?.isTerminated == false) {
                    connectionAndReadingPool?.shutdownNow()
                    connectedGps?.close()
                }
            }

            notificationPool?.execute(closeAndShutdown)
            nmeaListeners.clear()
            disableMockLocationProvider()
            notificationPool?.shutdown()
            callingService.stopSelf()

            sharedPreferences.edit()
                .putBoolean(USBGpsProviderService.PREF_START_GPS_PROVIDER, false)
                .apply()

            debugLog("USB GPS マネージャーの停止が完了しました。")
        }
    }

    fun enableMockLocationProvider(gpsName: String, force: Boolean) {
        debugLog("仮の位置情報プロバイダを有効にします: $gpsName")
        parser.enableMockLocationProvider(gpsName, force)
    }

    fun enableMockLocationProvider(gpsName: String) {
        debugLog("仮の位置情報プロバイダを有効にします: $gpsName")
        val force = sharedPreferences.getBoolean(USBGpsProviderService.PREF_FORCE_ENABLE_PROVIDER, true)
        parser.enableMockLocationProvider(gpsName, force)
    }

    fun disableMockLocationProvider() {
        debugLog("仮の位置情報プロバイダを無効にします")
        parser.disableMockLocationProvider()
    }

    fun isMockGpsEnabled(): Boolean {
        return parser.isMockGpsEnabled
    }

    fun getMockLocationProvider(): String? {
        return parser.mockLocationProvider
    }

    private fun setMockLocationProviderOutOfService() {
        parser.setMockLocationProviderOutOfService()
    }

    fun addNmeaListener(listener: NmeaListener): Boolean {
        if (!nmeaListeners.contains(listener)) {
            debugLog("新しいNMEAリスナーを追加します。")
            nmeaListeners.add(listener)
        }
        return true
    }

    fun removeNmeaListener(listener: NmeaListener) {
        debugLog("NMEAリスナーを解除します。")
        nmeaListeners.remove(listener)
    }

    @SuppressLint("SimpleDateFormat")
    private fun setSystemTime(time: String) {
        val parseTime = parser.parseNmeaTime(time)
        val timeFormatToybox = SimpleDateFormat("MMddHHmmyyyy.ss").format(Date(parseTime))
        val timeFormatToolbox = SimpleDateFormat("yyyyMMdd.HHmmss").format(Date(parseTime))

        debugLog("システム時刻設定値: $timeFormatToybox")
        val suManager = SuperuserManager.instance

        if (suManager.hasPermission()) {
            suManager.asyncExecute(
                "toolbox date -s $timeFormatToolbox; toybox date $timeFormatToybox; am broadcast -a android.intent.action.TIME_SET"
            )
        } else {
            sharedPreferences.edit()
                .putBoolean(USBGpsProviderService.PREF_SET_TIME, false)
                .apply()
        }
    }

    private fun notifyNmeaSentence(nmeaSentence: String): Boolean {
        var res = false
        if (enabled) {
            log("NMEAセンテンスの解析と通知を実行中: $nmeaSentence")
            var sentence: String? = null
            try {
                if (shouldSetTime && !timeSetAlready) {
                    parser.clearLastSentenceTime()
                }

                sentence = parser.parseNmeaSentence(nmeaSentence)

                if (shouldSetTime && !timeSetAlready) {
                    if (parser.lastSentenceTime.isNotEmpty()) {
                        setSystemTime(parser.lastSentenceTime)
                        timeSetAlready = true
                    }
                }
            } catch (e: SecurityException) {
                if (BuildConfig.DEBUG || debug) {
                    Log.e(LOG_TAG, "NMEAパース中のセキュリティ例外: $nmeaSentence", e)
                }
                sentence = null
                disable(R.string.msg_mock_location_disabled)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG || debug) {
                    Log.e(LOG_TAG, "NMEAセンテンスが解釈できません。")
                    Log.e(LOG_TAG, nmeaSentence)
                }
                e.printStackTrace()
            }

            val recognizedSentence = sentence
            val timestamp = System.currentTimeMillis()
            if (recognizedSentence != null) {
                res = true
                log("検知したNMEAセンテンス: $recognizedSentence")

                (appContext as USBGpsApplication).notifyNewSentence(
                    recognizedSentence.replace("(\\r|\\n)".toRegex(), "")
                )

                synchronized(nmeaListeners) {
                    for (listener in nmeaListeners) {
                        notificationPool?.execute {
                            listener.onNmeaReceived(timestamp, recognizedSentence)
                        }
                    }
                }
            }
        }
        return res
    }

    fun sendPackagedNmeaCommand(command: String) {
        log("NMEAコマンドを送信中: $command")
        connectedGps?.write(command)
    }

    fun sendPackagedSirfCommand(commandHexa: String) {
        val command = SirfUtils.genSirfCommand(commandHexa)
        log("SiRFバイナリコマンドを送信中: $commandHexa")
        connectedGps?.write(command)
    }

    fun sendNmeaCommand(sentence: String) {
        val command = String.format(Locale.US, "$%s*%02X\r\n", sentence, parser.computeChecksum(sentence))
        sendPackagedNmeaCommand(command)
    }

    fun sendSirfCommand(payload: String) {
        val command = SirfUtils.createSirfCommandFromPayload(payload)
        sendPackagedSirfCommand(command)
    }

    private fun enableNMEA(enable: Boolean) {
        if (deviceSpeed == callingService.getString(R.string.autoGpsDeviceSpeed)) {
            deviceSpeed = callingService.getString(R.string.defaultGpsDeviceSpeed)
        }
        SystemClock.sleep(400)
        if (enable) {
            val command = callingService.getString(R.string.sirf_bin_to_nmea)
            this.sendSirfCommand(command)
        } else {
            this.sendNmeaCommand(
                callingService.getString(R.string.sirf_nmea_to_binary_alt, deviceSpeed.toInt())
            )
        }
        SystemClock.sleep(400)
    }

    private fun enableNmeaGGA(enable: Boolean) {
        if (enable) {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_gga_on))
        } else {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_gga_off))
        }
    }

    private fun enableNmeaGLL(enable: Boolean) {
        if (enable) {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_gll_on))
        } else {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_gll_off))
        }
    }

    private fun enableNmeaGSA(enable: Boolean) {
        if (enable) {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_gsa_on))
        } else {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_gsa_off))
        }
    }

    private fun enableNmeaGSV(enable: Boolean) {
        if (enable) {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_gsv_on))
        } else {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_gsv_off))
        }
    }

    private fun enableNmeaRMC(enable: Boolean) {
        if (enable) {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_rmc_on))
        } else {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_rmc_off))
        }
    }

    private fun enableNmeaVTG(enable: Boolean) {
        if (enable) {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_vtg_on))
        } else {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_vtg_off))
        }
    }

    private fun enableNmeaZDA(enable: Boolean) {
        if (enable) {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_zda_on))
        } else {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_zda_off))
        }
    }

    private fun enableSBAS(enable: Boolean) {
        if (enable) {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_sbas_on))
        } else {
            this.sendNmeaCommand(callingService.getString(R.string.sirf_nmea_sbas_off))
        }
    }

    fun enableSirfConfig(extra: Bundle) {
        debugLog("SiRF設定をキューに配置中: $extra")
        if (isEnabled()) {
            notificationPool?.execute {
                while (enabled && (!connected || connectedGps == null || connectedGps?.isReady == false)) {
                    debugLog("書き込み用スレッドの準備が整っていません。")
                    SystemClock.sleep(500)
                }
                if (isEnabled() && connected && connectedGps != null && connectedGps?.isReady == true) {
                    debugLog("SiRF設定の適用を開始: $extra")
                    if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_GGA)) {
                        enableNmeaGGA(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GGA, true))
                    }
                    if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_RMC)) {
                        enableNmeaRMC(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_RMC, true))
                    }
                    if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_GLL)) {
                        enableNmeaGLL(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GLL, false))
                    }
                    if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_VTG)) {
                        enableNmeaVTG(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_VTG, false))
                    }
                    if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_GSA)) {
                        enableNmeaGSA(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GSA, false))
                    }
                    if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_GSV)) {
                        enableNmeaGSV(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GSV, false))
                    }
                    if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_ZDA)) {
                        enableNmeaZDA(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_ZDA, false))
                    }
                    if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_STATIC_NAVIGATION)) {
                        enableStaticNavigation(
                            extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_STATIC_NAVIGATION, false)
                        )
                    } else if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_NMEA)) {
                        enableNMEA(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_NMEA, true))
                    }
                    if (extra.containsKey(USBGpsProviderService.PREF_SIRF_ENABLE_SBAS)) {
                        enableSBAS(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_SBAS, true))
                    }
                    debugLog("SiRF設定の適用が完了しました。: $extra")
                }
            }
        }
    }

    fun enableSirfConfig(extra: SharedPreferences) {
        debugLog("SiRF設定を適用キューに配置中: $extra")
        if (isEnabled()) {
            notificationPool?.execute {
                while (enabled && (!connected || connectedGps == null || connectedGps?.isReady == false)) {
                    debugLog("書き込みスレッドの準備が整っていません。")
                    SystemClock.sleep(500)
                }
                if (isEnabled() && connected && connectedGps != null && connectedGps?.isReady == true) {
                    debugLog("SiRF設定を反映中: $extra")
                    if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_GLL)) {
                        enableNmeaGLL(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GLL, false))
                    }
                    if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_VTG)) {
                        enableNmeaVTG(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_VTG, false))
                    }
                    if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_GSA)) {
                        enableNmeaGSA(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GSA, false))
                    }
                    if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_GSV)) {
                        enableNmeaGSV(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GSV, false))
                    }
                    if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_ZDA)) {
                        enableNmeaZDA(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_ZDA, false))
                    }
                    if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_STATIC_NAVIGATION)) {
                        enableStaticNavigation(
                            extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_STATIC_NAVIGATION, false)
                        )
                    } else if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_NMEA)) {
                        enableNMEA(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_NMEA, true))
                    }
                    if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_SBAS)) {
                        enableSBAS(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_SBAS, true))
                    }
                    sendNmeaCommand(callingService.getString(R.string.sirf_nmea_gga_on))
                    sendNmeaCommand(callingService.getString(R.string.sirf_nmea_rmc_on))
                    if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_GGA)) {
                        enableNmeaGGA(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_GGA, true))
                    }
                    if (extra.contains(USBGpsProviderService.PREF_SIRF_ENABLE_RMC)) {
                        enableNmeaRMC(extra.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_RMC, true))
                    }
                }
            }
        }
    }

    private fun enableStaticNavigation(enable: Boolean) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(callingService)
        val isInNmeaMode = sharedPreferences.getBoolean(USBGpsProviderService.PREF_SIRF_ENABLE_NMEA, true)
        if (isInNmeaMode) {
            enableNMEA(false)
        }
        if (enable) {
            this.sendSirfCommand(callingService.getString(R.string.sirf_bin_static_nav_on))
        } else {
            this.sendSirfCommand(callingService.getString(R.string.sirf_bin_static_nav_off))
        }
        if (isInNmeaMode) {
            enableNMEA(true)
        }
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(LOG_TAG, message)
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG || debug) Log.d(LOG_TAG, message)
    }

    companion object {
        private val LOG_TAG = USBGpsManager::class.java.simpleName
        private const val ACTION_USB_PERMISSION = "org.broeuschmeul.android.gps.usb.provider.driver.USBGpsManager.USB_PERMISSION"
    }
}
