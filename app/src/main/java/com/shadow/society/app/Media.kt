package com.shadow.society.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import android.util.Base64
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Encrypted file store for big images (the database cannot hold rows of several MB).
 * Files are AES-256-GCM encrypted with a random key that lives inside the encrypted database,
 * so wiping the database makes every file unreadable.
 */
object ImgStore {
    private val NAME = Regex("[0-9a-f]{16}\\.bin")

    private fun dir(c: Context): File {
        val d = File(c.filesDir, "img")
        d.mkdirs()
        return d
    }

    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun key(c: Context): ByteArray {
        val db = Db.get(c)
        val h = db.getMeta("img_key")
        if (h != null) return unhex(h)
        val k = Crypto.random(32)
        db.setMeta("img_key", Crypto.hex(k))
        return k
    }

    fun put(c: Context, data: ByteArray): String {
        val name = Crypto.hex(Crypto.random(8)) + ".bin"
        File(dir(c), name).writeBytes(Crypto.encrypt(key(c), data))
        return name
    }

    fun get(c: Context, name: String): ByteArray? {
        if (!NAME.matches(name)) return null
        return try { Crypto.decrypt(key(c), File(dir(c), name).readBytes()) } catch (e: Exception) { null }
    }

    /** Deletes files that no message refers to any more (only files older than 10 minutes). */
    fun sweep(c: Context) {
        try {
            val used = HashSet<String>()
            Db.get(c).db().rawQuery("SELECT body FROM messages WHERE (tag & 8) != 0", null).use { cur ->
                while (cur.moveToNext()) used.add(cur.getString(0))
            }
            val limit = System.currentTimeMillis() - 10 * 60 * 1000L
            dir(c).listFiles()?.forEach { if (!used.contains(it.name) && it.lastModified() < limit) it.delete() }
        } catch (e: Exception) { }
    }
}

/**
 * Images. Wire modes: 2 = plain image, 3 = IV + AES-256-GCM(image).
 * Message tag bits: 1 imported, 2 exported, 4 media (image / voice / video), 8 stored in the encrypted file store (body = file name),
 * 16 came through the internal server, 32 voice message, 64 video, 128 "already looked inside".
 * Voice and video use the same wire modes 2 / 3 as images; their data starts with the 7-byte header "SHMA" + kind + seconds.
 */
object Media {
    class Preset(val en: String, val fa: String, val side: Int, val bytes: Int, val smsOk: Boolean, val mode: Int = 0) {
        fun label(): String = tr(en, fa)
    }

    val PRESETS = listOf(
        Preset("Tiny", "خیلی کوچک", 192, 4000, true),
        Preset("Small", "کوچک", 320, 10000, true),
        Preset("Medium", "متوسط", 480, 20000, true),
        Preset("Large (TXT only)", "بزرگ (فقط فایل)", 960, 120000, false),
        Preset("Full size, location removed (TXT only)", "اندازه‌ی کامل، بدون موقعیت (فقط فایل)", 0, 0, false, 1),
        Preset("Original, exactly as taken (TXT only)", "اصلی، دقیقاً همان که گرفته‌ای (فقط فایل)", 0, 0, false, 2)
    )

    const val MAX_SMS_PARTS = 300
    const val MAX_BYTES = 8_000_000
    private const val INLINE_MAX = 400_000

    private fun dir(c: Context): File {
        val d = File(c.cacheDir, "exports")
        d.deleteRecursively()
        d.mkdirs()
        return d
    }

    private fun safe(name: String): String {
        val s = name.replace(Regex("[^A-Za-z0-9_-]"), "")
        return if (s.isEmpty()) "chat" else s
    }

    // ---------- storing / loading ----------
    fun store(c: Context, data: ByteArray): Pair<String, Int> =
        if (data.size > INLINE_MAX) Pair(ImgStore.put(c, data), 8)
        else Pair(Base64.encodeToString(data, Base64.NO_WRAP), 0)

    /** The stored bytes (plain image, or IV+ciphertext for a Fire image). */
    fun rawBytes(c: Context, m: Msg): ByteArray? =
        if ((m.tag and 8) != 0) ImgStore.get(c, m.body)
        else try { Base64.decode(m.body, Base64.NO_WRAP) } catch (e: Exception) { null }

