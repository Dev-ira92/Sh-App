package com.shadow.society.app

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager

/** App-wide policy: FLAG_SECURE everywhere, auto-lock after leaving the app, hide from Recents in Lockdown. */
class ShadowApp : Application(), Application.ActivityLifecycleCallbacks {
    private var started = 0
    private var leftAt = 0L
    private val h = Handler(Looper.getMainLooper())
    private val lockTask = Runnable { if (started == 0) SessionVault.lock() }

    private fun autoLockSec(): Int = Prefs.int(this, "autolock", 60)

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) {
        a.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onActivityStarted(a: Activity) {
        if (started == 0 && leftAt > 0 && SystemClock.elapsedRealtime() - leftAt > autoLockSec() * 1000L) {
            SessionVault.lock()
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
