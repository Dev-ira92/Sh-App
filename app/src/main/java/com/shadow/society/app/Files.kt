package com.shadow.society.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** QR helpers: render (black on white, easy for any scanner) and decode from a picture. */
object QrTools {
    fun render(text: String, px: Int, white: Boolean): Bitmap? = try {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 4,
            EncodeHintType.CHARACTER_SET to "UTF-8")
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, hints)
        val w = m.width
        val h = m.height
        val dark = 0xFF000000.toInt()
        val light = if (white) 0xFFFFFFFF.toInt() else 0xFF00FF41.toInt()
        val pix = IntArray(w * h) { i -> if (m.get(i % w, i / w)) dark else light }
        Bitmap.createBitmap(pix, w, h, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) { null }

    fun decode(bmp: Bitmap): String? = try {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val hints = mapOf<DecodeHintType, Any>(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true)
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, px))), hints).text
    } catch (t: Throwable) { null }
}

/**
 * Export / import of messages and keys as files.
 * Raw message line:  SHSO1|time|S or R|base64([mode][data])   (S = written by the file's author)
 * Fire messages stay encrypted inside raw files; only someone with the same session key can read them.
 * Key file:  TXT line "SHSOKEY1|key"  /  JSON {"app":"Sh-So","type":"key","key":"..."}  /  PNG of the QR code.
 */
object Files {
    private const val DIR = "exports"
    private const val MAGIC = "SHSO1"

    class Result(val file: File, val count: Int, val skipped: Int)
    class ImportResult(val imported: Int, val ignored: Int)

    fun clear(c: Context) { File(c.cacheDir, DIR).deleteRecursively() }

    private fun safe(name: String): String {
        val s = name.replace(Regex("[^A-Za-z0-9_-]"), "")
        return if (s.isEmpty()) "chat" else s
    }

    private fun outFile(c: Context, name: String): File {
        val d = File(c.cacheDir, DIR)
        d.deleteRecursively()          // older exports are removed
        d.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(d, "ShSo-$name-$stamp.txt")
    }

    private fun namedFile(c: Context, name: String): File {
        val d = File(c.cacheDir, DIR)
        d.deleteRecursively()
        d.mkdirs()
        return File(d, name)
    }

    // ---------- my key ----------
    fun exportKeyTxt(c: Context, key: String): File {
        val f = namedFile(c, "ShSo-key.txt")
        f.writeText("# Sh-So key. Give it only to the person you will talk with.\nSHSOKEY1|$key\n")
        return f
    }

    fun exportKeyJson(c: Context, key: String): File {
        val j = JSONObject()
        j.put("app", "Sh-So")
        j.put("type", "key")
        j.put("v", 1)
        j.put("key", key)
        j.put("created", System.currentTimeMillis())
        val f = namedFile(c, "ShSo-key.json")
        f.writeText(j.toString(2))
        return f
    }

