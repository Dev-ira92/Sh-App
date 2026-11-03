package com.shadow.society.app

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Where to drop a message for someone: server + inbox + drop token. */
class Addr(val u: String, val i: String, val d: String) {
    fun toLine(): String = "SHSOADDR1|$u|$i|$d"
    fun toJson(): String = JSONObject().put("app", "Sh-So").put("type", "addr").put("v", 1)
        .put("u", u).put("i", i).put("d", d).toString(2)
}

/** Invite to a shared server: URL + server key. */
class ServerInfo(val u: String, val k: String) {
    fun toLine(): String = "SHSOSRV1|$u|$k"
    fun toJson(): String = JSONObject().put("app", "Sh-So").put("type", "server").put("v", 1)
        .put("u", u).put("k", k).toString(2)
}

object OnlineParse {
    private val ID = Regex("[0-9a-f]{32}")
    private val TOK = Regex("[0-9a-f]{48}")
    private val KEY = Regex("[^\\s|]{8,200}")

    fun cleanUrl(s: String): String? {
        val u = s.trim().trimEnd('/')
        if (!u.startsWith("https://") || u.length < 12) return null
        if (u.any { it.isWhitespace() || it == '|' }) return null
        return u
    }

    private fun fields(text: String, tag: String, jsonType: String, keys: List<String>): List<String>? {
        val t = text.trim()
        if (t.startsWith("{")) {
            return try {
                val j = JSONObject(t)
                if (j.optString("type") != jsonType) null else keys.map { j.optString(it) }
            } catch (e: Exception) { null }
        }
        for (l in t.lines()) {
            val x = l.trim()
            if (x.startsWith("$tag|")) {
                val p = x.split("|")
                return if (p.size == keys.size + 1) p.drop(1) else null
            }
        }
        return null
    }

    fun addr(text: String): Addr? {
        val f = fields(text, "SHSOADDR1", "addr", listOf("u", "i", "d")) ?: return null
        val u = cleanUrl(f[0]) ?: return null
        if (!ID.matches(f[1]) || !TOK.matches(f[2])) return null
        return Addr(u, f[1], f[2])
    }

    fun server(text: String): ServerInfo? {
        val f = fields(text, "SHSOSRV1", "server", listOf("u", "k")) ?: return null
        val u = cleanUrl(f[0]) ?: return null
        if (!KEY.matches(f[1])) return null
        return ServerInfo(u, f[1])
    }
}

object Net {
    class Res(val code: Int, val body: String)

    fun request(method: String, url: String, headers: Map<String, String>, body: String?): Res {
        var conn: HttpURLConnection? = null
        return try {
            val cn = URL(url).openConnection() as HttpURLConnection
            conn = cn
            cn.requestMethod = method
            cn.connectTimeout = 15000
            cn.readTimeout = 60000
            cn.instanceFollowRedirects = false
            for ((k, v) in headers) cn.setRequestProperty(k, v)
            if (body != null) {
                val b = body.toByteArray()
                cn.doOutput = true
                cn.setRequestProperty("Content-Type", "application/json")
                cn.setFixedLengthStreamingMode(b.size)
                cn.outputStream.use { it.write(b) }
            }
            val code = cn.responseCode
            val s = if (code in 200..299) cn.inputStream else cn.errorStream
            Res(code, s?.bufferedReader()?.use { it.readText() } ?: "")
        } catch (e: Exception) {
            Res(-1, e.message ?: "network error")
        } finally {
            conn?.disconnect()
        }
    }
}

/** Online mode: my inbox on a Cloudflare Worker, contacts' drop addresses, sending and polling. */
object Online {
    const val MAX_B64 = 1_400_000
    const val MAX_BYTES = 1_000_000     // payload size the internal server accepts
    private const val PAGE = 2

    class Mine(val url: String, val inbox: String, val read: String, val drop: String)

    // ---------- storage (inside the encrypted database) ----------
    fun meta(c: Context, k: String): String? = Db.get(c).getMeta(k)?.takeIf { it.isNotEmpty() }
    fun setMeta(c: Context, k: String, v: String) { Db.get(c).setMeta(k, v) }

    fun host(u: String): String = Uri.parse(u).host ?: u

