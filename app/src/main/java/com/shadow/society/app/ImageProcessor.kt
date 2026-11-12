package com.shadow.society.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Photo compression with three quality levels: ORIGINAL, GOOD, EXCELLENT. Output: WebP bytes.
 * فشرده‌سازی عکس با سه سطح کیفیت. خروجی: بایت‌های WebP.
 *
 * IMPORTANT (measured on real photos, see the notes in the chat):
 * The prompt's stages "quantize + 64/256-colour palette" make the picture MUCH worse and the file NOT smaller
 * than plain WebP. So by default (STRICT_SPEC = false) GOOD and EXCELLENT do: Lanczos resize → WebP.
 * Set STRICT_SPEC = true to run the exact pipeline of the prompt (YCbCr → subsample → quantize → palette → WebP).
 */
object ImageProcessor {
    enum class Mode { ORIGINAL, GOOD, EXCELLENT }

    class Result(val bytes: ByteArray, val note: String, val width: Int, val height: Int)

    /** false = best quality per byte (recommended). true = exact pipeline of the prompt. */
    const val STRICT_SPEC = false

    /** Longest side of the output. GOOD = 1000, EXCELLENT = 2000. ORIGINAL keeps every pixel (0 = no limit). */
    const val GOOD_MAX = 1000
    const val EXCELLENT_MAX = 2000
    const val ORIGINAL_MAX = 0

    // =====================================================================
    // 1) The three modes / سه حالت
    // =====================================================================

    /**
     * ORIGINAL: same pixels, no resize. Lossless WebP (API 30+), or WebP quality 100 on older phones.
     * If the result is bigger than the 8 MB limit, it falls back to lossy quality 100, then 95.
     * (Lossless WebP of a photo is usually about 3x bigger than the camera JPEG.)
     */
    fun compressOriginal(bitmap: Bitmap): ByteArray = originalWithNote(bitmap).first

    private fun originalWithNote(bitmap: Bitmap): Pair<ByteArray, String> {
        val first = encodeLossless(bitmap)
        val lossless = Build.VERSION.SDK_INT >= 30
        if (first.size <= Media.MAX_BYTES) {
            return Pair(first, if (lossless) tr("lossless", "بدون افت") else tr("quality 100 (this phone cannot do lossless WebP)", "کیفیت ۱۰۰ (این گوشی WebP بدون‌افت ندارد)"))
        }
        val q100 = encodeWebP(bitmap, 100)
        if (q100.size <= Media.MAX_BYTES) return Pair(q100, tr("quality 100 (lossless was too big)", "کیفیت ۱۰۰ (حالت بدون‌افت خیلی بزرگ شد)"))
        return Pair(encodeWebP(bitmap, 95), tr("quality 95 (too big for 100)", "کیفیت ۹۵ (برای ۱۰۰ خیلی بزرگ شد)"))
    }

    /** GOOD: at most 1000 px, WebP quality 85. */
    fun compressGood(bitmap: Bitmap): ByteArray {
        val fitted = fit(bitmap, GOOD_MAX)
        val out = if (STRICT_SPEC) strictPipeline(fitted, true) else encodeWebP(fitted, 85)
        if (fitted !== bitmap) fitted.recycle()
        return out
    }

    /** EXCELLENT: at most 2000 px, WebP quality 95. */
    fun compressExcellent(bitmap: Bitmap): ByteArray {
        val fitted = fit(bitmap, EXCELLENT_MAX)
        val out = if (STRICT_SPEC) strictPipeline(fitted, false) else encodeWebP(fitted, 95)
        if (fitted !== bitmap) fitted.recycle()
        return out
    }

