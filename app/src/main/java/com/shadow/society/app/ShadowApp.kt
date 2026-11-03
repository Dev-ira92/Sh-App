package com.shadow.society.app

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager

/**
 * App-wide policy: theme load, FLAG_SECURE, and two separate timers after you leave the app:
 *  - auto-lock: the database locks (login screen again)
 *  - key clearing: session keys are destroyed (when leaving / 10 min / 30 min / never),
 *    or kept inside the encrypted vault (and restored after login) if you chose that in Settings.
 */
class ShadowApp : Application(), Application.ActivityLifecycleCallbacks {
    private var started = 0
    private var leftAt = 0L
    private val h = Handler(Looper.getMainLooper())
    private val lockTask = Runnable { if (started == 0) SessionVault.lock() }
    private val keyTask = Runnable { if (started == 0) SessionVault.wipeKeys() }

    private fun autoLockSec(): Int = Prefs.int(this, "autolock", 60)

    override fun onCreate() {
        super.onCreate()
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
            val km = KeyPolicy.ramMinutes(this)
            if (km >= 0 && away > km * 60000L) SessionVault.wipeKeys()
        }
        started++
        h.removeCallbacks(lockTask)
        h.removeCallbacks(keyTask)
    }

    override fun onActivityStopped(a: Activity) {
        started--
        if (started <= 0 && !a.isChangingConfigurations) {
            started = 0
            leftAt = SystemClock.elapsedRealtime()
            val s = autoLockSec()
            if (s <= 0) SessionVault.lock() else h.postDelayed(lockTask, s * 1000L)
            val km = KeyPolicy.ramMinutes(this)
            if (km == 0) SessionVault.wipeKeys() else if (km > 0) h.postDelayed(keyTask, km * 60000L)
        }
    }

    override fun onActivityResumed(a: Activity) {
        SessionVault.restoreIfNeeded(this)
        if (Lockdown.isOn(this)) {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { it.setExcludeFromRecents(true) }
        }
    }

    override fun onActivityPaused(a: Activity) { }
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) { }
    override fun onActivityDestroyed(a: Activity) { }
}