    fun exportKeyPng(c: Context, key: String): File? {
        val bmp = QrTools.render(key, 900, true) ?: return null
        val f = namedFile(c, "ShSo-key-qr.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    /** Finds a key in JSON, TXT ("SHSOKEY1|...") or a bare key string. */
    fun parseKey(text: String): String? {
        val t = text.trim()
        if (t.startsWith("{")) {
            try {
                val k = JSONObject(t).optString("key").trim()
                if (k.isNotEmpty()) return k
            } catch (e: Exception) { }
        }
        for (line in t.lines()) {
            val l = line.trim()
            if (l.startsWith("SHSOKEY1|")) {
                val k = l.substring(9).trim()
                return if (k.isEmpty()) null else k
            }
        }
        if (Regex("[A-Za-z0-9]{8,2000}").matches(t)) return t
        return null
    }

    /** Reads a key from a chosen file: text/JSON, or a picture containing the QR code. */
    fun readKey(c: Context, uri: Uri): String? {
        val type = c.contentResolver.getType(uri) ?: ""
        if (type.startsWith("image/")) {
            val bmp = try {
                c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            } catch (t: Throwable) { null }
            if (bmp == null) return null
            val s = QrTools.decode(bmp) ?: return null
            return parseKey(s)
        }
        val s = readUri(c, uri) ?: return null
        return parseKey(s)
    }

    // ---------- messages ----------
    /** Whole chat in raw (encrypted) form. */
    fun exportRaw(c: Context, ct: Contact): Result {
        val key = SessionVault.key(ct.id)
        val sb = StringBuilder("# Sh-So raw export\n")
        var n = 0
        var skip = 0
        for (m in Messages.list(c, ct.id)) {
            val payload: ByteArray? = when {
                !m.sent && m.cipher -> byteArrayOf(1) + Base64.decode(m.body, Base64.NO_WRAP)
                !m.sent -> Codec.plain(m.body)
                m.cipher -> byteArrayOf(1) + Base64.decode(m.body, Base64.NO_WRAP)
                ct.level == 1 -> if (key != null) Codec.secure(key, m.body) else null
                else -> Codec.plain(m.body)
            }
            if (payload == null) { skip++; continue }
            sb.append(MAGIC).append("|").append(m.ts).append("|").append(if (m.sent) "S" else "R")
                .append("|").append(Base64.encodeToString(payload, Base64.NO_WRAP)).append("\n")
            n++
        }
        val f = outFile(c, safe(ct.name))
        f.writeText(sb.toString())
        Messages.tagAll(c, ct.id, 2)
        return Result(f, n, skip)
    }

    /** Whole chat as readable text. NOT encrypted. */
    fun exportReadable(c: Context, ct: Contact): Result {
        val key = SessionVault.key(ct.id)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder("# Sh-So readable export (NOT encrypted)\n")
        var n = 0
        for (m in Messages.list(c, ct.id)) {
            var t = m.body
            if (m.cipher) {
                t = "[encrypted]"
                if (key != null) {
                    try { t = String(Crypto.decrypt(key, Base64.decode(m.body, Base64.NO_WRAP))) } catch (e: Exception) { }
                }
            }
            val who = if (m.sent) tr("Me", "من") else ct.name
            sb.append("[").append(fmt.format(Date(m.ts))).append("] ").append(who).append(": ").append(t).append("\n")
            n++
        }
        val f = outFile(c, safe(ct.name) + "-readable")
        f.writeText(sb.toString())
        Messages.tagAll(c, ct.id, 2)
        return Result(f, n, 0)
    }

    /** One typed message as a file you can send with any app. */
    fun exportMessage(c: Context, ct: Contact, payload: ByteArray, ts: Long): File {
        val f = outFile(c, safe(ct.name) + "-msg")
        f.writeText("# Sh-So message\n$MAGIC|$ts|S|" + Base64.encodeToString(payload, Base64.NO_WRAP) + "\n")
        return f
    }

    /** Opens the Android share sheet so you choose which app sends the file. */
    fun share(a: Activity, f: File, mime: String = "text/plain") {
        val uri = FileProvider.getUriForFile(a, a.packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_SEND)
        i.type = mime
        i.putExtra(Intent.EXTRA_STREAM, uri)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(i, tr("Send with...", "ارسال با...")))
    }

    fun readUri(c: Context, uri: Uri): String? = try {
        c.contentResolver.openInputStream(uri)?.use { s ->
            val b = s.readBytes()
            if (b.size > 2000000) null else String(b)
        }
    } catch (e: Exception) { null }

    private fun build(cid: Long, d: ByteArray, ts: Long, sent: Boolean): Msg? = when (d[0].toInt()) {
        0 -> Msg(0, cid, String(d, 1, d.size - 1), sent, ts, true, false, 1, 1)
        1 -> Msg(0, cid, Base64.encodeToString(d, 1, d.size - 1, Base64.NO_WRAP), sent, ts, true, true, 1, 1)
        else -> null
    }

    /**
     * Accepts raw export files, "TXT|..." SMS lines, or a bare Base64 ciphertext.
     * fromContact = the file was written by my contact (S lines become received messages).
     */
    fun importText(c: Context, ct: Contact, text: String, fromContact: Boolean): ImportResult {
        var n = 0
        var bad = 0
        val frags = ArrayList<String>()
        val b64 = Regex("[A-Za-z0-9+/=]+")
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("$MAGIC|")) {
                val f = line.split("|", limit = 4)
                val ts = f.getOrNull(1)?.toLongOrNull()
                val dir = f.getOrNull(2)
                val data = try { Base64.decode(f.getOrNull(3) ?: "", Base64.NO_WRAP) } catch (e: Exception) { ByteArray(0) }
                if (ts == null || (dir != "S" && dir != "R") || data.size < 2) {
                    bad++
                } else {
                    val sent = if (fromContact) dir == "R" else dir == "S"
                    val m = build(ct.id, data, ts, sent)
                    if (m == null || Messages.exists(c, m)) {
                        bad++
                    } else {
                        Messages.insert(c, m)
                        n++
                    }
                }
            } else if (Codec.parse(line) != null) {
                frags.add(line)
            } else if (line.length >= 40 && b64.matches(line)) {
                try {
                    frags.addAll(Codec.encode(byteArrayOf(1) + Base64.decode(line, Base64.NO_WRAP)))
                } catch (e: Exception) { bad++ }
            } else {
                bad++
            }
        }
        if (frags.isNotEmpty()) {
            val now = System.currentTimeMillis()
            frags.forEach { Spool.add(c, ct.phone, now, it) }
            n += Inbox.drain(c, 1)
        }
        return ImportResult(n, bad)
    }
}