    fun mine(c: Context): Mine? {
        val u = meta(c, "ol_url") ?: return null
        val i = meta(c, "ol_inbox") ?: return null
        val r = meta(c, "ol_read") ?: return null
        val d = meta(c, "ol_drop") ?: return null
        return Mine(u, i, r, d)
    }

    fun myAddr(c: Context): Addr? = mine(c)?.let { Addr(it.url, it.inbox, it.drop) }

    fun saveServer(c: Context, url: String, key: String) {
        if (meta(c, "ol_url") != url) {
            setMeta(c, "ol_inbox", "")
            setMeta(c, "ol_read", "")
            setMeta(c, "ol_drop", "")
            setMeta(c, "ol_seq", "0")
        }
        setMeta(c, "ol_url", url)
        setMeta(c, "ol_skey", key)
    }

    fun reset(c: Context) {
        for (k in listOf("ol_url", "ol_skey", "ol_inbox", "ol_read", "ol_drop", "ol_seq")) setMeta(c, k, "")
    }

    fun addrOf(c: Context, cid: Long): Addr? = meta(c, "olc_$cid")?.let { OnlineParse.addr(it) }
    fun saveAddr(c: Context, cid: Long, a: Addr) { setMeta(c, "olc_$cid", a.toJson()) }
    fun clearAddr(c: Context, cid: Long) { setMeta(c, "olc_$cid", "") }

    private fun sha(s: String): String = Crypto.hex(Crypto.sha256(s.toByteArray()))

    fun err(r: Net.Res): String = when (r.code) {
        -1 -> tr("No connection: ", "اتصال برقرار نشد: ") + r.body
        401, 403 -> tr("Wrong key or token", "کلید یا توکن اشتباه است")
        404 -> tr("Not found (is the Worker deployed and the database ready?)", "پیدا نشد (Worker و دیتابیس را بررسی کن)")
        413 -> tr("Too big for the server", "برای سرور بزرگ است")
        else -> "HTTP " + r.code
    }

    // ---------- inbox ----------
    /** Creates (or renews) my inbox on the server. Returns an error text, or null on success. Run off the main thread. */
    fun createInbox(c: Context): String? {
        val url = meta(c, "ol_url") ?: return tr("Set the server first", "اول سرور را تنظیم کن")
        val key = meta(c, "ol_skey") ?: return tr("Set the server key first", "اول کلید سرور را وارد کن")
        val inbox = Crypto.hex(Crypto.random(16))
        val read = Crypto.hex(Crypto.random(24))
        val drop = Crypto.hex(Crypto.random(24))
        val body = JSONObject().put("read", sha(read)).put("drop", sha(drop)).toString()
        val r = Net.request("POST", "$url/setup/$inbox", mapOf("X-Server-Key" to key), body)
        if (r.code != 200) return err(r)
        setMeta(c, "ol_inbox", inbox)
        setMeta(c, "ol_read", read)
        setMeta(c, "ol_drop", drop)
        setMeta(c, "ol_seq", "0")
        return null
    }

    // ---------- sending ----------
    fun drop(a: Addr, from: String, ts: Long, payload: ByteArray): String? {
        val data = Base64.encodeToString(payload, Base64.NO_WRAP)
        if (data.length > MAX_B64) return tr("Too big for the internal server", "برای سرور داخلی بزرگ است")
        val body = JSONObject().put("from", from).put("ts", ts).put("data", data).toString()
        val r = Net.request("POST", a.u + "/drop/" + a.i, mapOf("X-Drop" to a.d), body)
        return if (r.code == 200) null else err(r)
    }

    /** Sends a payload ([mode][data]) to a contact's inbox. Returns an error text, or null. */
    fun send(c: Context, cid: Long, payload: ByteArray, ts: Long): String? {
        val a = addrOf(c, cid) ?: return tr("This contact has no online address", "این مخاطب آدرس آنلاین ندارد")
        val m = mine(c) ?: return tr("Online is not set up", "حالت آنلاین تنظیم نشده")
        return drop(a, m.inbox, ts, payload)
    }

    // ---------- receiving ----------
    private fun contactByInbox(c: Context, from: String): Contact? {
        val mapped = meta(c, "olfrom_$from")?.toLongOrNull()
        if (mapped != null) {
            val ct = Contacts.byId(c, mapped)
            if (ct != null) return ct
        }
        return Contacts.all(c).firstOrNull { addrOf(c, it.id)?.i == from }
    }

