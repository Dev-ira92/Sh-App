package com.shadow.society.app

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import java.util.Locale

/**
 * App-wide policy: defaults (SMS theme, system language), FLAG_SECURE, auto-lock after leaving the app,
 * and the Calculator disguise. Session keys are NOT cleared here: they follow the "session keys" setting.
 */
class ShadowApp : Application(), Application.ActivityLifecycleCallbacks {
    private var started = 0
    private var leftAt = 0L
    private val h = Handler(Looper.getMainLooper())
    private val lockTask = Runnable { if (started == 0) SessionVault.lock() }

    private fun autoLockSec(): Int = Prefs.int(this, "autolock", 60)

    override fun onCreate() {
        super.onCreate()
        if (Prefs.str(this, "theme") == null) Prefs.putStr(this, "theme", "sms")      // default theme: Mobile SMS
        if (Prefs.str(this, "lang") == null) Prefs.putInt(this, "lang_sys", 1)         // default: system language
        if (Prefs.int(this, "lang_sys", 0) == 1) {
            Prefs.putStr(this, "lang", if (Locale.getDefault().language == "fa") "fa" else "en")
        }
        Neo.load(this)
        SessionVault.init(this)
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) {
        a.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onActivityStarted(a: Activity) {
        if (started == 0 && leftAt > 0) {
            val away = SystemClock.elapsedRealtime() - leftAt
            if (away > autoLockSec() * 1000L) SessionVault.lock()
        }
        started++
        h.removeCallbacks(lockTask)
    }

    override fun onActivityStopped(a: Activity) {
        started--
        if (started <= 0 && !a.isChangingConfigurations) {
            started = 0
            leftAt = SystemClock.elapsedRealtime()
            val s = autoLockSec()
            if (s <= 0) SessionVault.lock() else h.postDelayed(lockTask, s * 1000L)
        }
    }

    override fun onActivityResumed(a: Activity) {
        if (Lockdown.isOn(this)) {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { it.setExcludeFromRecents(true) }
        }
    }

    override fun onActivityPaused(a: Activity) { }
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) { }
    override fun onActivityDestroyed(a: Activity) { }
}
