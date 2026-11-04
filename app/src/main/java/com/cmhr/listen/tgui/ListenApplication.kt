package com.cmhr.listen.tgui

import android.app.Application
import android.content.res.Configuration
import org.telegram.ui.TelegramUI

class ListenApplication : Application() {
    override fun onCreate() { super.onCreate(); TelegramUI.init(this) }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        TelegramUI.onConfigurationChanged(newConfig)
    }
}