    private fun deliver(c: Context, from: String, ts0: Long, b64: String): Boolean {
        val data = try { Base64.decode(b64, Base64.NO_WRAP) } catch (e: IllegalArgumentException) { return false }
        if (data.size < 2) return false
        val ts = if (ts0 > 0) ts0 else System.currentTimeMillis()
        var ct = contactByInbox(c, from)
        if (ct == null) {
            val phone = "ol:" + from.take(12)
            ct = Contact(0, "Online " + from.take(6), phone, 0, ts)
            ct.id = Contacts.insert(c, ct)
            if (ct.id < 0) {
                ct = Contacts.all(c).firstOrNull { it.phone == phone }
                if (ct == null) return false
            }
            setMeta(c, "olfrom_$from", ct.id.toString())
        }
        val mode = data[0].toInt()
        val payload = data.copyOfRange(1, data.size)
        val cipher: Boolean
        val body: String
        var tag = 0
        when (mode) {
            0 -> { cipher = false; body = String(payload) }
            1 -> { cipher = true; body = Base64.encodeToString(payload, Base64.NO_WRAP) }
            2, 3 -> {
                cipher = mode == 3
                val st = Media.store(c, payload)
                body = st.first
                tag = 4 or st.second
            }
            else -> return false
        }
        val m = Msg(0, ct.id, body, false, ts, false, cipher, 1, tag)
        if ((tag and 8) == 0 && Messages.exists(c, m)) return false
        Messages.insert(c, m)
        return true
    }

    /** Fetches new messages from my inbox. Returns how many were stored. Run off the main thread. */
    fun pollOnce(c: Context): Int {
        val m = mine(c) ?: return 0
        var total = 0
        var rounds = 0
        while (rounds < 20) {
            rounds++
            val after = meta(c, "ol_seq")?.toLongOrNull() ?: 0L
            val r = Net.request("GET", m.url + "/poll/" + m.inbox + "?after=" + after, mapOf("X-Read" to m.read), null)
            if (r.code != 200) break
            val arr = try { JSONObject(r.body).getJSONArray("msgs") } catch (e: Exception) { break }
            if (arr.length() == 0) break
            var maxSeq = after
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                val seq = o.optLong("seq", 0)
                try {
                    if (deliver(c, o.optString("from"), o.optLong("ts", 0), o.optString("data"))) total++
                } catch (e: Exception) { }
                if (seq > maxSeq) maxSeq = seq
            }
            if (maxSeq <= after) break
            setMeta(c, "ol_seq", maxSeq.toString())
            Net.request("POST", m.url + "/ack/" + m.inbox, mapOf("X-Read" to m.read),
                JSONObject().put("upto", maxSeq).toString())
            if (arr.length() < PAGE) break
        }
        if (total > 0) Inbox.notifyChanged()
        return total
    }

    // ---------- files ----------
    /** Raw text from a file, or the text inside a QR picture. */
    fun readAny(c: Context, uri: Uri): String? {
        val type = c.contentResolver.getType(uri) ?: ""
        if (type.startsWith("image/")) {
            val bmp = (try {
                c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            } catch (t: Throwable) { null }) ?: return null
            return QrTools.decode(bmp)
        }
        return Media.readText(c, uri)
    }

    fun exportText(c: Context, name: String, content: String): File {
        val d = File(c.cacheDir, "exports")
        d.deleteRecursively()
        d.mkdirs()
        val f = File(d, name)
        f.writeText(content)
        return f
    }
}

/** Polls my inbox every 15 seconds while a screen is open. */
object OnlinePoll {
    @Volatile private var gen = 0

    fun start(c: Context) {
        val app = c.applicationContext
        gen++
        val my = gen
        Thread {
            while (gen == my && SessionVault.isUnlocked()) {
                try { Online.pollOnce(app) } catch (e: Exception) { }
                var w = 0
                while (w < 15 && gen == my) {
                    try { Thread.sleep(1000) } catch (e: InterruptedException) { }
                    w++
                }
            }
        }.start()
    }

    fun stop() { gen++ }
}