    /** The plain image bytes, or null if the key is missing or wrong. */
    fun imageBytes(c: Context, m: Msg): ByteArray? {
        val raw = rawBytes(c, m) ?: return null
        if (!m.cipher) return raw
        val key = SessionVault.key(m.contactId) ?: return null
        return try { Crypto.decrypt(key, raw) } catch (e: Exception) { null }
    }

    fun mime(b: ByteArray): String =
        if (b.size > 3 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) "image/png"
        else if (b.size > 12 && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte()) "image/webp"
        else "image/jpeg"

    private fun ext(b: ByteArray): String = when (mime(b)) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }

    // ---------- decoding ----------
    private fun rotate(b: Bitmap, deg: Int): Bitmap {
        val m = Matrix()
        m.postRotate(deg.toFloat())
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun degrees(orientation: Int): Int = when (orientation) {
        6 -> 90
        3 -> 180
        8 -> 270
        else -> 0
    }

    /** Decodes at most maxSide pixels on the long side and applies the EXIF rotation. */
    fun decodeScaled(b: ByteArray, maxSide: Int): Bitmap? {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(b, 0, b.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            val bmp = BitmapFactory.decodeByteArray(b, 0, b.size, o2) ?: return null
            var rot = 0
            if (Build.VERSION.SDK_INT >= 24) {
                try { rot = degrees(ExifInterface(ByteArrayInputStream(b)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) }
                catch (e: Exception) { }
            }
            return if (rot == 0) bmp else rotate(bmp, rot)
        } catch (t: Throwable) {
            return null
        }
    }

    private fun rotationOf(c: Context, uri: Uri): Int {
        if (Build.VERSION.SDK_INT < 24) return 0
        return try {
            c.contentResolver.openInputStream(uri)?.use {
                degrees(ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1))
            } ?: 0
        } catch (e: Exception) { 0 }
    }

    // ---------- preparing an image to send ----------
    fun prepare(c: Context, uri: Uri, p: Preset): ByteArray? = when (p.mode) {
        2 -> readOriginal(c, uri)
        1 -> fullRes(c, uri)
        else -> compress(c, uri, p.side, p.bytes)
    }

    /** The file exactly as it is (keeps EXIF: position, camera). Null if unreadable or over 8 MB. */
    private fun readOriginal(c: Context, uri: Uri): ByteArray? = try {
        c.contentResolver.openInputStream(uri)?.use { s ->
            val b = s.readBytes()
            if (b.isEmpty() || b.size > MAX_BYTES) null else b
        }
    } catch (t: Throwable) { null }

    /** Full pixel size, JPEG quality 95, EXIF removed (re-encoding drops it). */
    private fun fullRes(c: Context, uri: Uri): ByteArray? {
        try {
            val src = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
            val rot = rotationOf(c, uri)
            var cur = if (rot != 0) rotate(src, rot) else src
            var q = 95
            while (true) {
                val bo = ByteArrayOutputStream()
                cur.compress(Bitmap.CompressFormat.JPEG, q, bo)
                if (bo.size() <= MAX_BYTES) return bo.toByteArray()
                if (q > 70) q -= 12
                else cur = Bitmap.createScaledBitmap(cur, maxOf(16, (cur.width * 0.8f).toInt()), maxOf(16, (cur.height * 0.8f).toInt()), true)
            }
        } catch (t: Throwable) {
            return null
        }
    }

    /** Shrinks and re-encodes as JPEG (this also removes EXIF: GPS position, camera info). */
    fun compress(c: Context, uri: Uri, side: Int, maxBytes: Int): ByteArray? {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= side) sample *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            val src = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null

            val rot = rotationOf(c, uri)
            val k = minOf(1f, side.toFloat() / maxOf(src.width, src.height))
            val mtx = Matrix()
            mtx.postScale(k, k)
            if (rot != 0) mtx.postRotate(rot.toFloat())
            var cur = Bitmap.createBitmap(src, 0, 0, src.width, src.height, mtx, true)

            var tries = 0
            while (true) {
                var q = 70
                while (q >= 25) {
                    val bo = ByteArrayOutputStream()
                    cur.compress(Bitmap.CompressFormat.JPEG, q, bo)
                    if (bo.size() <= maxBytes) return bo.toByteArray()
                    q -= 15
                }
                tries++
                if (tries > 6) return null
                cur = Bitmap.createScaledBitmap(cur,
                    maxOf(16, (cur.width * 0.8f).toInt()), maxOf(16, (cur.height * 0.8f).toInt()), true)
            }
        } catch (t: Throwable) {
            return null
        }
    }

    fun imagePayload(key: ByteArray?, img: ByteArray): ByteArray =
        if (key != null) byteArrayOf(3) + Crypto.encrypt(key, img) else byteArrayOf(2) + img

    fun smsParts(payload: ByteArray): Int = Codec.estimateParts(payload.size)

    // ---------- voice messages and videos ----------
    const val KIND_IMAGE = 0
    const val KIND_AUDIO = 1
    const val KIND_VIDEO = 2
    const val TAG_AUDIO = 32
    const val TAG_VIDEO = 64
    const val TAG_CHECKED = 128

    private val MAGIC = byteArrayOf('S'.code.toByte(), 'H'.code.toByte(), 'M'.code.toByte(), 'A'.code.toByte())

    class Av(val kind: Int, val seconds: Int, val data: ByteArray)
    class AvInfo(val kind: Int, val seconds: Int, val bytes: Int)

    /** "SHMA" + kind (1 voice, 2 video) + seconds (2 bytes) + the media file. This whole thing is what gets encrypted. */
    fun avWrap(kind: Int, seconds: Int, data: ByteArray): ByteArray {
        val s = seconds.coerceIn(0, 65535)
        return MAGIC + byteArrayOf(kind.toByte(), (s shr 8).toByte(), s.toByte()) + data
    }

    /** The reverse of avWrap, or null if these bytes are not a voice message / video (an ordinary image). */
    fun avUnwrap(b: ByteArray): Av? {
        if (b.size < 8) return null
        for (i in 0..3) if (b[i] != MAGIC[i]) return null
        val kind = b[4].toInt()
        if (kind != KIND_AUDIO && kind != KIND_VIDEO) return null
        val sec = ((b[5].toInt() and 255) shl 8) or (b[6].toInt() and 255)
        return Av(kind, sec, b.copyOfRange(7, b.size))
    }

    fun setAvInfo(c: Context, id: Long, kind: Int, seconds: Int, bytes: Int) {
        try { Db.get(c).setMeta("av_$id", "$kind|$seconds|$bytes") } catch (e: Exception) { }
    }

    fun avInfo(c: Context, id: Long): AvInfo? {
        val s = try { Db.get(c).getMeta("av_$id") } catch (e: Exception) { null } ?: return null
        val p = s.split("|")
        if (p.size != 3) return null
        return AvInfo(p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: 0, p[2].toIntOrNull() ?: 0)
    }

    /** Remembers (in the message tag) what a media message really is, so it is only opened once to find out. */
    fun markChecked(c: Context, id: Long, kind: Int, av: Av?) {
        var add = TAG_CHECKED
        if (kind == KIND_AUDIO) add = add or TAG_AUDIO
        if (kind == KIND_VIDEO) add = add or TAG_VIDEO
        try { Db.get(c).db().execSQL("UPDATE messages SET tag = (tag | ?) WHERE id=?", arrayOf<Any?>(add, id)) } catch (e: Exception) { }
        if (av != null) setAvInfo(c, id, av.kind, av.seconds, av.data.size)
    }

    // ---------- files ----------
    /** An image as a .txt file (one SHSO1 line) that you can send with any app. */
    fun exportImage(c: Context, ct: Contact, payload: ByteArray, ts: Long): File {
        val f = File(dir(c), "ShSo-" + safe(ct.name) + "-img-" + ts + ".txt")
        f.bufferedWriter().use {
            it.write("# Sh-So image\nSHSO1|$ts|S|")
            it.write(Base64.encodeToString(payload, Base64.NO_WRAP))
            it.write("\n")
        }
        return f
    }

    /** A plain (NOT encrypted) copy of a photo, for sharing from the viewer. */
    fun saveImage(c: Context, img: ByteArray): File {
        val f = File(dir(c), "ShSo-photo." + ext(img))
        f.writeBytes(img)
        return f
    }

    /** Reads a big text file (image exports can be many MB). */
    fun readText(c: Context, uri: Uri): String? = try {
        c.contentResolver.openInputStream(uri)?.use { s ->
            val b = s.readBytes()
            if (b.size > 24_000_000) null else String(b)
        }
    } catch (t: Throwable) { null }

    /** Whole chat (text and images) as raw encrypted file, or as readable text (NOT encrypted). */
    fun exportChat(c: Context, ct: Contact, readable: Boolean): Files.Result {
        val key = SessionVault.key(ct.id)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder(if (readable) "# Sh-So readable export (NOT encrypted)\n" else "# Sh-So raw export\n")
        var n = 0
        var skip = 0
        for (m in Messages.list(c, ct.id)) {
            val img = (m.tag and 4) != 0
            if (readable) {
                var t = m.body
                if (img) {
                    t = if ((m.tag and TAG_AUDIO) != 0) "[voice message]" else if ((m.tag and TAG_VIDEO) != 0) "[video]" else "[image]"
                } else if (m.cipher) {
                    t = "[encrypted]"
                    if (key != null) {
                        try { t = String(Crypto.decrypt(key, Base64.decode(m.body, Base64.NO_WRAP))) } catch (e: Exception) { }
                    }
                }
                val who = if (m.sent) tr("Me", "من") else ct.name
                sb.append("[").append(fmt.format(Date(m.ts))).append("] ").append(who).append(": ").append(t).append("\n")
                n++
            } else {
                val raw: ByteArray? = if (img) rawBytes(c, m) else null
                val payload: ByteArray? = when {
                    img && raw == null -> null
                    img && m.cipher -> byteArrayOf(3) + raw!!
                    img && !m.sent -> byteArrayOf(2) + raw!!
                    img -> if (ct.level == 1) {
                        if (key != null) imagePayload(key, raw!!) else null
                    } else imagePayload(null, raw!!)
                    !m.sent && m.cipher -> byteArrayOf(1) + Base64.decode(m.body, Base64.NO_WRAP)
                    !m.sent -> Codec.plain(m.body)
                    m.cipher -> byteArrayOf(1) + Base64.decode(m.body, Base64.NO_WRAP)
                    ct.level == 1 -> if (key != null) Codec.secure(key, m.body) else null
                    else -> Codec.plain(m.body)
                }
                if (payload == null) { skip++; continue }
                sb.append("SHSO1|").append(m.ts).append("|").append(if (m.sent) "S" else "R")
                    .append("|").append(Base64.encodeToString(payload, Base64.NO_WRAP)).append("\n")
                n++
            }
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(dir(c), "ShSo-" + safe(ct.name) + (if (readable) "-readable-" else "-") + stamp + ".txt")
        f.writeText(sb.toString())
        Messages.tagAll(c, ct.id, 2)
        return Files.Result(f, n, skip)
    }

    private fun imageExists(c: Context, cid: Long, ts: Long, sent: Boolean): Boolean =
        Db.get(c).db().rawQuery(
            "SELECT COUNT(*) FROM messages WHERE contact_id=? AND timestamp=? AND is_sent=? AND (tag & 4)!=0",
            arrayOf(cid.toString(), ts.toString(), if (sent) "1" else "0")
        ).use { cur -> cur.moveToFirst() && cur.getInt(0) > 0 }

    /** Import of a .txt file: image lines are handled here, everything else by Files.importText. */
    fun importFile(c: Context, ct: Contact, text: String, fromContact: Boolean): Files.ImportResult {
        var n = 0
        var bad = 0
        val rest = StringBuilder()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("SHSO1|")) {
                val f = line.split("|", limit = 4)
                val ts = f.getOrNull(1)?.toLongOrNull()
                val dir = f.getOrNull(2)
                val data = try { Base64.decode(f.getOrNull(3) ?: "", Base64.NO_WRAP) } catch (e: Exception) { ByteArray(0) }
                if (ts != null && (dir == "S" || dir == "R") && data.size > 1 &&
                    (data[0].toInt() == 2 || data[0].toInt() == 3)) {
                    val sent = if (fromContact) dir == "R" else dir == "S"
                    if (imageExists(c, ct.id, ts, sent)) {
                        bad++
                    } else {
                        val (body, extra) = store(c, data.copyOfRange(1, data.size))
                        Messages.insert(c, Msg(0, ct.id, body, sent, ts, true, data[0].toInt() == 3, 1, 1 or 4 or extra))
                        n++
                    }
                    continue
                }
            }
            rest.append(raw).append("\n")
        }
        val r = Files.importText(c, ct, rest.toString(), fromContact)
        return Files.ImportResult(r.imported + n, r.ignored + bad)
    }
}

