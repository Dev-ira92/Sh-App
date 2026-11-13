package com.shadow.society.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Shrinks a picture to a target size. Shows "reduce N% → size" instead of technical presets (used for SMS and the exact-file option). */
object Shrink {
    /** reduce = percent saved (0 = original, untouched); target = wanted size in bytes. */
    class Opt(val reduce: Double, val target: Long)

    fun fmt(b: Long): String =
        if (b >= 1_000_000) String.format(Locale.US, "%.1f MB", b / 1_000_000.0) else "${(b + 999) / 1000} KB"

    /** Size of the picked file in bytes, or -1. */
    fun sizeOf(c: Context, uri: Uri): Long {
        val n = try { c.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L } catch (t: Throwable) { -1L }
        if (n > 0) return n
        return try {
            c.contentResolver.openInputStream(uri)?.use { s ->
                var total = 0L
                val buf = ByteArray(65536)
                while (true) {
                    val r = s.read(buf)
                    if (r < 0) break
                    total += r
                    if (total > 40_000_000L) break
                }
                total
            } ?: -1L
        } catch (t: Throwable) { -1L }
    }

    fun options(orig: Long, sms: Boolean): List<Opt> {
        val targets: List<Long> = if (sms) {
            listOf(4_000L, 10_000L, 20_000L).filter { it < orig }.ifEmpty { listOf(orig) }
        } else {
            listOf(orig, orig * 3 / 4, orig / 2, orig / 4, orig / 10).filter { it > 0 }
        }
        return targets.map { t ->
            if (t >= orig) Opt(0.0, orig) else Opt((1.0 - t.toDouble() / orig) * 100.0, t)
        }
    }

    fun label(o: Opt, sms: Boolean): String {
        val head = if (o.reduce <= 0.0) tr("Original — no reduction (with GPS location)", "اصلی — بدون کاهش (با موقعیت GPS)")
        else {
            val pct = if (o.reduce >= 99.0) String.format(Locale.US, "%.1f", o.reduce) else o.reduce.roundToInt().toString()
            tr("Reduce $pct%", "کاهش $pct٪")
        }
        val tail = "  →  " + fmt(o.target) + (if (sms) "  ·  ~" + Codec.estimateParts(o.target.toInt() + 30) + " SMS" else "")
        return head + tail
    }

    fun prepare(c: Context, uri: Uri, o: Opt, orig: Long): ByteArray? =
        if (o.reduce <= 0.0) readAll(c, uri) else toTarget(c, uri, o.target, orig)

    /** The exact original file (null if over 8 MB). */
    private fun readAll(c: Context, uri: Uri): ByteArray? = try {
        c.contentResolver.openInputStream(uri)?.use { s ->
            val b = s.readBytes()
            if (b.isEmpty() || b.size > Media.MAX_BYTES) null else b
        }
    } catch (t: Throwable) { null }

    private fun rotation(c: Context, uri: Uri): Int {
        if (Build.VERSION.SDK_INT < 24) return 0
        return try {
            c.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90
                    3 -> 180
                    8 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) { 0 }
    }

    private fun rotate(b: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return b
        val m = Matrix()
        m.postRotate(deg.toFloat())
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun scaled(b: Bitmap, f: Double): Bitmap =
        Bitmap.createScaledBitmap(b, maxOf(16, (b.width * f).toInt()), maxOf(16, (b.height * f).toInt()), true)

    /** Finds the best JPEG quality (and, if needed, a smaller picture size) that fits the target. */
    private fun toTarget(c: Context, uri: Uri, target: Long, orig: Long): ByteArray? {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 4096) sample *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            val src = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
            var cur = rotate(src, rotation(c, uri))

            val guess = sqrt(target.toDouble() / maxOf(orig, 1L).toDouble()) * 1.5
            if (guess < 1.0) cur = scaled(cur, guess)

            var tries = 0
            while (tries < 12) {
                tries++
                var lo = 25
                var hi = 92
                var best: ByteArray? = null
                while (lo <= hi) {
                    val q = (lo + hi) / 2
                    val bo = ByteArrayOutputStream()
                    cur.compress(Bitmap.CompressFormat.JPEG, q, bo)
                    if (bo.size() <= target) { best = bo.toByteArray(); lo = q + 1 } else hi = q - 1
                }
                if (best != null) return best
                cur = scaled(cur, 0.8)
            }
            return null
        } catch (t: Throwable) {
            return null
        }
    }
}
