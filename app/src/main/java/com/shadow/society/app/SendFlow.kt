package com.shadow.society.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** SMS permission helper: explains the "restricted settings" step needed on Android 13+ for sideloaded apps. */
object SmsPerm {
    @Volatile private var shown = false

    private fun granted(a: Activity, p: String) =
        ContextCompat.checkSelfPermission(a, p) == PackageManager.PERMISSION_GRANTED

    fun ok(a: Activity): Boolean = granted(a, Manifest.permission.SEND_SMS) && granted(a, Manifest.permission.RECEIVE_SMS)

    /** Returns true if SMS is allowed. Otherwise shows the guide (once, unless force) and returns false. */
    fun ensure(a: Activity, force: Boolean): Boolean {
        if (ok(a)) return true
        if (!force && shown) return false
        shown = true
        val perms = ArrayList<String>()
        perms.add(Manifest.permission.SEND_SMS)
        perms.add(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        Neo.dialog(a).setTitle(tr("SMS permission", "مجوز پیامک"))
            .setMessage(tr(
                "To send and receive SMS the app needs the SMS permission.\n\n" +
                        "1) Tap Allow.\n" +
                        "2) If it is refused (Android 13+, app installed outside the store): App settings → ⋮ menu (top right) or the item \"Allow restricted settings\" → confirm with fingerprint/PIN.\n" +
                        "3) Then Permissions → SMS → Allow.",
                "برای ارسال و دریافت پیامک، برنامه مجوز پیامک لازم دارد.\n\n" +
                        "۱) «اجازه» را بزن.\n" +
                        "۲) اگر رد شد (اندروید ۱۳ به بعد، برنامه‌ی نصب‌شده بیرون از فروشگاه): «تنظیمات برنامه» ← منوی ⋮ بالا سمت راست، یا گزینه‌ی «Allow restricted settings» ← با اثر انگشت/پین تأیید کن.\n" +
                        "۳) بعد Permissions ← SMS ← Allow."))
            .setPositiveButton(tr("Allow", "اجازه")) { _, _ ->
                ActivityCompat.requestPermissions(a, perms.toTypedArray(), 7)
            }
            .setNeutralButton(tr("App settings", "تنظیمات برنامه")) { _, _ -> openSettings(a) }
            .setNegativeButton(tr("Later", "بعداً"), null)
            .show()
        return false
    }

    fun openSettings(a: Activity) {
        a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName)))
    }
}

/** A screen that can import raw text (QR scan, file / QR picture, paste), used for keys' addresses and invites. */
abstract class OnlineBase : BaseActivity() {
    private var rawCb: ((String) -> Unit)? = null

    private val rawFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            Thread {
                val t = Online.readAny(this, uri)
                runOnUiThread {
                    if (t == null) {
                        Toast.makeText(this, tr("Nothing readable in this file", "چیزی در این فایل خوانده نشد"), Toast.LENGTH_LONG).show()
                    } else {
                        rawCb?.invoke(t)
                    }
                }
            }.start()
        }
    }

    fun importRaw(title: String, cb: (String) -> Unit) {
        rawCb = cb
        val labels = arrayOf(tr("Scan QR", "اسکن QR"), tr("File or QR picture", "فایل یا عکس QR"), tr("Paste text", "paste متن"))
        Dialogs.choose(this, title, labels, -1) { w ->
            when (w) {
                0 -> scanKey { cb(it) }
                1 -> rawFile.launch(arrayOf("text/*", "application/json", "image/*"))
                else -> pasteDialog(title, cb)
            }
        }
    }

    private fun pasteDialog(title: String, cb: (String) -> Unit) {
        val col = Neo.vertical(this)
        val p = Neo.dp(this, 20)
        col.setPadding(p, p / 2, p, 0)
        val e = Neo.input(this, "SHSOADDR1|…  /  SHSOSRV1|…  /  { … }").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4
            gravity = Gravity.TOP
        }
        Neo.add(col, e)
        Neo.dialog(this).setTitle(title).setView(col)
            .setPositiveButton("OK") { _, _ ->
                val s = e.text.toString().trim()
                if (s.isNotEmpty()) cb(s)
            }
            .setNegativeButton(tr("Cancel", "انصراف"), null).show()
    }
}

/** The questions asked every time you press send. */
object SendFlow {
    /** kind: 0 = SMS (subId = SIM), 1 = internal server, 2 = other app (asFile = txt file, else text). */
    class Choice(val kind: Int, val subId: Int = -1, val asFile: Boolean = false)

    fun ask(a: BaseActivity, c: Contact, cb: (Choice) -> Unit) {
        val labels = arrayOf(tr("📶 SMS (offline)", "📶 پیامک (آفلاین)"), tr("🌐 Online", "🌐 آنلاین"))
        Dialogs.choose(a, tr("Send through", "ارسال از طریق"), labels, -1) { w ->
            if (w == 0) pickSim(a, c) { sub -> cb(Choice(0, sub)) } else askFormat(a, cb)
        }
    }

    private fun pickSim(a: BaseActivity, c: Contact, cb: (Int) -> Unit) {
        val def = Sims.current(a, c.id)
        if (!Sims.has(a)) {
            ActivityCompat.requestPermissions(a, arrayOf(Manifest.permission.READ_PHONE_STATE), 8)
            Toast.makeText(a, tr("Allow the phone permission to choose a SIM, then tap send again",
                "برای انتخاب سیم‌کارت اجازه‌ی «تلفن» را بده و دوباره ارسال را بزن"), Toast.LENGTH_LONG).show()
            return
        }
        val sims = Sims.list(a)
        if (sims.size <= 1) {
            cb(if (sims.size == 1) sims[0].subId else def)
            return
        }
        Dialogs.choose(a, tr("Send with which SIM?", "با کدام سیم‌کارت؟"),
            Array(sims.size) { sims[it].label }, -1) { w -> cb(sims[w].subId) }
    }

    private fun askFormat(a: BaseActivity, cb: (Choice) -> Unit) {
        val labels = arrayOf(tr("📝 Text", "📝 متن"), tr("📄 File (.txt)", "📄 فایل (.txt)"))
        Dialogs.choose(a, tr("Send as", "ارسال به صورت"), labels, -1) { f -> askDest(a, f == 1, cb) }
    }

    private fun askDest(a: BaseActivity, file: Boolean, cb: (Choice) -> Unit) {
        val labels = arrayOf(tr("🏠 Internal server", "🏠 سرور داخلی"), tr("📤 Other app (Telegram, …)", "📤 برنامه‌ی دیگر (تلگرام، …)"))
        Dialogs.choose(a, tr("Deliver with", "تحویل با"), labels, -1) { d ->
            cb(if (d == 0) Choice(1, asFile = file) else Choice(2, asFile = file))
        }
    }

    fun shareText(a: Activity, s: String) {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, s)
        a.startActivity(Intent.createChooser(i, tr("Send with...", "ارسال با...")))
    }
}
