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
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Images. Wire modes: 2 = plain JPEG, 3 = IV + AES-256-GCM(JPEG).
 * In the database an image message has tag bit 4. Sent/plain images keep Base64(JPEG) as body,
 * received Fire images keep Base64(IV+ciphertext) with cipher = true.
 */
object Media {
    class Preset(val en: String, val fa: String, val side: Int, val bytes: Int, val smsOk: Boolean) {
        fun label(): String = tr(en, fa)
    }

    val PRESETS = listOf(
        Preset("Tiny", "خیلی کوچک", 192, 4000, true),
        Preset("Small", "کوچک", 320, 10000, true),
        Preset("Medium", "متوسط", 480, 20000, true),
        Preset("Large (TXT only)", "بزرگ (فقط فایل)", 960, 120000, false)
    )

    const val MAX_SMS_PARTS = 300

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

            var rot = 0
            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    c.contentResolver.openInputStream(uri)?.use {
                        val ex = ExifInterface(it)
                        rot = when (ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                            6 -> 90
                            3 -> 180
                            8 -> 270
                            else -> 0
                        }
                    }
                } catch (e: Exception) { }
            }

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

    fun imagePayload(key: ByteArray?, jpeg: ByteArray): ByteArray =
        if (key != null) byteArrayOf(3) + Crypto.encrypt(key, jpeg) else byteArrayOf(2) + jpeg

    fun smsParts(payload: ByteArray): Int = Codec.estimateParts(payload.size)

    /** An image as a .txt file (one SHSO1 line) that you can send with any app. */
    fun exportImage(c: Context, ct: Contact, payload: ByteArray, ts: Long): File {
        val f = File(dir(c), "ShSo-" + safe(ct.name) + "-img-" + ts + ".txt")
        f.writeText("# Sh-So image\nSHSO1|$ts|S|" + Base64.encodeToString(payload, Base64.NO_WRAP) + "\n")
        return f
    }

    /** A plain (NOT encrypted) copy of a received photo, for sharing from the viewer. */
    fun saveJpeg(c: Context, jpeg: ByteArray): File {
        val f = File(dir(c), "ShSo-photo.jpg")
        f.writeBytes(jpeg)
        return f
    }

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
                    t = "[image]"
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
                val payload: ByteArray? = when {
                    img && m.cipher -> byteArrayOf(3) + Base64.decode(m.body, Base64.NO_WRAP)
                    img && !m.sent -> byteArrayOf(2) + Base64.decode(m.body, Base64.NO_WRAP)
                    img -> if (ct.level == 1) {
                        if (key != null) imagePayload(key, Base64.decode(m.body, Base64.NO_WRAP)) else null
                    } else imagePayload(null, Base64.decode(m.body, Base64.NO_WRAP))
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

    /** Import of a .txt file: image lines are handled here, everything else by Files.importText. */
    fun importFile(c: Context, ct: Contact, text: String, fromContact: Boolean): Files.ImportResult {
        var n = 0
        val rest = StringBuilder()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("SHSO1|")) {
                val f = line.split("|", limit = 4)
                val ts = f.getOrNull(1)?.toLongOrNull()
                val dir = f.getOrNull(2)
                val data = try { Base64.decode(f.getOrNull(3) ?: "", Base64.NO_WRAP) } catch (e: Exception) { ByteArray(0) }
                if (ts != null && (dir == "S" || dir == "R") && data.size > 1 &&
                    (data[0].toInt() == 2 || data[0].toInt() == 3)) {
                    val sent = if (fromContact) dir == "R" else dir == "S"
                    val body = Base64.encodeToString(data, 1, data.size - 1, Base64.NO_WRAP)
                    val m = Msg(0, ct.id, body, sent, ts, true, data[0].toInt() == 3, 1, 1 or 4)
                    if (!Messages.exists(c, m)) { Messages.insert(c, m); n++ }
                    continue
                }
            }
            rest.append(raw).append("\n")
        }
        val r = Files.importText(c, ct, rest.toString(), fromContact)
        return Files.ImportResult(r.imported + n, r.ignored)
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
 * Chat list adapter: text and image bubbles. Mine on the right, friend's on the left, each with a tail.
 * Fire messages are decrypted only if the key in RAM matches; otherwise only the raw text is shown.
 * Long-press copies the text (or the raw ciphertext of an image). Tap an image to open it.
 */
class ChatAdapter(private val ctx: Context, private val onImage: (ByteArray) -> Unit) : BaseAdapter() {
    private var items: List<Msg> = emptyList()
    private val cache = LruCache<Long, Bitmap>(12)

    fun set(l: List<Msg>) { items = l; notifyDataSetChanged() }
    override fun getCount(): Int = items.size
    override fun getItem(i: Int): Msg = items[i]
    override fun getItemId(i: Int): Long = items[i].id

    private fun icon(name: String, tint: Int): ImageView {
        val iv = Icons.view(ctx, name, tint, 12, Neo.fs)
        iv.setPadding(Neo.dp(ctx, 2), 0, Neo.dp(ctx, 2), 0)
        return iv
    }

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

        val isImg = (m.tag and 4) != 0
        var text = m.body
        var ok = true
        var bytes: ByteArray? = null
        if (isImg) {
            if (m.cipher) {
                ok = false
                val key = SessionVault.key(m.contactId)
                if (key != null) {
                    try {
                        bytes = Crypto.decrypt(key, Base64.decode(m.body, Base64.NO_WRAP))
                        ok = true
                    } catch (e: Exception) { }
                }
            } else {
                bytes = try { Base64.decode(m.body, Base64.NO_WRAP) } catch (e: Exception) { null }
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
        var tapBytes: ByteArray? = null
        if (isImg && ok && bytes != null) {
            var bmp = cache.get(m.id)
            if (bmp == null) {
                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) cache.put(m.id, bmp)
            }
            if (bmp != null) {
                val iv = ImageView(ctx)
                iv.setImageBitmap(bmp)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                val w = minOf((screenW * 0.62f).toInt(), bmp.width * Neo.dp(ctx, 2))
                val h = w * bmp.height / maxOf(1, bmp.width)
                box.addView(iv, LinearLayout.LayoutParams(w, h))
                tapBytes = bytes
            } else {
                box.addView(Neo.text(ctx, tr("image is damaged", "تصویر خراب است"), 14, Neo.withAlpha(fg, 150)))
            }
        } else if (isImg) {
            val kb = maxOf(1, m.body.length * 3 / 4 / 1024)
            box.addView(Neo.text(ctx, tr("encrypted image", "تصویر رمزشده") + " (" + kb + " KB)", 14, Neo.withAlpha(fg, 150)))
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

        val raw = if (isImg && !ok) m.body else shown
        if (!(isImg && ok)) box.setOnLongClickListener { Neo.copy(ctx, raw); true }
        val tb = tapBytes
        if (tb != null) box.setOnClickListener { onImage(tb) }

        val lpB = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpB.gravity = if (m.sent) Gravity.RIGHT else Gravity.LEFT
        col.addView(box, lpB)
        return col
    }
}
