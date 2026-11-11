package com.shadow.society.app

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/** Registering my number on my server. Knows how to take over a number that was registered before (re-install). */
object OnlineReg {
    /** code: 200 = ok, 409 = this number is registered by an older install, 0 = missing settings, else HTTP / -1. */
    class Result(val code: Int, val error: String?)

    private fun sha(s: String): String = Crypto.hex(Crypto.sha256(s.toByteArray()))

    /** Run off the main thread. force = delete the old registration (and its waiting messages) and take over. */
    fun register(c: Context, force: Boolean): Result {
        val inbox = Online.myInbox(c) ?: return Result(0, tr("Enter your phone number first", "اول شماره‌ی خودت را وارد کن"))
        val s = Online.myServer(c) ?: return Result(0, tr("Set the server first", "اول سرور را تنظیم کن"))
        val same = Online.meta(c, "ol_read_url") == s.u
        val tok = if (same) (Online.meta(c, "ol_read") ?: Crypto.hex(Crypto.random(24))) else Crypto.hex(Crypto.random(24))
        val body = JSONObject().put("read", sha(tok)).put("force", force).toString()
        val r = Net.request("POST", s.u + "/reg/" + inbox, mapOf("X-Key" to s.k, "X-Read" to tok), body)
        if (r.code == 409) return Result(409, null)
        if (r.code != 200) return Result(r.code, Online.err(r))
        Online.setMeta(c, "ol_read", tok)
        Online.setMeta(c, "ol_read_url", s.u)
        if (Online.meta(c, "ol_seq") == null) Online.setMeta(c, "ol_seq", "0")
        return Result(200, null)
    }
}

/**
 * Registers and, if the number was registered before, asks: "delete the old registration and stay active?".
 * done(null) = online is ready, done(text) = something went wrong / cancelled.
 */
fun registerOnline(a: AppCompatActivity, done: (String?) -> Unit) {
    Thread {
        val r = OnlineReg.register(a, false)
        a.runOnUiThread {
            when (r.code) {
                200 -> done(null)
                409 -> Neo.dialog(a)
                    .setTitle(tr("This number is already registered on this server", "این شماره قبلاً روی این سرور ثبت شده"))
                    .setMessage(tr(
                        "You registered with this number on this server before (maybe from an earlier install). To keep going, the old registration and its waiting messages must be deleted from the server. Your new account then stays active. Continue?",
                        "قبلاً با این شماره روی این سرور ثبت شده‌ای (شاید از نصب قبلی). برای ادامه، ثبت قبلی و پیام‌های تحویل‌نشده‌اش باید از سرور پاک شود. بعدش حساب جدیدت فعال می‌ماند. ادامه می‌دهی؟"))
                    .setCancelable(false)
                    .setPositiveButton(tr("Yes, delete and activate", "بله، پاک کن و فعال کن")) { _, _ ->
                        Thread {
                            val r2 = OnlineReg.register(a, true)
                            a.runOnUiThread {
                                done(when (r2.code) {
                                    200 -> null
                                    409 -> tr("The server must be updated first (see the Server guide).", "اول باید Worker سرور به‌روز شود (راهنمای سرور).")
                                    else -> r2.error ?: "error"
                                })
                            }
                        }.start()
                    }
                    .setNegativeButton(tr("No, cancel", "نه، لغو")) { _, _ ->
                        done(tr("Cancelled. Online stays off until you register.", "لغو شد. آنلاین تا وقتی ثبت نکنی خاموش می‌ماند."))
                    }
                    .show()
                else -> done(r.error ?: "error")
            }
        }
    }.start()
}