    /**
     * Picks a photo from the phone, prepares it in the chosen mode. Run off the main thread.
     * Reads the file, applies the EXIF rotation, resizes, encodes. GPS and camera info are not kept.
     */
    fun process(c: Context, uri: Uri, mode: Mode): Result? {
        val raw = try {
            c.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (t: Throwable) { null } ?: return null
        if (raw.isEmpty() || raw.size > 80_000_000) return null

        val side = when (mode) {
            Mode.ORIGINAL -> if (ORIGINAL_MAX > 0) ORIGINAL_MAX else Int.MAX_VALUE
            Mode.GOOD -> GOOD_MAX
            Mode.EXCELLENT -> EXCELLENT_MAX
        }
        val src = Media.decodeScaled(raw, side) ?: return null   // keeps at least `side` pixels, so Lanczos has detail to use
        return try {
            when (mode) {
                Mode.ORIGINAL -> {
                    val (b, note) = originalWithNote(src)
                    Result(b, note, src.width, src.height)
                }
                Mode.GOOD -> {
                    val f = fit(src, GOOD_MAX)
                    val b = if (STRICT_SPEC) strictPipeline(f, true) else encodeWebP(f, 85)
                    val r = Result(b, tr("max 1000 px, quality 85", "حداکثر ۱۰۰۰ پیکسل، کیفیت ۸۵"), f.width, f.height)
                    if (f !== src) f.recycle()
                    r
                }
                Mode.EXCELLENT -> {
                    val f = fit(src, EXCELLENT_MAX)
                    val b = if (STRICT_SPEC) strictPipeline(f, false) else encodeWebP(f, 95)
                    val r = Result(b, tr("max 2000 px, quality 95", "حداکثر ۲۰۰۰ پیکسل، کیفیت ۹۵"), f.width, f.height)
                    if (f !== src) f.recycle()
                    r
                }
            }
        } catch (t: Throwable) {
            null
        } finally {
            src.recycle()
        }
    }

    // =====================================================================
    // 2) Resize with Lanczos-3 / تغییر اندازه با لنچوس
    // =====================================================================

    /** Scales down so the longest side is at most maxDim (aspect ratio kept). Returns the same bitmap if already small. */
    fun fit(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val m = maxOf(w, h)
        if (maxDim <= 0 || m <= maxDim) return bitmap
        val factor = maxDim.toDouble() / m
        val nw = maxOf(1, Math.round(w * factor).toInt())
        val nh = maxOf(1, Math.round(h * factor).toInt())
        return resizeLanczos(bitmap, nw, nh)
    }

    private class Axis(val start: IntArray, val weights: Array<FloatArray>)

    private fun lanczos3(x: Double): Double {
        if (x == 0.0) return 1.0
        if (abs(x) >= 3.0) return 0.0
        val px = PI * x
        return 3.0 * sin(px) * sin(px / 3.0) / (px * px)
    }

    /** For every output pixel: first input pixel and the (normalized) weights. Same method as Pillow's Lanczos. */
    private fun axis(src: Int, dst: Int): Axis {
        val scale = src.toDouble() / dst
        val fscale = maxOf(1.0, scale)
        val support = 3.0 * fscale
        val starts = IntArray(dst)
        val ws = Array(dst) { FloatArray(0) }
        for (i in 0 until dst) {
            val center = (i + 0.5) * scale
            val xmin = maxOf(0, (center - support + 0.5).toInt())
            val xmax = minOf(src, (center + support + 0.5).toInt()) - xmin
            val k = DoubleArray(maxOf(1, xmax))
            var sum = 0.0
            for (x in 0 until xmax) {
                val w = lanczos3((x + xmin - center + 0.5) / fscale)
                k[x] = w
                sum += w
            }
            val out = FloatArray(maxOf(1, xmax))
            if (sum != 0.0) for (x in 0 until xmax) out[x] = (k[x] / sum).toFloat() else out[0] = 1f
            starts[i] = xmin
            ws[i] = out
        }
        return Axis(starts, ws)
    }

    /**
     * Resize with the Lanczos-3 filter (two passes: horizontal, then vertical). Android's own scaling is only bilinear.
     * Memory-friendly: reads one row at a time.
     */
    fun resizeLanczos(bitmap: Bitmap, newWidth: Int, newHeight: Int): Bitmap {
        val sw = bitmap.width
        val sh = bitmap.height
        val ax = axis(sw, newWidth)
        val ay = axis(sh, newHeight)

        // pass 1: horizontal, every source row -> newWidth pixels (8-bit per channel)
        val tmp = IntArray(newWidth * sh)
        val row = IntArray(sw)
        for (y in 0 until sh) {
            bitmap.getPixels(row, 0, sw, 0, y, sw, 1)
            val o = y * newWidth
            for (x in 0 until newWidth) {
                val s = ax.start[x]
                val w = ax.weights[x]
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in w.indices) {
                    val p = row[s + k]
                    val wk = w[k]
                    r += wk * ((p shr 16) and 255)
                    g += wk * ((p shr 8) and 255)
                    b += wk * (p and 255)
                }
                tmp[o + x] = (0xFF shl 24) or (clamp8(r) shl 16) or (clamp8(g) shl 8) or clamp8(b)
            }
        }

        // pass 2: vertical, newWidth x sh -> newWidth x newHeight
        val out = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val accR = FloatArray(newWidth)
        val accG = FloatArray(newWidth)
        val accB = FloatArray(newWidth)
        val line = IntArray(newWidth)
        for (y in 0 until newHeight) {
            java.util.Arrays.fill(accR, 0f)
            java.util.Arrays.fill(accG, 0f)
            java.util.Arrays.fill(accB, 0f)
            val s = ay.start[y]
            val w = ay.weights[y]
            for (k in w.indices) {
                val wk = w[k]
                val base = (s + k) * newWidth
                for (x in 0 until newWidth) {
                    val p = tmp[base + x]
                    accR[x] += wk * ((p shr 16) and 255)
                    accG[x] += wk * ((p shr 8) and 255)
                    accB[x] += wk * (p and 255)
                }
            }
            for (x in 0 until newWidth) {
                line[x] = (0xFF shl 24) or (clamp8(accR[x]) shl 16) or (clamp8(accG[x]) shl 8) or clamp8(accB[x])
            }
            out.setPixels(line, 0, newWidth, 0, y, newWidth, 1)
        }
        return out
    }

