package com.shadow.society.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Base64
import java.util.TreeMap

/**
 * One SMS per fragment:  TXT|uuid8|index|total|base64chunk
 * The joined Base64 decodes to [mode][data]: mode 0 = plain text, mode 1 = IV+AES-GCM ciphertext.
 */
object Codec {
    const val CHUNK = 120
    private val UUID = Regex("[0-9a-f]{8}")
    private val B64 = Regex("[A-Za-z0-9+/=]+")

    class Part(val uuid: String, val index: Int, val total: Int, val data: String)

    fun plain(text: String): ByteArray = byteArrayOf(0) + text.toByteArray()

    fun secure(key: ByteArray, text: String): ByteArray = byteArrayOf(1) + Crypto.encrypt(key, text.toByteArray())

    fun encode(payload: ByteArray): List<String> {
        val uuid = Crypto.hex(Crypto.random(4))
        val b64 = Base64.encodeToString(payload, Base64.NO_WRAP)
        val total = (b64.length + CHUNK - 1) / CHUNK
        return (0 until total).map { i ->
            "TXT|$uuid|$i|$total|" + b64.substring(i * CHUNK, minOf(b64.length, (i + 1) * CHUNK))
        }
    }

    fun estimateParts(bytes: Int): Int = maxOf(1, (4 * ((bytes + 2) / 3) + CHUNK - 1) / CHUNK)

    fun parse(sms: String?): Part? {
        if (sms == null || sms.length > 200) return null
        val f = sms.trim().split("|", limit = 5)
        if (f.size != 5 || f[0] != "TXT" || !UUID.matches(f[1]) || !B64.matches(f[4])) return null
        val i = f[2].toIntOrNull() ?: return null
        val t = f[3].toIntOrNull() ?: return null
        if (t < 1 || t > 999 || i < 0 || i >= t) return null
        return Part(f[1], i, t, f[4])
    }
}

object Sender {
    @Suppress("DEPRECATION")
    fun send(phone: String, payload: ByteArray) {
        val sm = SmsManager.getDefault()
        Codec.encode(payload).forEach { sm.sendTextMessage(phone, null, it, null, null) }
    }
}

/** Fragments that arrive while the vault is locked wait here (still in wire form). */
object Spool {
    private const val FILE = "spool.dat"

    @Synchronized fun add(c: Context, sender: String, time: Long, body: String) {
        if (Codec.parse(body) == null) return
        try {
            c.openFileOutput(FILE, Context.MODE_PRIVATE or Context.MODE_APPEND).bufferedWriter().use {
                it.write(sender.replace('\t', ' ') + "\t" + time + "\t" + body.trim() + "\n")
            }
        } catch (e: Exception) { }
    }

    @Synchronized fun readAll(c: Context): List<Array<String>> {
        val f = c.getFileStreamPath(FILE)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().mapNotNull {
                val p = it.split("\t", limit = 3)
                if (p.size == 3) arrayOf(p[0], p[1], p[2]) else null
            }
        } catch (e: Exception) { emptyList() }
    }

    @Synchronized fun writeAll(c: Context, rows: List<Array<String>>) {
        if (rows.isEmpty()) { clear(c); return }
        try {
            c.openFileOutput(FILE, Context.MODE_PRIVATE).bufferedWriter().use { w ->
                rows.forEach { w.write(it[0] + "\t" + it[1] + "\t" + it[2] + "\n") }
            }
        } catch (e: Exception) { }
    }

    @Synchronized fun clear(c: Context) { c.deleteFile(FILE) }
}

object Inbox {
    @Volatile var listener: Runnable? = null
    private val main = Handler(Looper.getMainLooper())

    fun notifyChanged() { listener?.let { main.post(it) } }

    /** Joins fragments into messages and stores them. Needs the vault unlocked. */
    @Synchronized fun drain(c: Context): Int {
        if (!SessionVault.isUnlocked()) return 0
        val rows = Spool.readAll(c)
        if (rows.isEmpty()) return 0

        val groups = LinkedHashMap<String, MutableList<Array<String>>>()
        for (r in rows) {
            val p = Codec.parse(r[2]) ?: continue
            groups.getOrPut(Phones.tail(r[0]) + "|" + p.uuid) { ArrayList() }.add(r)
        }

        val keep = ArrayList<Array<String>>()
        var stored = 0
        val now = System.currentTimeMillis()
        for (g in groups.values) {
            val first = Codec.parse(g[0][2]) ?: continue
            val byIdx = TreeMap<Int, String>()
            var oldest = Long.MAX_VALUE
            var newest = 0L
            for (r in g) {
                val p = Codec.parse(r[2])
                if (p != null && p.total == first.total) byIdx[p.index] = p.data
                val t = r[1].toLongOrNull() ?: now
                oldest = minOf(oldest, t)
                newest = maxOf(newest, t)
            }
            if (byIdx.size == first.total) {
                if (deliver(c, g[0][0], newest, byIdx.values.joinToString(""))) stored++
            } else if (now - oldest < 24L * 3600 * 1000) {
                keep.addAll(g)
            }
        }
        Spool.writeAll(c, keep)
        return stored
    }

    private fun deliver(c: Context, sender: String, time: Long, b64: String): Boolean {
        val data = try { Base64.decode(b64, Base64.NO_WRAP) } catch (e: IllegalArgumentException) { return false }
        if (data.size < 2) return false
        var ct = Contacts.byPhone(c, sender)
        if (ct == null) {
            ct = Contact(0, sender, sender, 0, time)
            ct.id = Contacts.insert(c, ct)
            if (ct.id < 0) return false
        }
        val cipher: Boolean
        val body: String
        when (data[0].toInt()) {
            0 -> { cipher = false; body = String(data, 1, data.size - 1) }
            1 -> { cipher = true; body = Base64.encodeToString(data, 1, data.size - 1, Base64.NO_WRAP) }
            else -> return false
        }
        Messages.insert(c, Msg(0, ct.id, body, false, time, false, cipher))
        return true
    }
}

/** Only Sh-So wire-format SMS are touched. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val bodies = LinkedHashMap<String, StringBuilder>()
        for (m in msgs) {
            val a = m?.originatingAddress ?: continue
            bodies.getOrPut(a) { StringBuilder() }.append(m.messageBody)
        }
        val now = System.currentTimeMillis()
        var any = false
        for ((a, sb) in bodies) {
            val body = sb.toString()
            if (Codec.parse(body) != null) { Spool.add(ctx, a, now, body); any = true }
        }
        if (!any) return
        val pr = goAsync()
        Thread {
            try {
                if (SessionVault.isUnlocked()) { Inbox.drain(ctx); Inbox.notifyChanged() }
                Notifier.newMessage(ctx)
            } finally { pr.finish() }
        }.start()
    }
}
