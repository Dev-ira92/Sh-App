package com.shadow.society.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.scottyab.rootbeer.RootBeer
import java.util.Locale

/** Persian by default, English if the phone language is English. */
fun tr(en: String, fa: String): String = if (Locale.getDefault().language == "en") en else fa

object Prefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("shso", Context.MODE_PRIVATE)
    fun str(c: Context, k: String): String? = sp(c).getString(k, null)
    fun putStr(c: Context, k: String, v: String) { sp(c).edit().putString(k, v).commit() }
    fun int(c: Context, k: String, d: Int): Int = sp(c).getInt(k, d)
    fun putInt(c: Context, k: String, v: Int) { sp(c).edit().putInt(k, v).commit() }
    fun long(c: Context, k: String, d: Long): Long = sp(c).getLong(k, d)
    fun putLong(c: Context, k: String, v: Long) { sp(c).edit().putLong(k, v).commit() }
    fun clear(c: Context) { sp(c).edit().clear().commit() }
}

object Phones {
    fun digits(s: String?): String {
        val sb = StringBuilder()
        s?.forEach { val d = Character.digit(it, 10); if (d >= 0) sb.append(d) }
        return sb.toString()
    }
    fun tail(s: String?): String { val d = digits(s); return if (d.length > 9) d.substring(d.length - 9) else d }
    fun same(a: String?, b: String?): Boolean { val x = tail(a); return x.isNotEmpty() && x == tail(b) }
}

object Perms {
    private fun ok(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
    fun hasSend(c: Context) = ok(c, Manifest.permission.SEND_SMS)
    fun request(a: Activity) {
        val need = ArrayList<String>()
        if (!ok(a, Manifest.permission.SEND_SMS)) need.add(Manifest.permission.SEND_SMS)
        if (!ok(a, Manifest.permission.RECEIVE_SMS)) need.add(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= 33 && !ok(a, Manifest.permission.POST_NOTIFICATIONS)) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(a, need.toTypedArray(), 7)
    }
}

/** Swaps launcher icon/name to "Calculator". */
object Lockdown {
    private fun cn(c: Context, n: String) = ComponentName(c.packageName, "com.shadow.society.app.$n")
    fun isOn(c: Context) =
        c.packageManager.getComponentEnabledSetting(cn(c, "CalcAlias")) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    fun set(c: Context, on: Boolean) {
        val pm = c.packageManager
        pm.setComponentEnabledSetting(cn(c, if (on) "CalcAlias" else "NormalAlias"),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(cn(c, if (on) "NormalAlias" else "CalcAlias"),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
}

/** Notifications never reveal sender or content. */
object Notifier {
    fun newMessage(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel("shso", "Signals", NotificationManager.IMPORTANCE_DEFAULT)
            ch.lockscreenVisibility = Notification.VISIBILITY_SECRET
            (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, "shso")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("> _")
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { NotificationManagerCompat.from(c).notify(1, n) } catch (e: SecurityException) { }
    }
}

object Qr {
    fun render(text: String, px: Int): Bitmap? = try {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8")
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, hints)
        val w = m.width
        val h = m.height
        val black = 0xFF000000.toInt()
        val green = 0xFF00FF41.toInt()
        val pix = IntArray(w * h) { i -> if (m.get(i % w, i / w)) black else green }
        Bitmap.createBitmap(pix, w, h, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) { null }
}

object Root {
    fun isRooted(c: Context): Boolean = try { RootBeer(c).isRooted } catch (t: Throwable) { false }
}
