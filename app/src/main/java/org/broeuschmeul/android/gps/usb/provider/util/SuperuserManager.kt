package org.broeuschmeul.android.gps.usb.provider.util

import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/**
 * スーパーユーザー（SU / Root）権限の要求およびシェルコマンド実行を管理するクラスです。
 */
class SuperuserManager private constructor() {

    private var permission: Boolean = false
    private var numThreads = 0

    interface PermissionListener {
        fun onGranted()
        fun onDenied()
    }

    private fun execute(command: String): Boolean {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su -c $command")
            val result: Int
            try {
                result = process.waitFor()
                if (result != 0) { // コマンド実行エラー
                    Log.d(TAG, "result code : $result")
                    var line: String?
                    val bufferedReader = BufferedReader(InputStreamReader(process.errorStream))
                    try {
                        while (bufferedReader.readLine().also { line = it } != null) {
                            Log.d(TAG, "Error: $line")
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                } else {
                    process.destroy()
                    return true
                }
            } catch (e1: InterruptedException) {
                e1.printStackTrace()
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

        process?.destroy()
        return false
    }

    /**
     * スレッドが利用可能な場合に、非同期でコマンドを実行します。
     * スレッドの上限に達している場合は false を返します。
     */
    fun asyncExecute(command: String): Boolean {
        return if (numThreads < MAX_THREADS) {
            Thread {
                execute(command)
                synchronized(this) {
                    numThreads--
                }
            }.start()
            synchronized(this) {
                numThreads++
            }
            true
        } else {
            false
        }
    }

    /**
     * SU権限を要求します。
     */
    fun request(permissionListener: PermissionListener) {
        Thread {
            Log.d(TAG, "SU権限を要求中")
            if (execute("ls")) {
                permission = true
                permissionListener.onGranted()
            } else {
                permission = false
                permissionListener.onDenied()
            }
        }.start()
    }

    fun hasPermission(): Boolean {
        return permission
    }

    companion object {
        const val MAX_THREADS = 10
        val TAG: String = SuperuserManager::class.java.simpleName

        @JvmStatic
        val instance: SuperuserManager by lazy { SuperuserManager() }
    }
}
