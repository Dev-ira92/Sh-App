package com.shadow.society.app

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** SIM choice: a default in Settings, and an optional override for each chat. -1 = system default. */
object Sims {
    const val INHERIT = Int.MIN_VALUE

    class Sim(val subId: Int, val label: String)

    fun has(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun list(c: Context): List<Sim> = try {
        val sm = c.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        (sm.activeSubscriptionInfoList ?: emptyList()).map {
            Sim(it.subscriptionId, "SIM " + (it.simSlotIndex + 1) + " · " + it.carrierName)
        }
    } catch (e: SecurityException) { emptyList() }

    fun current(c: Context, contactId: Long): Int {
        val v = Prefs.int(c, "sim_c$contactId", INHERIT)
        return if (v != INHERIT) v else Prefs.int(c, "sim", -1)
    }

    fun label(c: Context, subId: Int): String {
        if (subId == -1) return tr("system default", "پیش‌فرض سیستم")
        if (has(c)) {
            val s = list(c).firstOrNull { it.subId == subId }
            if (s != null) return s.label
        }
        return "SIM #$subId"
    }

    fun choose(a: Activity, prefKey: String, allowInherit: Boolean, done: () -> Unit) {
        if (!has(a)) {
            ActivityCompat.requestPermissions(a, arrayOf(Manifest.permission.READ_PHONE_STATE), 8)
            Toast.makeText(a, tr("Allow the permission, then tap again", "اجازه را بده، بعد دوباره بزن"), Toast.LENGTH_LONG).show()
            return
        }
        val labels = ArrayList<String>()
        val ids = ArrayList<Int>()
        if (allowInherit) {
            labels.add(tr("Same as the default", "مثل پیش‌فرض"))
            ids.add(INHERIT)
        }
        labels.add(tr("System default", "پیش‌فرض سیستم"))
        ids.add(-1)
        for (s in list(a)) {
            labels.add(s.label)
            ids.add(s.subId)
        }
        Dialogs.choose(a, tr("SIM for sending", "سیم‌کارت ارسال"), labels.toTypedArray(), -1) { w ->
            Prefs.putInt(a, prefKey, ids[w])
            done()
        }
    }
}

/** Same as Sender, but through the chosen SIM. Status results go to SmsStatusReceiver. */
object SimSender {
    @Suppress("DEPRECATION")
    private fun manager(subId: Int): SmsManager =
        if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()

    fun send(c: Context, phone: String, payload: ByteArray, msgId: Long, subId: Int) {
        val sm = manager(subId)
        val parts = Codec.encode(payload)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        parts.forEachIndexed { i, p ->
            val code = (msgId * 1000 + i).toInt()
            val sentPi = PendingIntent.getBroadcast(c, code,
                Intent(c, SmsStatusReceiver::class.java).setAction(Sender.ACTION_SENT)
                    .putExtra("id", msgId).putExtra("total", parts.size), flags)
            val delPi = if (i == parts.size - 1) PendingIntent.getBroadcast(c, code + 500000,
                Intent(c, SmsStatusReceiver::class.java).setAction(Sender.ACTION_DELIVERED)
                    .putExtra("id", msgId).putExtra("total", 1), flags) else null
            sm.sendTextMessage(phone, null, p, sentPi, delPi)
        }
    }
}