private fun dKey(ts: Long): Long = (ts + TimeZone.getDefault().getOffset(ts)) / 86400000L

private fun dLabel(ts: Long): String {
    val today = dKey(System.currentTimeMillis())
    val k = dKey(ts)
    return when (k) {
        today -> tr("Today", "امروز")
        today - 1 -> tr("Yesterday", "دیروز")
        else -> DateFormat.format("dd MMM yyyy", ts).toString()
    }
}

/**
 * Chat list adapter: text, image, voice-message and video bubbles. Mine on the right, friend's on the left, each with a tail.
 * Fire messages are decrypted only if the key in RAM matches; otherwise only the raw text is shown.
 * Long-press copies the text (or the raw ciphertext of a small attachment). Tap an image / voice / video to open it.
 */
class ChatAdapter(private val ctx: Context, private val onMedia: (Msg, Int) -> Unit) : BaseAdapter() {
    private var items: List<Msg> = emptyList()
    private val kinds = HashMap<Long, Int>()
    private val cache = object : LruCache<Long, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    fun set(l: List<Msg>) { items = l; notifyDataSetChanged() }
    override fun getCount(): Int = items.size
    override fun getItem(i: Int): Msg = items[i]
    override fun getItemId(i: Int): Long = items[i].id

    private fun icon(name: String, tint: Int): ImageView {
        val iv = Icons.view(ctx, name, tint, 12, Neo.fs)
        iv.setPadding(Neo.dp(ctx, 2), 0, Neo.dp(ctx, 2), 0)
        return iv
    }

