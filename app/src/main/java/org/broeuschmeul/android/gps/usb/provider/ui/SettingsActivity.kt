package org.broeuschmeul.android.gps.usb.provider.ui

import android.content.res.Configuration
import android.os.Bundle
import org.broeuschmeul.android.gps.usb.provider.R

/**
 * 設定画面のActivityです。
 * 大画面タブレットでかつ横画面（Landscape）の場合は自動的にダブルパネルで表示するため、
 * スマートフォン用の一画面のみの設定Activityは終了します。
 */
class SettingsActivity : USBGpsBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isDoublePanelAvailable()) {
            finish()
            return
        }

        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        showSettingsFragment(R.id.settings_content, true)
    }

    private fun isDoublePanelAvailable(): Boolean {
        val configuration = resources.configuration
        return (configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE &&
                configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
}
