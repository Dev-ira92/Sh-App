package com.shadow.society.app

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A server address = URL + server key, written as one text so it can be shared by QR / file / text. */
class ServerInfo(val u: String, val k: String) {
    fun toLine(): String = "SHSOSRV1|$u|$k"
    fun toJson(): String = JSONObject().put("app", "Sh-So").put("type", "server").put("v", 1)
        .put("u", u).put("k", k).toString(2)
}

object OnlineParse {
    private val KEY = Regex("[^\\s|]{8,200}")

    fun cleanUrl(s: String): String? {
        val u = s.trim().trimEnd('/')
        if (!u.startsWith("https://") || u.length < 12) return null
        if (u.any { it.isWhitespace() || it == '|' }) return null
        return u
    }

    fun server(text: String): ServerInfo? {
        val t = text.trim()
        var u: String? = null
        var k: String? = null
        if (t.startsWith("{")) {
            try {
                val j = JSONObject(t)
                if (j.optString("type") != "server") return null
                u = j.optString("u")
                k = j.optString("k")
            } catch (e: Exception) { return null }
        } else {
            for (l in t.lines()) {
                val x = l.trim()
                if (x.startsWith("SHSOSRV1|")) {
                    val p = x.split("|")
                    if (p.size == 3) { u = p[1]; k = p[2] }
                    break
                }
            }
        }
        val cu = cleanUrl(u ?: return null) ?: return null
        val kk = k ?: return null
        if (!KEY.matches(kk)) return null
        return ServerInfo(cu, kk)
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

/**
 * Online mode. Your phone number is your ID: inbox id = SHA-256("shso|" + last 9 digits).
 * Shared server: everyone registers on the same server. Dedicated: each person has their own server and
 * you save your friend's server address in his contact.
 */
object Online {
    const val MAX_PAYLOAD = 8_100_000
    private const val CHUNK = 1_000_000
    private const val PAGE = 2
    private val HEX64 = Regex("[0-9a-f]{64}")

    // ---------- storage (inside the encrypted database) ----------
    fun meta(c: Context, k: String): String? = Db.get(c).getMeta(k)?.takeIf { it.isNotEmpty() }
    fun setMeta(c: Context, k: String, v: String) { Db.get(c).setMeta(k, v) }
    fun host(u: String): String = Uri.parse(u).host ?: u
    private fun sha(s: String): String = Crypto.hex(Crypto.sha256(s.toByteArray()))

    // ---------- identity ----------
    fun myPhone(c: Context): String? = meta(c, "ol_phone")
    fun setMyPhone(c: Context, p: String) { setMeta(c, "ol_phone", p) }

    fun inboxOfPhone(p: String): String? {
        if (p.startsWith("ol:")) {
            val h = p.substring(3)
            return if (HEX64.matches(h)) h else null
        }
        val t = Phones.tail(p)
        return if (t.length < 6) null else sha("shso|$t")
    }

    fun myInbox(c: Context): String? = myPhone(c)?.let { inboxOfPhone(it) }

    // ---------- servers ----------
    fun myServer(c: Context): ServerInfo? {
        val u = meta(c, "ol_url") ?: return null
        val k = meta(c, "ol_skey") ?: return null
        return ServerInfo(u, k)
    }

    fun saveServer(c: Context, s: ServerInfo) {
        setMeta(c, "ol_url", s.u)
        setMeta(c, "ol_skey", s.k)
    }

    fun contactServer(c: Context, cid: Long): ServerInfo? = meta(c, "srv_$cid")?.let { OnlineParse.server(it) }
    fun setContactServer(c: Context, cid: Long, s: ServerInfo) { setMeta(c, "srv_$cid", s.toJson()) }
    fun clearContactServer(c: Context, cid: Long) { setMeta(c, "srv_$cid", "") }
    private fun serverFor(c: Context, cid: Long): ServerInfo? = contactServer(c, cid) ?: myServer(c)

    fun ready(c: Context): Boolean {
        val s = myServer(c) ?: return false
        return myInbox(c) != null && meta(c, "ol_read") != null && meta(c, "ol_read_url") == s.u
    }

    fun forgetServer(c: Context) {
        for (k in listOf("ol_url", "ol_skey")) setMeta(c, k, "")
    }

    fun err(r: Net.Res): String = when (r.code) {
        -1 -> tr("No connection: ", "اتصال برقرار نشد: ") + r.body
        401, 403 -> tr("Wrong server key", "کلید سرور اشتباه است")
        404 -> tr("Not found (server not ready, or your friend is not registered there)", "پیدا نشد (سرور آماده نیست یا دوستت آنجا ثبت‌نام نکرده)")
        409 -> tr("This number is already registered on this server by another installation. Ask the server owner to delete it (SQL: DELETE FROM inboxes).",
            "این شماره روی این سرور با نصب دیگری ثبت شده. از صاحب سرور بخواه پاکش کند (SQL: DELETE FROM inboxes).")
        413 -> tr("Too big for the server", "برای سرور بزرگ است")
        else -> "HTTP " + r.code
    }

    /** Registers my number on my server. Returns an error text, or null. Run off the main thread. */
    fun register(c: Context): String? {
        val inbox = myInbox(c) ?: return tr("Enter your phone number first", "اول شماره‌ی خودت را وارد کن")
        val s = myServer(c) ?: return tr("Set the server first", "اول سرور را تنظیم کن")
        val tok = if (meta(c, "ol_read_url") == s.u) (meta(c, "ol_read") ?: Crypto.hex(Crypto.random(24))) else Crypto.hex(Crypto.random(24))
        val body = JSONObject().put("read", sha(tok)).toString()
        val r = Net.request("POST", s.u + "/reg/" + inbox, mapOf("X-Key" to s.k, "X-Read" to tok), body)
        if (r.code != 200) return err(r)
        setMeta(c, "ol_read", tok)
        setMeta(c, "ol_read_url", s.u)
        setMeta(c, "ol_seq", if (meta(c, "ol_seq") == null) "0" else meta(c, "ol_seq")!!)
        return null
    }

    // ---------- sending ----------
    /** Sends [mode][data] straight to the contact's inbox (his phone number is the address). Run off the main thread. */
    fun sendTo(c: Context, ct: Contact, payload: ByteArray, ts: Long): String? {
        val to = inboxOfPhone(ct.phone) ?: return tr("This contact has no valid phone number", "شماره‌ی این مخاطب معتبر نیست")
        val from = myInbox(c) ?: return tr("Enter your phone number in Settings → Online first", "اول شماره‌ات را در تنظیمات ← آنلاین وارد کن")
        val s = serverFor(c, ct.id) ?: return tr("Set the server first", "اول سرور را تنظیم کن")
        if (payload.size > MAX_PAYLOAD) return tr("Too big for the internal server", "برای سرور داخلی بزرگ است")
        val b64 = Base64.encodeToString(payload, Base64.NO_WRAP)
        val mid = Crypto.hex(Crypto.random(8))
        val total = (b64.length + CHUNK - 1) / CHUNK
        for (i in 0 until total) {
            val part = b64.substring(i * CHUNK, minOf(b64.length, (i + 1) * CHUNK))
            val body = JSONObject().put("from", from).put("mid", mid).put("idx", i).put("total", total)
                .put("ts", ts).put("data", part).toString()
            val r = Net.request("POST", s.u + "/drop/" + to, mapOf("X-Key" to s.k), body)
            if (r.code != 200) return err(r)
        }
        return null
    }

    // ---------- receiving ----------
    private class Chunk(val seq: Long, val from: String, val mid: String, val idx: Int, val total: Int, val ts: Long, val data: String)

    private fun seen(c: Context, mid: String): Boolean = (meta(c, "olp") ?: "").split(",").contains(mid)

    private fun markSeen(c: Context, mid: String) {
        val l = (meta(c, "olp") ?: "").split(",").filter { it.isNotEmpty() }.takeLast(199) + mid
        setMeta(c, "olp", l.joinToString(","))
    }

    private fun deliver(c: Context, from: String, ts0: Long, b64: String): Boolean {
        val data = try { Base64.decode(b64, Base64.NO_WRAP) } catch (e: IllegalArgumentException) { return false }
        if (data.size < 2) return false
        val ts = if (ts0 > 0) ts0 else System.currentTimeMillis()
        var ct = Contacts.all(c).firstOrNull { inboxOfPhone(it.phone) == from }
        if (ct == null) {
            val phone = "ol:$from"
            ct = Contact(0, "Online " + from.take(6), phone, 1, ts)
            ct.id = Contacts.insert(c, ct)
            if (ct.id < 0) {
                ct = Contacts.all(c).firstOrNull { it.phone == phone }
                if (ct == null) return false
            }
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
        val inbox = myInbox(c) ?: return 0
        val s = myServer(c) ?: return 0
        val tok = meta(c, "ol_read") ?: return 0
        if (meta(c, "ol_read_url") != s.u) return 0
        val after = meta(c, "ol_seq")?.toLongOrNull() ?: 0L
        var cur = after
        val chunks = ArrayList<Chunk>()
        var rounds = 0
        while (rounds < 60) {
            rounds++
            val r = Net.request("GET", s.u + "/poll/" + inbox + "?after=" + cur, mapOf("X-Read" to tok), null)
            if (r.code != 200) return 0
            val arr = try { JSONObject(r.body).getJSONArray("msgs") } catch (e: Exception) { return 0 }
            if (arr.length() == 0) break
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                val seq = o.optLong("seq", 0)
                chunks.add(Chunk(seq, o.optString("from"), o.optString("mid"), o.optInt("idx"),
                    o.optInt("total", 1), o.optLong("ts", 0), o.optString("data")))
                if (seq > cur) cur = seq
            }
            if (arr.length() < PAGE) break
        }
        if (chunks.isEmpty()) return 0

        val groups = LinkedHashMap<String, MutableList<Chunk>>()
        for (ch in chunks) groups.getOrPut(ch.from + "|" + ch.mid) { ArrayList() }.add(ch)
        var total = 0
        var minIncomplete = Long.MAX_VALUE
        val now = System.currentTimeMillis()
        for (g in groups.values) {
            val first = g[0]
            if (first.total < 1 || first.total > 20) continue
            val byIdx = HashMap<Int, String>()
            for (ch in g) byIdx[ch.idx] = ch.data
            if ((0 until first.total).all { byIdx.containsKey(it) }) {
                if (!seen(c, first.mid)) {
                    val sb = StringBuilder()
                    for (i in 0 until first.total) sb.append(byIdx[i])
                    try { if (deliver(c, first.from, first.ts, sb.toString())) total++ } catch (e: Exception) { }
                    markSeen(c, first.mid)
                }
            } else if (first.ts == 0L || now - first.ts < 15 * 60 * 1000L) {
                minIncomplete = minOf(minIncomplete, g.minOf { it.seq })   // still arriving: keep it on the server
            }
        }
        val upto = if (minIncomplete == Long.MAX_VALUE) cur else minIncomplete - 1
        if (upto > after) {
            setMeta(c, "ol_seq", upto.toString())
            Net.request("POST", s.u + "/ack/" + inbox, mapOf("X-Read" to tok), JSONObject().put("upto", upto).toString())
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

    /** Converts an exported .txt (SHSO1 lines) into a .json file next to it. */
    fun txtToJson(f: File): File {
        val arr = JSONArray()
        f.forEachLine { line ->
            if (line.startsWith("SHSO1|")) {
                val p = line.split("|", limit = 4)
                if (p.size == 4) arr.put(JSONObject().put("ts", p[1].toLongOrNull() ?: 0L).put("dir", p[2]).put("data", p[3].trim()))
            }
        }
        val j = JSONObject().put("app", "Sh-So").put("type", "messages").put("v", 1).put("items", arr)
        val out = File(f.parentFile, f.nameWithoutExtension + ".json")
        out.writeText(j.toString())
        return out
    }

    /** If the imported text is a Sh-So JSON file, turns it into SHSO1 lines. */
    fun normalizeImport(text: String): String {
        val t = text.trimStart()
        if (!t.startsWith("{")) return text
        return try {
            val arr = JSONObject(t).optJSONArray("items") ?: return text
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                sb.append("SHSO1|").append(o.optLong("ts")).append("|").append(o.optString("dir"))
                    .append("|").append(o.optString("data")).append("\n")
            }
            sb.toString()
        } catch (e: Exception) { text }
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
