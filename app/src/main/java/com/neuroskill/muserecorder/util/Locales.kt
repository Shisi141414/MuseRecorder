package com.neuroskill.muserecorder.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate

/**
 * 取按应用内语言设置本地化的 Context。
 * API 33+ 系统按应用语言自动本地化 app 资源；旧版本由 AppCompat 存储，
 * 需手动套用配置，保证后台/服务层文案与界面语言一致。
 */
object Locales {
    fun ctx(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        return try {
            val locales = AppCompatDelegate.getApplicationLocales()
            val first = locales.get(0)
            if (locales.isEmpty || first == null) base
            else {
                val config = Configuration(base.resources.configuration)
                config.setLocale(first)
                base.createConfigurationContext(config)
            }
        } catch (_: Exception) {
            base
        }
    }
}
