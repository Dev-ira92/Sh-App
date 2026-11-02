package com.shadow.society.app

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
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
import java.io.File

class ChatActivity : BaseActivity() {
    private var contact: Contact? = null
    private var contactId = -1L
    private lateinit var adapter: ChatAdapter
    private lateinit var input: EditText
    private lateinit var header: TextView
    private lateinit var counter: TextView
    private lateinit var chip: TextView

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    private val imgLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImagePicked(uri)
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
        bar.setPadding(p / 3, p / 3, p / 3, p / 3)
        bar.addView(Icons.button(this, "back", Neo.BARTEXT) { finish() })
        header = Neo.text(this, "", 15, Neo.BARTEXT)
        header.typeface = Neo.BOLD
        bar.addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val simBtn = Neo.text(this, "SIM", 12, Neo.BARTEXT)
        simBtn.background = Neo.box(this, 0, Neo.BARTEXT, 6)
        simBtn.setPadding(Neo.dp(this, 8), Neo.dp(this, 4), Neo.dp(this, 8), Neo.dp(this, 4))
        simBtn.setOnClickListener { chooseSim() }
        bar.addView(simBtn)

        bar.addView(Icons.button(this, "key", Neo.BARTEXT) { unlock() })
        bar.addView(Icons.button(this, "download", Neo.BARTEXT) { importMenu() })
        bar.addView(Icons.button(this, "upload", Neo.BARTEXT) { exportMenu() })
        root.addView(bar)

        val lv = ListView(this)
        lv.setBackgroundColor(0)
        lv.cacheColorHint = 0
        lv.divider = null
        lv.selector = ColorDrawable(0)
        lv.isStackFromBottom = true
        lv.transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
        adapter = ChatAdapter(this) { m -> viewImage(m) }
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
        row.setPadding(p / 2, p / 2, p / 2, p / 2)

        row.addView(Icons.button(this, "add", Neo.ACCENT) { pickImage() })

        input = Neo.input(this, tr("Message", "پیام"))
        input.maxLines = 4
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val cp = Neo.dp(this, 8)
        chip = Neo.text(this, "", 11, Neo.ACCENT)
        chip.background = Neo.box(this, 0, Neo.ACCENT, 10)
        chip.setPadding(cp, cp / 2, cp, cp / 2)
        chip.setOnClickListener { chooseChannel() }
        val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        clp.setMargins(p / 3, 0, 0, 0)
        row.addView(chip, clp)

