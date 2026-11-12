package com.shadow.society.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.Toast
import android.widget.VideoView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File

/**
 * Voice messages and videos: recording, picking, compressing (video), playing.
 * صدا و فیلم: ضبط، فشرده‌سازی (فیلم)، پخش.
 * Temporary files live in cache/media and are deleted right after use.
 */
object AvKit {
    /** Biggest voice/video file we send (the internal server takes about 8 MB per message). */
    const val MAX_AV = 7_800_000
    private const val MAX_REC_SEC = 300

    class VideoPreset(val height: Int, val videoBps: Int)
    class VideoInfo(val seconds: Int, val width: Int, val height: Int)

    val VIDEO_PRESETS = listOf(VideoPreset(360, 400_000), VideoPreset(480, 700_000), VideoPreset(720, 1_500_000))

    private fun dir(c: Context): File {
        val d = File(c.cacheDir, "media")
        d.mkdirs()
        return d
    }

    /** Deletes leftovers of recordings / playback (called when a chat opens). */
    fun clean(c: Context) {
        try { dir(c).listFiles()?.forEach { it.delete() } } catch (e: Exception) { }
    }

    fun fmtTime(sec: Int): String = (sec / 60).toString() + ":" + (sec % 60).toString().padStart(2, '0')

    /** Rough size of a video of [seconds] with this preset (audio about 128 kbit/s, +5%). */
    fun estimate(seconds: Int, p: VideoPreset): Long = (p.videoBps + 128_000L) * seconds / 8L * 105L / 100L

    // ---------- looking at files ----------
    fun videoInfo(c: Context, uri: Uri): VideoInfo? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(c, uri)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            VideoInfo(((ms + 500) / 1000).toInt(), w, h)
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    fun audioSeconds(c: Context, uri: Uri): Int = videoInfo(c, uri)?.seconds ?: 0

    /** File ending for an audio file, found from its first bytes. */
    fun audioExt(b: ByteArray): String = when {
        b.size > 12 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte() -> "m4a"
        b.size > 3 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte() -> "mp3"
        b.size > 3 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0 -> "mp3"
        b.size > 3 && b[0] == 'O'.code.toByte() && b[1] == 'g'.code.toByte() && b[2] == 'g'.code.toByte() -> "ogg"
        b.size > 11 && b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte() -> "wav"
        else -> "m4a"
    }

    // ---------- microphone ----------
    fun hasMic(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun askMic(a: Activity) {
        ActivityCompat.requestPermissions(a, arrayOf(Manifest.permission.RECORD_AUDIO), 77)
    }

    /** Records AAC audio (mono, 32 kbit/s: about 240 KB per minute). */
    class Recorder(private val ctx: Context) {
        private var rec: MediaRecorder? = null
        private var file: File? = null
        private var startedAt = 0L
        @Volatile var limitHit = false

        @Suppress("DEPRECATION")
        private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()

        fun start(): Boolean {
            return try {
                val f = File(dir(ctx), "rec-" + System.currentTimeMillis() + ".m4a")
                val r = newRecorder()
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(22050)
                r.setAudioEncodingBitRate(32000)
                r.setMaxDuration(MAX_REC_SEC * 1000)
                r.setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) limitHit = true
                }
                r.setOutputFile(f.absolutePath)
                r.prepare()
                r.start()
                rec = r
                file = f
                startedAt = SystemClock.elapsedRealtime()
                true
            } catch (e: Exception) {
                cancel()
                false
            }
        }

        fun seconds(): Int = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()

        /** Stops and returns the recording, or null if it was too short / failed. */
        fun finish(): ByteArray? {
            val r = rec ?: return null
            val f = file
            var ok = true
            try { r.stop() } catch (e: Exception) { ok = false }   // throws if shorter than about 1 second
            try { r.release() } catch (e: Exception) { }
            rec = null
            val bytes = try { if (f != null && f.exists() && f.length() > 0) f.readBytes() else null } catch (e: Exception) { null }
            try { f?.delete() } catch (e: Exception) { }
            return if (ok || (bytes != null && bytes.size > 1000)) bytes else null
        }