    private fun clamp8(v: Float): Int {
        val i = (v + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    // =====================================================================
    // 3) RGB <-> YCbCr, subsampling, quantizing, palette (the prompt's stages)
    // =====================================================================

    /** RGB → (Y, Cb, Cr), each 0..255, one value per pixel. Formulas of the prompt (BT.601, full range). */
    fun rgbToYcbcr(bitmap: Bitmap): Triple<IntArray, IntArray, IntArray> {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val y = IntArray(px.size)
        val cb = IntArray(px.size)
        val cr = IntArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            y[i] = clampInt(Math.round(0.299 * r + 0.587 * g + 0.114 * b).toInt())
            cb[i] = clampInt(Math.round(-0.169 * r - 0.331 * g + 0.500 * b + 128).toInt())
            cr[i] = clampInt(Math.round(0.500 * r - 0.419 * g - 0.081 * b + 128).toInt())
        }
        return Triple(y, cb, cr)
    }

    private fun clampInt(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    /** (Y, Cb, Cr) → ARGB int. Standard inverse formulas. */
    private fun yccToRgb(y: Int, cb: Int, cr: Int): Int {
        val u = cb - 128
        val v = cr - 128
        val r = clampInt(Math.round(y + 1.402 * v).toInt())
        val g = clampInt(Math.round(y - 0.344136 * u - 0.714136 * v).toInt())
        val b = clampInt(Math.round(y + 1.772 * u).toInt())
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * 4:2:0 — Cb and Cr get half width AND half height (average of every 2x2 block). Y stays full size.
     * width and height are needed to find the blocks (the prompt's signature had no room for them).
     */
    fun subsample420(y: IntArray, cb: IntArray, cr: IntArray, width: Int, height: Int): Triple<IntArray, IntArray, IntArray> =
        subsample(y, cb, cr, width, height, 2, 2)

    /** 4:2:2 — Cb and Cr get half width, full height (average of every 2x1 block). */
    fun subsample422(y: IntArray, cb: IntArray, cr: IntArray, width: Int, height: Int): Triple<IntArray, IntArray, IntArray> =
        subsample(y, cb, cr, width, height, 2, 1)

    private fun subsample(y: IntArray, cb: IntArray, cr: IntArray, w: Int, h: Int, bx: Int, by: Int): Triple<IntArray, IntArray, IntArray> {
        val cw = (w + bx - 1) / bx
        val ch = (h + by - 1) / by
        val ocb = IntArray(cw * ch)
        val ocr = IntArray(cw * ch)
        for (j in 0 until ch) {
            for (i in 0 until cw) {
                var sb = 0
                var sr = 0
                var n = 0
                for (dy in 0 until by) {
                    val yy = j * by + dy
                    if (yy >= h) continue
                    for (dx in 0 until bx) {
                        val xx = i * bx + dx
                        if (xx >= w) continue
                        val k = yy * w + xx
                        sb += cb[k]
                        sr += cr[k]
                        n++
                    }
                }
                ocb[j * cw + i] = (sb + n / 2) / n
                ocr[j * cw + i] = (sr + n / 2) / n
            }
        }
        return Triple(y, ocb, ocr)
    }

    /**
     * Reduces every value to [levels] steps: level = round(v / step) * step with step = 256 / levels.
     * Example: levels 16 → step 16 (Y of GOOD); levels 8 → step 32 (Cb/Cr of GOOD).
     */
    fun quantize(data: IntArray, levels: Int): IntArray {
        val step = maxOf(1, 256 / levels)
        return IntArray(data.size) { clampInt(((data[it] + step / 2) / step) * step) }
    }

    /**
     * Median-cut palette. Returns (palette: each entry = [Y, Cb, Cr], index: palette number of every pixel).
     * Works on the list of UNIQUE colours with their pixel counts, so it is fast after quantizing (at most a few thousand colours).
     */
    fun buildPalette(y: IntArray, cb: IntArray, cr: IntArray, maxColors: Int): Pair<Array<IntArray>, IntArray> {
        val n = y.size
        val keys = IntArray(n) { (y[it] shl 16) or (cb[it] shl 8) or cr[it] }
        val counts = HashMap<Int, Int>()
        for (k in keys) counts[k] = (counts[k] ?: 0) + 1
        val uniq = counts.keys.toIntArray()
        val cnt = IntArray(uniq.size) { counts[uniq[it]] ?: 1 }

        fun ch(key: Int, axis: Int): Int = (key shr (16 - 8 * axis)) and 255

        val boxes = ArrayList<IntArray>()
        boxes.add(IntArray(uniq.size) { it })
        while (boxes.size < maxColors) {
            var best = -1
            var bestRange = 0
            var bestAxis = 0
            for ((bi, b) in boxes.withIndex()) {
                if (b.size < 2) continue
                for (a in 0..2) {
                    var lo = 255
                    var hi = 0
                    for (i in b) {
                        val v = ch(uniq[i], a)
                        if (v < lo) lo = v
                        if (v > hi) hi = v
                    }
                    if (hi - lo > bestRange) { bestRange = hi - lo; best = bi; bestAxis = a }
                }
            }
            if (best < 0) break
            val b = boxes[best]
            val sorted = b.sortedBy { ch(uniq[it], bestAxis) }
            var total = 0L
            for (i in sorted) total += cnt[i]
            var run = 0L
            var cut = 1
            for ((pos, i) in sorted.withIndex()) {
                run += cnt[i]
                if (run * 2 >= total) { cut = pos + 1; break }
            }
            cut = cut.coerceIn(1, sorted.size - 1)
            boxes[best] = sorted.subList(0, cut).toIntArray()
            boxes.add(sorted.subList(cut, sorted.size).toIntArray())
        }

        val palette = Array(boxes.size) { IntArray(3) }
        for ((bi, b) in boxes.withIndex()) {
            var w = 0L
            val s = LongArray(3)
            for (i in b) {
                w += cnt[i]
                for (a in 0..2) s[a] += ch(uniq[i], a).toLong() * cnt[i]
            }
            for (a in 0..2) palette[bi][a] = if (w == 0L) 0 else Math.round(s[a].toDouble() / w).toInt()
        }

        // every unique colour -> nearest palette entry
        val where = HashMap<Int, Int>()
        for (i in uniq.indices) {
            var bestIdx = 0
            var bestD = Int.MAX_VALUE
            for ((pi, p) in palette.withIndex()) {
                val dy = ch(uniq[i], 0) - p[0]
                val du = ch(uniq[i], 1) - p[1]
                val dv = ch(uniq[i], 2) - p[2]
                val d = dy * dy + du * du + dv * dv
                if (d < bestD) { bestD = d; bestIdx = pi }
            }
            where[uniq[i]] = bestIdx
        }
        val index = IntArray(n) { where[keys[it]] ?: 0 }
        return Pair(palette, index)
    }

    /** The exact pipeline of the prompt. good = GOOD (4:2:0, Y16, C8, 64 colours, q85); else EXCELLENT (4:2:2, Y32, C16, 256 colours, q95). */
    private fun strictPipeline(src: Bitmap, good: Boolean): ByteArray {
        val w = src.width
        val h = src.height
        val (y0, cb0, cr0) = rgbToYcbcr(src)
        val (y, cbS, crS) = if (good) subsample420(y0, cb0, cr0, w, h) else subsample422(y0, cb0, cr0, w, h)
        val yq = quantize(y, if (good) 16 else 32)
        val cbq = quantize(cbS, if (good) 8 else 16)
        val crq = quantize(crS, if (good) 8 else 16)

        // stretch the small colour planes back to full size (nearest)
        val cw = (w + 1) / 2
        val cbF = IntArray(w * h)
        val crF = IntArray(w * h)
        for (yy in 0 until h) {
            val cy = if (good) yy / 2 else yy
            for (xx in 0 until w) {
                val ci = cy * cw + xx / 2
                cbF[yy * w + xx] = cbq[ci]
                crF[yy * w + xx] = crq[ci]
            }
        }
        val (pal, idx) = buildPalette(yq, cbF, crF, if (good) 64 else 256)
        val px = IntArray(w * h) { val p = pal[idx[it]]; yccToRgb(p[0], p[1], p[2]) }
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val out = encodeWebP(bmp, if (good) 85 else 95)
        bmp.recycle()
        return out
    }

    // =====================================================================
    // 4) WebP / انکودر
    // =====================================================================

    /** Lossy WebP. API 30+ has WEBP_LOSSY; older phones use the (lossy) WEBP format. */
    fun encodeWebP(bitmap: Bitmap, quality: Int): ByteArray {
        val bo = ByteArrayOutputStream()
        if (Build.VERSION.SDK_INT >= 30) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, bo)
        } else {
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, quality, bo)
        }
        return bo.toByteArray()
    }

    /** Lossless WebP on API 30+. On older phones: WebP quality 100 (the closest there is). */
    private fun encodeLossless(bitmap: Bitmap): ByteArray {
        if (Build.VERSION.SDK_INT >= 30) {
            val bo = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, bo)
            return bo.toByteArray()
        }
        return encodeWebP(bitmap, 100)
    }
}