        val sendBtn = Icons.button(this, "send", Neo.P.onAccent) { send() }
        val oval = GradientDrawable()
        oval.shape = GradientDrawable.OVAL
        oval.setColor(Neo.ACCENT)
        sendBtn.background = oval
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
        header.text = c.name + "\n" + c.phone + "  " + (if (c.level == 1) "🔴" else "🟢")
        updateChip()
        updateCounter(input.text.toString())
        reload()
        Inbox.listener = Runnable { reload() }
        Thread {
            if (Inbox.drain(this) > 0) runOnUiThread { reload() }
            ImgStore.sweep(this)
        }.start()
    }

    override fun onPause() {
        super.onPause()
        Inbox.listener = null
    }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun reload() {
        val c = contact ?: return
        if (!SessionVault.isUnlocked()) return
        Messages.markRead(this, c.id)
        adapter.set(Messages.list(this, c.id))
    }

    // ---------- channel ----------
    private fun channel(): Int = Prefs.int(this, "chan_c$contactId", 0)

    private fun updateChip() {
        chip.text = if (channel() == 0) "📶 SMS" else "📄 TXT"
    }

    private fun chooseChannel() {
        val labels = arrayOf(
            tr("📶 SMS (offline)", "📶 پیامک (آفلاین)"),
            tr("📄 TXT file → send with any app", "📄 فایل txt ← ارسال با هر برنامه"))
        Dialogs.choose(this, tr("Send through", "ارسال از طریق"), labels, channel()) { w ->
            Prefs.putInt(this, "chan_c$contactId", w)
            updateChip()
            updateCounter(input.text.toString())
        }
    }

    private fun updateCounter(t: String) {
        val c = contact ?: return
        val bytes = t.toByteArray().size + 1 + (if (c.level == 1) 28 else 0)
        val parts = if (t.isEmpty()) "" else if (channel() == 0) "   ~" + Codec.estimateParts(bytes) + " SMS" else "   TXT"
        counter.text = if (c.level == 1) "🔴 AES-256-GCM$parts"
                       else "🟢 " + tr("plain text — NOT secret", "متن ساده — محرمانه نیست") + parts
    }

    // ---------- SIM ----------
    private fun chooseSim() {
        val c = contact ?: return
        Sims.choose(this, "sim_c${c.id}", true) {
            toast(tr("SIM: ", "سیم‌کارت: ") + Sims.label(this, Sims.current(this, c.id)))
        }
    }

    // ---------- keys ----------
    private fun unlock() {
        val c = contact ?: return
        showUnlock(this, c, this::scanKey) { reload() }
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

        if (channel() == 1) {
            val now = System.currentTimeMillis()
            val f = Files.exportMessage(this, c, payload, now)
            Messages.insert(this, Msg(0, c.id, text, true, now, true, false, 1, 2))
            input.setText("")
            reload()
            offerShare(f, tr("1 message exported as a file", "۱ پیام به صورت فایل خروجی گرفته شد"))
            return
        }

        if (!Perms.hasSend(this)) { Perms.request(this); return }
        val id = Messages.insert(this, Msg(0, c.id, text, true, System.currentTimeMillis(), true, false, 0, 0))
        try {
            SimSender.send(this, c.phone, payload, id, Sims.current(this, c.id))
        } catch (e: Exception) {
            Messages.setStatus(this, id, 2)
        }
        input.setText("")
        reload()
    }

    // ---------- images ----------
    private fun pickImage() {
        val c = contact ?: return
        if (c.level == 1 && SessionVault.key(c.id) == null) { unlock(); return }
        imgLauncher.launch("image/*")
    }

    private fun onImagePicked(uri: Uri) {
        val c = contact ?: return
        val sms = channel() == 0
        val labels = Array(Media.PRESETS.size) {
            val p = Media.PRESETS[it]
            if (p.bytes > 0) p.label() + "  ≈ " + (p.bytes / 1000) + " KB  ·  ~" + Codec.estimateParts(p.bytes + 30) + " SMS"
            else p.label()
        }
        Dialogs.choose(this, tr("Image size", "اندازه‌ی عکس"), labels, 1) { w ->
            val preset = Media.PRESETS[w]
            if (sms && !preset.smsOk) {
                toast(tr("This size is for TXT files only. Switch the channel to TXT.", "این اندازه فقط برای فایل txt است. مسیر را روی TXT بگذار."))
                return@choose
            }
            if (preset.mode == 2) {
                Dialogs.confirm(this, tr("The original file also contains the photo's location (GPS) and camera details, and it will be sent as it is. Continue?",
                    "فایل اصلی شامل موقعیت مکانی (GPS) و مشخصات دوربین هم هست و همان‌طور که هست فرستاده می‌شود. ادامه؟")) {
                    prepareAndSend(c, uri, preset, sms)
                }
            } else {
                prepareAndSend(c, uri, preset, sms)
            }
        }
    }

    private fun prepareAndSend(c: Contact, uri: Uri, preset: Media.Preset, sms: Boolean) {
        toast(tr("Preparing the image…", "در حال آماده‌سازی عکس…"))
        Thread {
            val img = Media.prepare(this, uri, preset)
            runOnUiThread {
                if (img == null) {
                    toast(tr("Could not read the image, or it is larger than 8 MB. Try 'Full size'.",
                        "عکس خوانده نشد یا بزرگ‌تر از ۸ مگابایت است. «اندازه‌ی کامل» را امتحان کن."))
                } else {
                    sendImage(c, img, sms)
                }
            }
        }.start()
    }

    private fun sendImage(c: Contact, img: ByteArray, sms: Boolean) {
        val key: ByteArray? = if (c.level == 1) SessionVault.key(c.id) else null
        if (c.level == 1 && key == null) { unlock(); return }
        val payload = Media.imagePayload(key, img)
        val kb = maxOf(1, img.size / 1024)
        val warn = if (c.level == 1) "" else "\n\n" + tr("⚠ This contact is at the plain (Shadow) level: the image is NOT encrypted.",
            "⚠ این مخاطب سطح ساده (سایه) است: عکس رمزنگاری نمی‌شود.")

        if (!sms) {
            Thread {
                val now = System.currentTimeMillis()
                val f = Media.exportImage(this, c, payload, now)
                val (body, extra) = Media.store(this, img)
                Messages.insert(this, Msg(0, c.id, body, true, now, true, false, 1, 2 or 4 or extra))
                runOnUiThread {
                    reload()
                    offerShare(f, "$kb KB " + tr("image exported as a file", "عکس به صورت فایل خروجی گرفته شد") + warn)
                }
            }.start()
            return
        }

        val parts = Media.smsParts(payload)
        if (parts > Media.MAX_SMS_PARTS) {
            toast(tr("Too big for SMS. Choose a smaller size or the TXT channel.", "برای پیامک بزرگ است. اندازه‌ی کوچک‌تر یا مسیر TXT را انتخاب کن."))
            return
        }
        if (!Perms.hasSend(this)) { Perms.request(this); return }
        Dialogs.confirm(this, "$kb KB → $parts SMS\n" +
                tr("Sending takes a while and your operator may charge for each SMS. Send?",
                    "ارسال زمان می‌برد و ممکن است اپراتور برای هر پیامک هزینه بگیرد. ارسال شود؟") + warn) {
            val (body, extra) = Media.store(this, img)
            val id = Messages.insert(this, Msg(0, c.id, body, true, System.currentTimeMillis(), true, false, 0, 4 or extra))
            try {
                SimSender.send(this, c.phone, payload, id, Sims.current(this, c.id))
            } catch (e: Exception) {
                Messages.setStatus(this, id, 2)
            }
            reload()
        }
    }

    private fun viewImage(m: Msg) {
        toast(tr("Opening…", "در حال باز کردن…"))
        Thread {
            val b = Media.imageBytes(this, m)
            val bmp = if (b != null) Media.decodeScaled(b, 2048) else null
            runOnUiThread {
                if (b == null || bmp == null) {
                    toast(tr("Cannot open the image", "تصویر باز نشد"))
                    return@runOnUiThread
                }
                val iv = ImageView(this)
                iv.setImageBitmap(bmp)
                iv.adjustViewBounds = true
                val q = Neo.dp(this, 12)
                iv.setPadding(q, q, q, q)
                Neo.dialog(this).setView(iv)
                    .setPositiveButton(tr("Share…", "ارسال…")) { _, _ ->
                        Dialogs.confirm(this, tr("The shared photo is NOT encrypted. Continue?", "عکسِ ارسالی رمزنگاری نیست. ادامه؟")) {
                            Files.share(this, Media.saveImage(this, b), Media.mime(b))
                        }
                    }
                    .setNegativeButton(tr("Close", "بستن"), null).show()
            }
        }.start()
    }

    // ---------- export ----------
    private fun exportMenu() {
        val labels = arrayOf(
            tr("Chat → raw file (encrypted)", "گفتگو ← فایل خام (رمزشده)"),
            tr("Chat → readable text (NOT encrypted)", "گفتگو ← متن خوانا (بدون رمز)"),
            tr("Typed message → file", "پیام نوشته‌شده ← فایل"))
        Dialogs.choose(this, tr("Export", "خروجی"), labels, -1) { w ->
            when (w) {
                0 -> doExport(false)
                1 -> Dialogs.confirm(this, tr("This file will NOT be encrypted. Continue?", "این فایل رمزنگاری نیست. ادامه؟")) { doExport(true) }
                else -> exportTyped()
            }
        }
    }

    private fun doExport(readable: Boolean) {
        val c = contact ?: return
        toast(tr("Preparing the file…", "در حال ساخت فایل…"))
        Thread {
            val r = Media.exportChat(this, c, readable)
            runOnUiThread {
                reload()
                offerShare(r.file, "${r.count} " + tr("messages exported", "پیام خروجی گرفته شد") +
                        (if (r.skipped > 0) "\n${r.skipped} " + tr("skipped (no key)", "رد شد (کلید نیست)") else ""))
            }
        }.start()
    }

    private fun exportTyped() {
        val c = contact ?: return
        val text = input.text.toString().trim()
        if (text.isEmpty()) { toast(tr("type a message first", "اول پیام را بنویس")); return }
        val payload = textPayload(c, text) ?: return
        val now = System.currentTimeMillis()
        val f = Files.exportMessage(this, c, payload, now)
        Messages.insert(this, Msg(0, c.id, text, true, now, true, false, 1, 2))
        input.setText("")
        reload()
        offerShare(f, tr("1 message exported as a file", "۱ پیام به صورت فایل خروجی گرفته شد"))
    }

    private fun offerShare(f: File, msg: String) {
        Neo.dialog(this).setTitle(tr("Export ready", "خروجی آماده شد")).setMessage(msg)
            .setPositiveButton(tr("Send with…", "ارسال با…")) { _, _ -> Files.share(this, f) }
            .setNegativeButton(tr("Close", "بستن"), null).show()
    }

    // ---------- import ----------
    private fun importMenu() {
        val c = contact ?: return
        val labels = arrayOf(tr("From file (.txt)", "از فایل (.txt)"), tr("Paste text", "paste متن"))
        Dialogs.choose(this, tr("Import", "ایمپورت"), labels, -1) { w ->
            if (w == 0) pickLauncher.launch(arrayOf("*/*")) else showReceive(this, c) { reload() }
        }
    }

    private fun onFilePicked(uri: Uri) {
        val c = contact ?: return
        toast(tr("Reading the file…", "در حال خواندن فایل…"))
        Thread {
            val text = Media.readText(this, uri)
            runOnUiThread {
                if (text == null) {
                    toast(tr("cannot read file (or it is too big)", "فایل خوانده نشد (یا خیلی بزرگ است)"))
                } else {
                    val labels = arrayOf(tr("from my contact", "از طرف مخاطب من"), tr("my own backup", "پشتیبان خودم"))
                    Dialogs.choose(this, tr("This file is…", "این فایل…"), labels, 0) { w ->
                        Thread {
                            val r = Media.importFile(this, c, text, w == 0)
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