        fun cancel() {
            try { rec?.stop() } catch (e: Exception) { }
            try { rec?.release() } catch (e: Exception) { }
            rec = null
            try { file?.delete() } catch (e: Exception) { }
        }
    }

    /** "Recording…" window with a timer. Stop = send, Cancel = throw away. */
    fun recordDialog(a: Activity, onDone: (ByteArray, Int) -> Unit) {
        val rec = Recorder(a)
        if (!rec.start()) {
            Toast.makeText(a, tr("Could not start the microphone", "میکروفون روشن نشد"), Toast.LENGTH_LONG).show()
            return
        }
        val h = Handler(Looper.getMainLooper())
        val tv = Neo.text(a, "● 0:00", 20, Neo.RED)
        tv.gravity = Gravity.CENTER
        val p = Neo.dp(a, 24)
        tv.setPadding(p, p, p, p)
        var closed = false
        val tick = object : Runnable {
            override fun run() {
                if (closed) return
                tv.text = "● " + fmtTime(rec.seconds()) + (if (rec.limitHit) "  (max)" else "")
                h.postDelayed(this, 400)
            }
        }
        Neo.dialog(a)
            .setTitle(tr("Recording…", "در حال ضبط…"))
            .setView(tv)
            .setCancelable(false)
            .setPositiveButton(tr("Stop and send", "توقف و ارسال")) { _, _ ->
                closed = true
                h.removeCallbacks(tick)
                val sec = maxOf(1, rec.seconds())
                val b = rec.finish()
                if (b == null) Toast.makeText(a, tr("Too short", "خیلی کوتاه بود"), Toast.LENGTH_SHORT).show() else onDone(b, sec)
            }
            .setNegativeButton(tr("Cancel", "انصراف")) { _, _ ->
                closed = true
                h.removeCallbacks(tick)
                rec.cancel()
            }
            .show()
        h.post(tick)
    }

    // ---------- playing ----------
    private fun mmss(ms: Int): String = fmtTime(ms / 1000)

    /** Plays a voice message / audio file. [onSave] is called by the Save button. */
    fun showAudio(a: Activity, data: ByteArray, onSave: () -> Unit) {
        val f = File(dir(a), "play-" + System.nanoTime() + "." + audioExt(data))
        val mp = MediaPlayer()
        try {
            f.writeBytes(data)
            mp.setDataSource(f.absolutePath)
            mp.prepare()
        } catch (e: Exception) {
            try { mp.release() } catch (x: Exception) { }
            f.delete()
            Toast.makeText(a, tr("Cannot play this audio", "این صدا پخش نشد"), Toast.LENGTH_LONG).show()
            return
        }
        val total = mp.duration
        val col = Neo.vertical(a)
        val p = Neo.dp(a, 20)
        col.setPadding(p, p, p, p / 2)
        val time = Neo.text(a, "0:00 / " + mmss(total), 16, Neo.TEXT)
        time.gravity = Gravity.CENTER
        Neo.add(col, time, 0)
        val seek = SeekBar(a)
        seek.max = maxOf(1, total)
        Neo.add(col, seek, 10)
        val btn = Neo.primary(a, "⏸ " + tr("Pause", "توقف"))
        Neo.add(col, btn, 12)

        val h = Handler(Looper.getMainLooper())
        var alive = true
        val tick = object : Runnable {
            override fun run() {
                if (!alive) return
                try {
                    seek.progress = mp.currentPosition
                    time.text = mmss(mp.currentPosition) + " / " + mmss(total)
                } catch (e: Exception) { }
                h.postDelayed(this, 300)
            }
        }
        btn.setOnClickListener {
            if (mp.isPlaying) { mp.pause(); btn.text = "▶ " + tr("Play", "پخش") }
            else { mp.start(); btn.text = "⏸ " + tr("Pause", "توقف") }
        }
        mp.setOnCompletionListener {
            btn.text = "▶ " + tr("Play", "پخش")
            seek.progress = 0
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) mp.seekTo(progress) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val d = Neo.dialog(a).setTitle(tr("Voice message", "پیام صوتی")).setView(col)
            .setPositiveButton(tr("Save…", "ذخیره…")) { _, _ -> onSave() }
            .setNegativeButton(tr("Close", "بستن"), null)
            .create()
        d.setOnDismissListener {
            alive = false
            h.removeCallbacks(tick)
            try { mp.stop() } catch (e: Exception) { }
            try { mp.release() } catch (e: Exception) { }
            f.delete()
        }
        d.show()
        mp.start()
        h.post(tick)
    }

    /** Plays a video in a window with the usual play/pause bar. */
    fun showVideo(a: Activity, data: ByteArray, onSave: () -> Unit) {
        val f = File(dir(a), "play-" + System.nanoTime() + ".mp4")
        try { f.writeBytes(data) } catch (e: Exception) {
            Toast.makeText(a, tr("Cannot play this video", "این فیلم پخش نشد"), Toast.LENGTH_LONG).show()
            return
        }
        val vv = VideoView(a)
        val mc = MediaController(a)
        mc.setAnchorView(vv)
        vv.setMediaController(mc)
        vv.setVideoPath(f.absolutePath)
        vv.setOnPreparedListener { vv.start() }
        vv.setOnErrorListener { _, _, _ ->
            Toast.makeText(a, tr("Cannot play this video", "این فیلم پخش نشد"), Toast.LENGTH_LONG).show()
            true
        }
        val box = FrameLayout(a)
        box.addView(vv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Neo.dp(a, 320), Gravity.CENTER))
        val d = Neo.dialog(a).setTitle(tr("Video", "ویدیو")).setView(box)
            .setPositiveButton(tr("Save…", "ذخیره…")) { _, _ -> onSave() }
            .setNegativeButton(tr("Close", "بستن"), null)
            .create()
        d.setOnDismissListener {
            try { vv.stopPlayback() } catch (e: Exception) { }
            f.delete()
        }
        d.show()
    }

    // ---------- compressing a video ----------
    /**
     * Re-encodes a video as H.264 / AAC at a smaller size and bit rate (Media3 Transformer).
     * Call on the main thread. onDone(bytes, null) or onDone(null, error). Returns a function that cancels.
     */
    fun transcode(a: Activity, uri: Uri, targetHeight: Int, videoBps: Int,
                  onProgress: (Int) -> Unit, onDone: (ByteArray?, String?) -> Unit): () -> Unit {
        val out = File(dir(a), "vid-" + System.currentTimeMillis() + ".mp4")
        val handler = Handler(Looper.getMainLooper())
        var finished = false

        val encoder = DefaultEncoderFactory.Builder(a)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBps).build())
            .build()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                finished = true
                val b = try { out.readBytes() } catch (e: Exception) { null }
                out.delete()
                onDone(b, if (b == null) tr("Could not read the result", "نتیجه خوانده نشد") else null)
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                finished = true
                out.delete()
                onDone(null, exportException.message ?: "error")
            }
        }

        val transformer = Transformer.Builder(a)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoder)
            .addListener(listener)
            .build()

        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(Effects(listOf(), listOf(Presentation.createForHeight(targetHeight))))
            .build()
        transformer.start(item, out.absolutePath)

        val ph = ProgressHolder()
        val tick = object : Runnable {
            override fun run() {
                if (finished) return
                if (transformer.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(ph.progress)
                handler.postDelayed(this, 500)
            }
        }
        handler.postDelayed(tick, 500)

        return {
            finished = true
            handler.removeCallbacks(tick)
            try { transformer.cancel() } catch (e: Exception) { }
            out.delete()
        }
    }
}
