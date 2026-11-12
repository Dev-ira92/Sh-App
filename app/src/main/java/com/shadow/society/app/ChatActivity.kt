package com.shadow.society.app

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged

class ChatActivity : OnlineBase() {
    private var contact: Contact? = null
    private var contactId = -1L
    private lateinit var adapter: ChatAdapter
    private lateinit var input: EditText
    private lateinit var header: TextView
    private lateinit var counter: TextView

    private var pendingSave: ByteArray? = null
    private var cancelFn: (() -> Unit)? = null

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    private val imgLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImagePicked(uri)
    }

    private val vidLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onVideoPicked(uri)
    }

    private val audLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onAudioPicked(uri)
    }

    /** "Save to the phone": the system asks where to put the file (no storage permission needed). */
    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
        val b = pendingSave
        pendingSave = null
        if (uri != null && b != null) {
            Thread {
                val ok = try {
                    contentResolver.openOutputStream(uri)?.use { it.write(b) }
                    true
                } catch (e: Exception) { false }
                runOnUiThread { toast(if (ok) tr("Saved", "ذخیره شد") else tr("Could not save", "ذخیره نشد")) }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        contactId = intent.getLongExtra("id", -1L)

        val root = Neo.vertical(this)
        val p = Neo.dp(this, 12)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Neo.PANEL)
        bar.elevation = Neo.dp(this, 6).toFloat()
        bar.setPadding(p / 3, p / 3, p / 3, p / 3)
        bar.addView(Icons.button(this, "back", Neo.BARTEXT) { finish() })
        header = Neo.text(this, "", 15, Neo.BARTEXT)
        header.typeface = Neo.BOLD
        bar.addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Icons.button(this, "menu", Neo.BARTEXT) { menu() })
        root.addView(bar)

        val lv = ListView(this)
        lv.setBackgroundColor(0)
        lv.cacheColorHint = 0
        lv.divider = null
        lv.selector = ColorDrawable(0)
        lv.isStackFromBottom = true
        lv.transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
        adapter = ChatAdapter(this) { m, k -> openMedia(m, k) }
        lv.adapter = adapter
        root.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        counter = Neo.text(this, "", 11, Neo.DIM)
        counter.setPadding(p, 2, p, 2)
        counter.setBackgroundColor(Neo.withAlpha(Neo.PANEL, 200))
        root.addView(counter)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setBackgroundColor(Neo.PANEL)
        row.elevation = Neo.dp(this, 6).toFloat()
        row.setPadding(p / 2, p / 2, p / 2, p / 2)

        row.addView(Icons.button(this, "add", Neo.ACCENT) { attach() })

        input = Neo.input(this, tr("Message", "پیام"))
        input.maxLines = 4
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sendBtn = Icons.button(this, "send", Neo.P.onAccent) { send() }
        sendBtn.background = Neo.raised(this, 30).apply { shape = GradientDrawable.OVAL }
        sendBtn.elevation = Neo.dp(this, 6).toFloat()
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(p / 2, 0, 0, 0)
        row.addView(sendBtn, lp)
        root.addView(row)
        setContentView(Neo.backdrop(this, root))

        input.doAfterTextChanged { updateCounter(it.toString()) }
    }

    override fun onReady() {
        val c = Contacts.byId(this, contactId)
        if (c == null) { finish(); return }
        contact = c
        header.text = c.name + "\n" + c.phone + "  " + (if (c.level == 1) "🔐" else "⚠ " + tr("plain", "ساده"))
        updateCounter(input.text.toString())
        reload()
        Inbox.listener = Runnable { reload() }
        OnlinePoll.start(this)
        Thread {
            AvKit.clean(this)
            if (Inbox.drain(this) > 0) runOnUiThread { reload() }
            ImgStore.sweep(this)
        }.start()
    }

    override fun onPause() {
        super.onPause()
        Inbox.listener = null
        OnlinePoll.stop()
    }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun reload() {
        val c = contact ?: return
        if (!SessionVault.isUnlocked()) return
        Messages.markRead(this, c.id)
        adapter.set(Messages.list(this, c.id))
    }

    private fun updateCounter(t: String) {
        val c = contact ?: return
        val bytes = t.toByteArray().size + 1 + (if (c.level == 1) 28 else 0)
        counter.text = if (t.isEmpty()) "" else "~" + Codec.estimateParts(bytes) + " SMS"
    }

    // ---------- the three-line menu ----------
    private fun menu() {
        val c = contact ?: return
        val labels = arrayOf(
            "🔑 " + tr("Keys of this chat", "کلیدهای این چت"),
            "⬇ " + tr("Import", "ایمپورت"),
            "⬆ " + tr("Export", "اکسپورت"),
            "🖥 " + tr("Friend's server address", "آدرس سرور دوستم"))
        Dialogs.choose(this, c.name, labels, -1) { w ->
            when (w) {
                0 -> unlock()
                1 -> importMenu()
                2 -> exportMenu()
                else -> serverDialog()
            }
        }
    }

    private fun serverDialog() {
        val c = contact ?: return
        val s = Online.contactServer(this, c.id)
        val msg = if (s == null) {
            tr("This chat uses MY server. If your friend has his own server, import its address.",
                "این چت از سرور من استفاده می‌کند. اگر دوستت سرور خودش را دارد، آدرسش را وارد کن.")
        } else {
            tr("Friend's server: ", "سرور دوستم: ") + Online.host(s.u)
        }
        Neo.dialog(this).setTitle("🖥 " + c.name).setMessage(msg)
            .setPositiveButton(tr("Import address", "وارد کردن آدرس")) { _, _ ->
                importRaw(tr("Friend's server address", "آدرس سرور دوستم")) { raw ->
                    val sv = OnlineParse.server(raw)
                    if (sv == null) {
                        toast(tr("This is not a server address", "این آدرس سرور نیست"))
                    } else {
                        Online.setContactServer(this, c.id, sv)
                        toast(tr("Saved", "ذخیره شد"))
                    }
                }
            }
            .setNeutralButton(tr("Use my server", "استفاده از سرور من")) { _, _ ->
                Online.clearContactServer(this, c.id)
                toast(tr("Now using my server", "حالا از سرور من استفاده می‌شود"))
            }
            .setNegativeButton(tr("Close", "بستن"), null).show()
    }

    // ---------- keys ----------
    private fun unlock() {
        val c = contact ?: return
        showUnlock(this, c, this::scanKey) { reload() }
    }

    // ---------- checks ----------
    private fun onlineReady(c: Contact): Boolean {
        if (!Online.ready(this)) {
            Dialogs.confirm(this, tr("Online is not set up yet. Open the settings?", "حالت آنلاین هنوز تنظیم نشده. تنظیمات باز شود؟")) {
                startActivity(Intent(this, OnlineActivity::class.java))
            }
            return false
        }
        if (Online.inboxOfPhone(c.phone) == null) {
            toast(tr("This contact needs a valid phone number", "این مخاطب شماره‌ی معتبر لازم دارد"))
            return false
        }
        return true
    }

    private fun smsOk(c: Contact): Boolean {
        if (c.phone.startsWith("ol:")) {
            toast(tr("This contact has no phone number. Edit the contact first.", "این مخاطب شماره ندارد. اول ویرایشش کن."))
            return false
        }
        return SmsPerm.ensure(this, true)
    }

    private fun guardPlain(c: Contact, ch: SendFlow.Choice, go: () -> Unit) {
        if (c.level == 0 && ch.kind != 0) {
            Dialogs.confirm(this, tr("This contact is NOT encrypted: the server / app can read the message. Continue?",
                "این مخاطب رمزنگاری نیست: سرور / برنامه می‌تواند پیام را بخواند. ادامه؟"), go)
        } else {
            go()
        }
    }

    // ---------- send text ----------
    private fun textPayload(c: Contact, text: String): ByteArray? {
        if (c.level == 1) {
            val key = SessionVault.key(c.id)
            if (key == null) { unlock(); return null }
            return Codec.secure(key, text)
        }
        return Codec.plain(text)
    }

    private fun send() {
        val c = contact ?: return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (text.length > 500) { toast(tr("max 500 characters", "حداکثر ۵۰۰ کاراکتر")); return }
        val payload = textPayload(c, text) ?: return
        SendFlow.ask(this, c) { ch -> guardPlain(c, ch) { dispatchText(c, text, payload, ch) } }
    }

    /** Shares a payload through another app as text / .txt / .json. */
    private fun shareOut(c: Contact, payload: ByteArray, ts: Long, format: Int, isImage: Boolean) {
        val b64 = Base64.encodeToString(payload, Base64.NO_WRAP)
        var fmt = format
        if (fmt == 0 && b64.length > 150_000) {
            toast(tr("Too big for plain text, sending as a file", "برای متن بزرگ است، به صورت فایل فرستاده می‌شود"))
            fmt = 1
        }
        if (fmt == 0) {
            SendFlow.shareText(this, "SHSO1|$ts|S|$b64")
            return
        }
        val f = if (isImage) Media.exportImage(this, c, payload, ts) else Files.exportMessage(this, c, payload, ts)
        if (fmt == 2) Files.share(this, Online.txtToJson(f), "application/json") else Files.share(this, f)
    }

    private fun dispatchText(c: Contact, text: String, payload: ByteArray, ch: SendFlow.Choice) {
        val now = System.currentTimeMillis()
        when (ch.kind) {
            0 -> {
                if (!smsOk(c)) return
                val id = Messages.insert(this, Msg(0, c.id, text, true, now, true, false, 0, 0))
                try { SimSender.send(this, c.phone, payload, id, ch.subId) } catch (e: Exception) { Messages.setStatus(this, id, 2) }
            }
            1 -> {
                if (!onlineReady(c)) return
                val id = Messages.insert(this, Msg(0, c.id, text, true, now, true, false, 0, 0))
                Thread {
                    val err = Online.sendTo(this, c, payload, now)
                    try { Messages.setStatus(this, id, if (err == null) 1 else 2) } catch (e: Exception) { }
                    runOnUiThread {
                        if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                        reload()
                    }
                }.start()
            }
            else -> {
                Messages.insert(this, Msg(0, c.id, text, true, now, true, false, 1, 2))
                shareOut(c, payload, now, ch.format, false)
            }
        }
        input.setText("")
        reload()
    }

    // ---------- the + button: photo, video, voice message, audio file ----------
    private fun attach() {
        val c = contact ?: return
        if (c.level == 1 && SessionVault.key(c.id) == null) { unlock(); return }
        val labels = arrayOf(
            "🖼 " + tr("Photo", "عکس"),
            "🎬 " + tr("Video", "فیلم"),
            "🎤 " + tr("Record a voice message", "ضبط پیام صوتی"),
            "🎵 " + tr("Audio file", "فایل صوتی"))
        Dialogs.choose(this, tr("Send", "ارسال"), labels, -1) { w ->
            when (w) {
                0 -> imgLauncher.launch("image/*")
                1 -> vidLauncher.launch("video/*")
                2 -> recordVoice()
                else -> audLauncher.launch("audio/*")
            }
        }
    }

    /** Voice and video go online only (an SMS cannot carry them). */
    private fun askMediaDest(c: Contact, cb: (SendFlow.Choice) -> Unit) {
        val labels = arrayOf(
            "🏠 " + tr("Internal server (online)", "سرور داخلی (آنلاین)"),
            "📤 " + tr("Other app, as a .txt file", "برنامه‌ی دیگر، فایل txt"),
            "📤 " + tr("Other app, as a .json file", "برنامه‌ی دیگر، فایل json"))
        Dialogs.choose(this, tr("Voice and video go online only", "صدا و فیلم فقط آنلاین می‌روند"), labels, 0) { w ->
            val ch = when (w) {
                0 -> SendFlow.Choice(1)
                1 -> SendFlow.Choice(2, -1, 1)
                else -> SendFlow.Choice(2, -1, 2)
            }
            guardPlain(c, ch) { cb(ch) }
        }
    }

    // ---------- photos ----------
    private fun onImagePicked(uri: Uri) {
        val c = contact ?: return
        SendFlow.ask(this, c) { ch -> guardPlain(c, ch) { chooseSize(c, uri, ch) } }
    }

    private fun chooseSize(c: Contact, uri: Uri, ch: SendFlow.Choice) {
        if (ch.kind == 0) { smsSizes(c, uri, ch); return }   // SMS: tiny sizes
        val labels = arrayOf(
            "✨ " + tr("Excellent — up to 2000 px, high quality", "عالی — تا ۲۰۰۰ پیکسل، کیفیت بالا"),
            "👍 " + tr("Good — up to 1000 px, small size", "خوب — تا ۱۰۰۰ پیکسل، حجم کم"),
            "🎞 " + tr("Original — every pixel, no loss, location removed (big)", "اصلی — همه‌ی پیکسل‌ها، بدون افت، بدون موقعیت (حجم زیاد)"),
            "📄 " + tr("The exact file as taken (keeps the location)", "فایل دقیق همان‌طور که هست (با موقعیت)"))
        Dialogs.choose(this, tr("Photo quality", "کیفیت عکس"), labels, 0) { w -> processPhoto(c, uri, ch, w) }
    }

    private fun processPhoto(c: Contact, uri: Uri, ch: SendFlow.Choice, w: Int) {
        toast(tr("Preparing the photo…", "در حال آماده‌سازی عکس…"))
        Thread {
            var bytes: ByteArray? = null
            var note = ""
            try {
                if (w == 3) {
                    bytes = Shrink.prepare(this, uri, Shrink.Opt(0.0, 0L), 0L)
                } else {
                    val mode = when (w) {
                        0 -> ImageProcessor.Mode.EXCELLENT
                        1 -> ImageProcessor.Mode.GOOD
                        else -> ImageProcessor.Mode.ORIGINAL
                    }
                    val r = ImageProcessor.process(this, uri, mode)
                    if (r != null) {
                        bytes = r.bytes
                        note = r.note + " · " + r.width + "×" + r.height
                    }
                }
            } catch (t: Throwable) { }
            val out = bytes
            runOnUiThread {
                if (out == null) {
                    toast(tr("Could not prepare the photo (too big or not readable)", "عکس آماده نشد (خیلی بزرگ یا ناخوانا)"))
                } else {
                    Dialogs.confirm(this, tr("Final size: ", "حجم نهایی: ") + Shrink.fmt(out.size.toLong()) +
                            (if (note.isNotEmpty()) "\n$note" else "") + "\n" + tr("Send?", "ارسال شود؟")) {
                        dispatchImage(c, out, ch)
                    }
                }
            }
        }.start()
    }

    /** SMS: the old short list of tiny sizes. */
    private fun smsSizes(c: Contact, uri: Uri, ch: SendFlow.Choice) {
        Thread {
            val orig = Shrink.sizeOf(this, uri)
            runOnUiThread {
                if (orig <= 0) {
                    toast(tr("Could not read the image", "عکس خوانده نشد"))
                } else {
                    val opts = Shrink.options(orig, true)
                    val labels = Array(opts.size) { Shrink.label(opts[it], true) }
                    Dialogs.choose(this, tr("Image — original ", "عکس — حجم اصلی ") + Shrink.fmt(orig), labels, -1) { w ->
                        prepareAndSend(c, uri, opts[w], orig, ch)
                    }
                }
            }
        }.start()
    }

    private fun prepareAndSend(c: Contact, uri: Uri, opt: Shrink.Opt, orig: Long, ch: SendFlow.Choice) {
        toast(tr("Preparing the image…", "در حال آماده‌سازی عکس…"))
        Thread {
            val img = Shrink.prepare(this, uri, opt, orig)
            runOnUiThread {
                if (img == null) {
                    toast(tr("Could not prepare the image. Choose a reduction.", "عکس آماده نشد. یک کاهش انتخاب کن."))
                } else {
                    dispatchImage(c, img, ch)
                }
            }
        }.start()
    }

    private fun dispatchImage(c: Contact, img: ByteArray, ch: SendFlow.Choice) {
        val key: ByteArray? = if (c.level == 1) SessionVault.key(c.id) else null
        if (c.level == 1 && key == null) { unlock(); return }
        val payload = Media.imagePayload(key, img)
        val kb = maxOf(1, img.size / 1024)
        val now = System.currentTimeMillis()

        when (ch.kind) {
            0 -> {
                val parts = Media.smsParts(payload)
                if (parts > Media.MAX_SMS_PARTS) {
                    toast(tr("Too big for SMS. Choose a bigger reduction or go online.", "برای پیامک بزرگ است. کاهش بیشتر یا آنلاین را انتخاب کن."))
                    return
                }
                if (!smsOk(c)) return
                Dialogs.confirm(this, "$kb KB → $parts SMS\n" +
                        tr("Sending takes a while and your operator may charge for each SMS. Send?",
                            "ارسال زمان می‌برد و ممکن است اپراتور برای هر پیامک هزینه بگیرد. ارسال شود؟")) {
                    val st = Media.store(this, img)
                    val id = Messages.insert(this, Msg(0, c.id, st.first, true, now, true, false, 0, 4 or Media.TAG_CHECKED or st.second))
                    try { SimSender.send(this, c.phone, payload, id, ch.subId) } catch (e: Exception) { Messages.setStatus(this, id, 2) }
                    reload()
                }
            }
            1 -> {
                if (payload.size > Online.MAX_PAYLOAD) {
                    toast(tr("Too big for the internal server (max about 8 MB). Choose a smaller quality.",
                        "برای سرور داخلی بزرگ است (حداکثر حدود ۸ مگابایت). کیفیت کمتری انتخاب کن."))
                    return
                }
                if (!onlineReady(c)) return
                toast(tr("Uploading $kb KB…", "در حال ارسال $kb KB…"))
                Thread {
                    val st = Media.store(this, img)
                    val id = Messages.insert(this, Msg(0, c.id, st.first, true, now, true, false, 0, 4 or Media.TAG_CHECKED or st.second))
                    runOnUiThread { reload() }
                    val err = Online.sendTo(this, c, payload, now)
                    try { Messages.setStatus(this, id, if (err == null) 1 else 2) } catch (e: Exception) { }
                    runOnUiThread {
                        if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                        reload()
                    }
                }.start()
            }
            else -> {
                Thread {
                    val st = Media.store(this, img)
                    Messages.insert(this, Msg(0, c.id, st.first, true, now, true, false, 1, 2 or 4 or Media.TAG_CHECKED or st.second))
                    runOnUiThread {
                        reload()
                        shareOut(c, payload, now, ch.format, true)
                    }
                }.start()
            }
        }
    }

    // ---------- voice messages ----------
    private fun recordVoice() {
        val c = contact ?: return
        if (!AvKit.hasMic(this)) {
            AvKit.askMic(this)
            toast(tr("Allow the microphone, then tap again", "میکروفون را اجازه بده، بعد دوباره بزن"))
            return
        }
        askMediaDest(c) { ch ->
            AvKit.recordDialog(this) { data, sec -> sendAv(c, Media.KIND_AUDIO, sec, data, ch) }
        }
    }

    private fun onAudioPicked(uri: Uri) {
        val c = contact ?: return
        askMediaDest(c) { ch ->
            toast(tr("Reading the file…", "در حال خواندن فایل…"))
            Thread {
                val data = try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Throwable) { null }
                val sec = AvKit.audioSeconds(this, uri)
                runOnUiThread {
                    if (data == null || data.isEmpty()) toast(tr("Could not read the file", "فایل خوانده نشد"))
                    else if (data.size > AvKit.MAX_AV) toast(tr("Too big (max about 7 MB)", "خیلی بزرگ است (حداکثر حدود ۷ مگابایت)"))
                    else sendAv(c, Media.KIND_AUDIO, sec, data, ch)
                }
            }.start()
        }
    }

    // ---------- videos ----------
    private fun onVideoPicked(uri: Uri) {
        val c = contact ?: return
        askMediaDest(c) { ch -> chooseVideoQuality(c, uri, ch) }
    }

    private fun chooseVideoQuality(c: Contact, uri: Uri, ch: SendFlow.Choice) {
        Thread {
            val info = AvKit.videoInfo(this, uri)
            val size = Shrink.sizeOf(this, uri)
            runOnUiThread {
                if (info == null) {
                    toast(tr("Could not read the video", "فیلم خوانده نشد"))
                    return@runOnUiThread
                }
                val labels = ArrayList<String>()
                val acts = ArrayList<() -> Unit>()
                val len = AvKit.fmtTime(info.seconds)

                if (size in 1..AvKit.MAX_AV.toLong()) {
                    labels.add("📄 " + tr("Original, not compressed", "اصلی، بدون فشرده‌سازی") + " — " + Shrink.fmt(size) + "  ($len)")
                    acts.add { sendOriginalVideo(c, uri, info.seconds, ch) }
                }
                for (p in AvKit.VIDEO_PRESETS) {
                    val est = AvKit.estimate(info.seconds, p)
                    val tooBig = est > AvKit.MAX_AV
                    labels.add((if (tooBig) "✗ " else "") + p.height + "p — ≈ " + Shrink.fmt(est) + "  ($len)" +
                            (if (tooBig) "  " + tr("too long", "خیلی طولانی") else ""))
                    acts.add {
                        if (tooBig) toast(tr("This video is too long for this quality. Choose a smaller one or a shorter video.",
                            "این فیلم برای این کیفیت خیلی طولانی است. کیفیت کمتر یا فیلم کوتاه‌تر انتخاب کن."))
                        else compressVideo(c, uri, ch, info, p)
                    }
                }
                Dialogs.choose(this, tr("Video quality (max about 7 MB)", "کیفیت فیلم (حداکثر حدود ۷ مگابایت)"), labels.toTypedArray(), -1) { w -> acts[w]() }
            }
        }.start()
    }

    private fun sendOriginalVideo(c: Contact, uri: Uri, seconds: Int, ch: SendFlow.Choice) {
        Thread {
            val data = try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Throwable) { null }
            runOnUiThread {
                if (data == null || data.isEmpty()) toast(tr("Could not read the file", "فایل خوانده نشد"))
                else sendAv(c, Media.KIND_VIDEO, seconds, data, ch)
            }
        }.start()
    }

    private fun compressVideo(c: Contact, uri: Uri, ch: SendFlow.Choice, info: AvKit.VideoInfo, p: AvKit.VideoPreset) {
        val h = if (info.height > 0) minOf(p.height, info.height) else p.height
        val dlg = Neo.dialog(this).setTitle(tr("Compressing the video…", "در حال فشرده‌سازی فیلم…"))
            .setMessage("0%")
            .setCancelable(false)
            .setNegativeButton(tr("Cancel", "انصراف")) { _, _ -> cancelFn?.invoke() }
            .show()
        cancelFn = AvKit.transcode(this, uri, h, p.videoBps, { pct -> dlg.setMessage("$pct%") }) { bytes, err ->
            dlg.dismiss()
            cancelFn = null
            if (bytes == null) {
                toast(err ?: tr("Could not compress the video", "فیلم فشرده نشد"))
            } else if (bytes.size > AvKit.MAX_AV) {
                toast(tr("The result is still too big. Choose a smaller quality.", "نتیجه هنوز بزرگ است. کیفیت کمتری انتخاب کن."))
            } else {
                Dialogs.confirm(this, tr("Final size: ", "حجم نهایی: ") + Shrink.fmt(bytes.size.toLong()) + "\n" + tr("Send?", "ارسال شود؟")) {
                    sendAv(c, Media.KIND_VIDEO, info.seconds, bytes, ch)
                }
            }
        }
    }

    // ---------- sending a voice message or video ----------
    private fun sendAv(c: Contact, kind: Int, seconds: Int, data: ByteArray, ch: SendFlow.Choice) {
        if (data.size > AvKit.MAX_AV) {
            toast(tr("Too big for the internal server", "برای سرور داخلی بزرگ است"))
            return
        }
        val key: ByteArray? = if (c.level == 1) SessionVault.key(c.id) else null
        if (c.level == 1 && key == null) { unlock(); return }
        val wrapped = Media.avWrap(kind, seconds, data)
        val payload = Media.imagePayload(key, wrapped)
        val now = System.currentTimeMillis()
        val base = 4 or Media.TAG_CHECKED or (if (kind == Media.KIND_AUDIO) Media.TAG_AUDIO else Media.TAG_VIDEO)
        val kb = maxOf(1, data.size / 1024)

        if (ch.kind == 1) {
            if (payload.size > Online.MAX_PAYLOAD) {
                toast(tr("Too big for the internal server", "برای سرور داخلی بزرگ است"))
                return
            }
            if (!onlineReady(c)) return
            toast(tr("Uploading $kb KB…", "در حال ارسال $kb KB…"))
            Thread {
                val st = Media.store(this, wrapped)
                val id = Messages.insert(this, Msg(0, c.id, st.first, true, now, true, false, 0, base or st.second))
                Media.setAvInfo(this, id, kind, seconds, data.size)
                runOnUiThread { reload() }
                val err = Online.sendTo(this, c, payload, now)
                try { Messages.setStatus(this, id, if (err == null) 1 else 2) } catch (e: Exception) { }
                runOnUiThread {
                    if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    reload()
                }
            }.start()
        } else {
            Thread {
                val st = Media.store(this, wrapped)
                val id = Messages.insert(this, Msg(0, c.id, st.first, true, now, true, false, 1, 2 or base or st.second))
                Media.setAvInfo(this, id, kind, seconds, data.size)
                runOnUiThread {
                    reload()
                    shareOut(c, payload, now, ch.format, true)
                }
            }.start()
        }
    }

    // ---------- opening a message: photo / voice / video ----------
    private fun saveBytes(bytes: ByteArray, name: String) {
        pendingSave = bytes
        saveLauncher.launch(name)
    }

    private fun openMedia(m: Msg, kind: Int) {
        toast(tr("Opening…", "در حال باز کردن…"))
        Thread {
            val b = Media.imageBytes(this, m)
            runOnUiThread {
                if (b == null) {
                    toast(tr("Cannot open it", "باز نشد"))
                    return@runOnUiThread
                }
                if (kind == Media.KIND_IMAGE) {
                    showImage(m, b)
                } else {
                    val av = Media.avUnwrap(b)
                    if (av == null) {
                        toast(tr("Cannot open it", "باز نشد"))
                    } else if (kind == Media.KIND_AUDIO) {
                        AvKit.showAudio(this, av.data) { saveBytes(av.data, "Shadow-voice-" + m.ts + "." + AvKit.audioExt(av.data)) }
                    } else {
                        AvKit.showVideo(this, av.data) { saveBytes(av.data, "Shadow-video-" + m.ts + ".mp4") }
                    }
                }
            }
        }.start()
    }

    private fun showImage(m: Msg, b: ByteArray) {
        val bmp = Media.decodeScaled(b, 2048)
        if (bmp == null) {
            toast(tr("Cannot open the image", "تصویر باز نشد"))
            return
        }
        val iv = ImageView(this)
        iv.setImageBitmap(bmp)
        iv.adjustViewBounds = true
        val q = Neo.dp(this, 12)
        iv.setPadding(q, q, q, q)
        val ext = when (Media.mime(b)) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        Neo.dialog(this).setView(iv)
            .setPositiveButton(tr("Share…", "ارسال…")) { _, _ ->
                Dialogs.confirm(this, tr("The shared photo is NOT encrypted. Continue?", "عکسِ ارسالی رمزنگاری نیست. ادامه؟")) {
                    Files.share(this, Media.saveImage(this, b), Media.mime(b))
                }
            }
            .setNeutralButton(tr("Save…", "ذخیره…")) { _, _ -> saveBytes(b, "Shadow-" + m.ts + "." + ext) }
            .setNegativeButton(tr("Close", "بستن"), null).show()
    }

    // ---------- export ----------
    private fun exportMenu() {
        val labels = arrayOf(
            tr("Chat → .txt (encrypted)", "گفتگو ← txt (رمزشده)"),
            tr("Chat → .json (encrypted)", "گفتگو ← json (رمزشده)"),
            tr("Chat → readable text (NOT encrypted)", "گفتگو ← متن خوانا (بدون رمز)"))
        Dialogs.choose(this, tr("Export", "اکسپورت"), labels, -1) { w ->
            when (w) {
                0 -> doExport(false, false)
                1 -> doExport(false, true)
                else -> Dialogs.confirm(this, tr("This file will NOT be encrypted. Continue?", "این فایل رمزنگاری نیست. ادامه؟")) { doExport(true, false) }
            }
        }
    }

    private fun doExport(readable: Boolean, json: Boolean) {
        val c = contact ?: return
        toast(tr("Preparing the file…", "در حال ساخت فایل…"))
        Thread {
            val r = Media.exportChat(this, c, readable)
            val f = if (json) Online.txtToJson(r.file) else r.file
            runOnUiThread {
                reload()
                Neo.dialog(this).setTitle(tr("Export ready", "اکسپورت آماده شد"))
                    .setMessage("${r.count} " + tr("messages exported", "پیام خروجی گرفته شد") +
                            (if (r.skipped > 0) "\n${r.skipped} " + tr("skipped (no key)", "رد شد (کلید نیست)") else ""))
                    .setPositiveButton(tr("Send with…", "ارسال با…")) { _, _ ->
                        Files.share(this, f, if (json) "application/json" else "text/plain")
                    }
                    .setNegativeButton(tr("Close", "بستن"), null).show()
            }
        }.start()
    }

    // ---------- import ----------
    private fun importMenu() {
        val c = contact ?: return
        val labels = arrayOf(tr("From file (.txt / .json)", "از فایل (txt / json)"), tr("Paste text", "paste متن"))
        Dialogs.choose(this, tr("Import", "ایمپورت"), labels, -1) { w ->
            if (w == 0) pickLauncher.launch(arrayOf("*/*")) else showReceive(this, c) { reload() }
        }
    }

    private fun onFilePicked(uri: Uri) {
        val c = contact ?: return
        toast(tr("Reading the file…", "در حال خواندن فایل…"))
        Thread {
            val raw = Media.readText(this, uri)
            runOnUiThread {
                if (raw == null) {
                    toast(tr("cannot read file (or it is too big)", "فایل خوانده نشد (یا خیلی بزرگ است)"))
                } else {
                    val labels = arrayOf(tr("from my contact", "از طرف مخاطب من"), tr("my own backup", "پشتیبان خودم"))
                    Dialogs.choose(this, tr("This file is…", "این فایل…"), labels, 0) { w ->
                        Thread {
                            val r = Media.importFile(this, c, Online.normalizeImport(raw), w == 0)
                            runOnUiThread {
                                reload()
                                Dialogs.info(this, tr("Import finished", "ایمپورت انجام شد"),
                                    "${r.imported} " + tr("messages imported", "پیام وارد شد") +
                                            (if (r.ignored > 0) "\n${r.ignored} " + tr("ignored (duplicate/invalid)", "نادیده (تکراری/نامعتبر)") else ""))
                            }
                        }.start()
                    }
                }
            }
        }.start()
    }
}