    /** -1 = not looked at yet. */
    private fun knownKind(m: Msg): Int = when {
        (m.tag and Media.TAG_AUDIO) != 0 -> Media.KIND_AUDIO
        (m.tag and Media.TAG_VIDEO) != 0 -> Media.KIND_VIDEO
        (m.tag and Media.TAG_CHECKED) != 0 -> Media.KIND_IMAGE
        else -> kinds[m.id] ?: -1
    }

    private fun mmss(sec: Int): String = (sec / 60).toString() + ":" + (sec % 60).toString().padStart(2, '0')

    override fun getView(pos: Int, v: View?, parent: ViewGroup?): View {
        val m = items[pos]
        val pal = Neo.P
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Neo.dp(ctx, 10), Neo.dp(ctx, 2), Neo.dp(ctx, 10), Neo.dp(ctx, 2))
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        if (pos == 0 || dKey(items[pos - 1].ts) != dKey(m.ts)) {
            val sep = Neo.text(ctx, dLabel(m.ts), 11, pal.recvText).apply {
                background = Neo.box(ctx, Neo.withAlpha(pal.recv, 235), Neo.withAlpha(Neo.DIM, 90), 12)
                setPadding(Neo.dp(ctx, 12), Neo.dp(ctx, 3), Neo.dp(ctx, 12), Neo.dp(ctx, 3))
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.CENTER_HORIZONTAL
            lp.topMargin = Neo.dp(ctx, 8)
            lp.bottomMargin = Neo.dp(ctx, 6)
            col.addView(sep, lp)
        }

        val isMedia = (m.tag and 4) != 0
        var kind = Media.KIND_IMAGE
        var text = m.body
        var ok = true
        var bmp: Bitmap? = null
        var av: Media.AvInfo? = null
        if (isMedia) {
            ok = !m.cipher || SessionVault.key(m.contactId) != null
            var pre: ByteArray? = null
            var known = knownKind(m)
            if (ok && known < 0) {
                // first time: look inside once to see if it is a picture, a voice message or a video
                pre = Media.imageBytes(ctx, m)
                if (pre == null) {
                    ok = false
                } else {
                    val unwrapped = Media.avUnwrap(pre)
                    known = if (unwrapped == null) Media.KIND_IMAGE else unwrapped.kind
                    kinds[m.id] = known
                    Media.markChecked(ctx, m.id, known, unwrapped)
                }
            }
            if (known >= 0) kind = known
            if (ok && kind == Media.KIND_IMAGE) {
                bmp = cache.get(m.id)
                if (bmp == null) {
                    val b = pre ?: Media.imageBytes(ctx, m)
                    if (b == null) {
                        ok = false
                    } else {
                        bmp = Media.decodeScaled(b, 1080)
                        if (bmp != null) cache.put(m.id, bmp)
                    }
                }
            } else if (ok) {
                av = Media.avInfo(ctx, m.id)
            }
        } else if (m.cipher) {
            ok = false
            val key = SessionVault.key(m.contactId)
            if (key != null) {
                try {
                    text = String(Crypto.decrypt(key, Base64.decode(m.body, Base64.NO_WRAP)))
                    ok = true
                } catch (e: Exception) { }
            }
        }
        val shown = text
        val fg = if (m.sent) pal.sentText else pal.recvText
        val soft = Neo.withAlpha(fg, 170)
        val tail = Neo.dp(ctx, 7)

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Neo.bubble(ctx, m.sent)
            val hp = Neo.dp(ctx, 12)
            setPadding(hp + (if (m.sent) 0 else tail), Neo.dp(ctx, 8), hp + (if (m.sent) tail else 0), Neo.dp(ctx, 6))
        }

        val screenW = ctx.resources.displayMetrics.widthPixels
        if (isMedia && ok && kind == Media.KIND_IMAGE && bmp != null) {
            val iv = ImageView(ctx)
            iv.setImageBitmap(bmp)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            val w = minOf((screenW * 0.62f).toInt(), bmp.width * Neo.dp(ctx, 2))
            val h = w * bmp.height / maxOf(1, bmp.width)
            box.addView(iv, LinearLayout.LayoutParams(w, h))
        } else if (isMedia && ok && kind == Media.KIND_IMAGE) {
            box.addView(Neo.text(ctx, tr("image is damaged", "تصویر خراب است"), 14, Neo.withAlpha(fg, 150)))
        } else if (isMedia && ok) {
            // voice message / video: a play button with the length and size
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumWidth = (screenW * 0.5f).toInt()
            }
            row.addView(Neo.text(ctx, "▶", 22, fg))
            val info = Neo.vertical(ctx)
            info.setPadding(Neo.dp(ctx, 12), 0, 0, 0)
            info.addView(Neo.text(ctx, if (kind == Media.KIND_AUDIO) tr("Voice message", "پیام صوتی") else tr("Video", "ویدیو"), 14, fg))
            val detail = if (av != null) mmss(av.seconds) + "  ·  " + Shrink.fmt(av.bytes.toLong()) else tr("tap to play", "برای پخش بزن")
            info.addView(Neo.text(ctx, detail, 10, soft))
            row.addView(info)
            box.addView(row)
        } else if (isMedia) {
            val kb = if ((m.tag and 8) != 0) "" else " (" + maxOf(1, m.body.length * 3 / 4 / 1024) + " KB)"
            box.addView(Neo.text(ctx, tr("encrypted attachment", "پیوست رمزشده") + kb, 14, Neo.withAlpha(fg, 150)))
        } else {
            val tv = Neo.text(ctx, shown, 15, if (ok) fg else Neo.withAlpha(fg, 150)).apply {
                maxWidth = (screenW * 0.72f).toInt()
            }
            box.addView(tv)
        }

        val foot = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        }
        if (!ok) foot.addView(icon("lock", soft))
        if ((m.tag and 1) != 0) {
            foot.addView(icon("download", soft))
            foot.addView(Neo.text(ctx, tr("imported", "وارد شده") + "  ", 10, soft))
        }
        if ((m.tag and 2) != 0) {
            foot.addView(icon("upload", soft))
            foot.addView(Neo.text(ctx, tr("exported", "خروجی") + "  ", 10, soft))
        }
        foot.addView(Neo.text(ctx, DateFormat.format("HH:mm", m.ts).toString(), 10, soft))
        if (m.sent) {
            when (m.status) {
                0 -> foot.addView(icon("clock", soft))
                2 -> {
                    foot.addView(icon("cross", pal.red))
                    foot.addView(Neo.text(ctx, tr("failed", "ناموفق"), 10, pal.red))
                }
                3 -> foot.addView(icon("check2", fg))
                else -> foot.addView(icon("check", soft))
            }
        }
        box.addView(foot)

        if (!isMedia) {
            box.setOnLongClickListener { Neo.copy(ctx, shown); true }
        } else if (!ok && (m.tag and 8) == 0) {
            box.setOnLongClickListener { Neo.copy(ctx, m.body); true }
        }
        if (isMedia && ok && (bmp != null || kind != Media.KIND_IMAGE)) box.setOnClickListener { onMedia(m, kind) }

        val lpB = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpB.gravity = if (m.sent) Gravity.RIGHT else Gravity.LEFT
        col.addView(box, lpB)
        return col
    }
}
