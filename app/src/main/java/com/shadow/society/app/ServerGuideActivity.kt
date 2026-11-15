package com.shadow.society.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Server guide. A picture of how it works, two ways to build it (one button / by hand), and a timeline of steps.
 * Written for a child. Every command has a Copy button.
 */
class ServerGuideActivity : AppCompatActivity() {
    private var n = 0

    private companion object {
        const val SQL1 = "CREATE TABLE inboxes (id TEXT PRIMARY KEY, read_hash TEXT NOT NULL, created INTEGER NOT NULL, rec_hash TEXT, fails INTEGER DEFAULT 0, lock_until INTEGER DEFAULT 0)"
        const val SQL2 = "CREATE TABLE msgs (seq INTEGER PRIMARY KEY AUTOINCREMENT, inbox TEXT NOT NULL, from_id TEXT, mid TEXT, idx INTEGER, total INTEGER, ts INTEGER, data TEXT NOT NULL, created INTEGER NOT NULL)"
        const val SQL3 = "CREATE INDEX idx_msgs ON msgs (inbox, seq)"
        const val UP1 = "ALTER TABLE inboxes ADD COLUMN rec_hash TEXT"
        const val UP2 = "ALTER TABLE inboxes ADD COLUMN fails INTEGER DEFAULT 0"
        const val UP3 = "ALTER TABLE inboxes ADD COLUMN lock_until INTEGER DEFAULT 0"

        val WORKER = """
const J = (o, s = 200) => new Response(JSON.stringify(o), { status: s, headers: { "content-type": "application/json" } });
async function sha(s) { const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)); return [...new Uint8Array(d)].map(b => b.toString(16).padStart(2, "0")).join(""); }
const okKey = (req, env) => !!env.SERVER_KEY && env.SERVER_KEY.length >= 8 && !env.SERVER_KEY.startsWith("CHANGE-ME") && req.headers.get("X-Key") === env.SERVER_KEY;
const MID = /^[0-9a-f]{16}${'$'}/;
const H64 = /^[0-9a-f]{64}${'$'}/;
async function reg(req, env, id) { if (!okKey(req, env)) return J({ error: "forbidden" }, 403); const b = await req.json(); if (!H64.test(b.read || "")) return J({ error: "bad body" }, 400); const rh = H64.test(b.rec_hash || "") ? b.rec_hash : null; const now = Date.now(); const row = await env.DB.prepare("SELECT read_hash, rec_hash, fails, lock_until FROM inboxes WHERE id = ?1").bind(id).first(); if (!row) { await env.DB.prepare("INSERT INTO inboxes (id, read_hash, created, rec_hash, fails, lock_until) VALUES (?1, ?2, ?3, ?4, 0, 0)").bind(id, b.read, now, rh).run(); return J({ ok: true }); } const mine = (await sha(req.headers.get("X-Read") || "")) === row.read_hash; if (mine) { await env.DB.prepare("UPDATE inboxes SET read_hash = ?2, rec_hash = COALESCE(?3, rec_hash) WHERE id = ?1").bind(id, b.read, rh).run(); return J({ ok: true }); } if (b.force !== true) return J({ error: "taken" }, 409); if (!row.rec_hash) return J({ error: "no recovery" }, 403); if ((row.lock_until || 0) > now) return J({ error: "locked" }, 429); if ((await sha(req.headers.get("X-Rec") || "")) !== row.rec_hash) { const f = (row.fails || 0) + 1; await env.DB.prepare("UPDATE inboxes SET fails = ?2, lock_until = ?3 WHERE id = ?1").bind(id, f >= 5 ? 0 : f, f >= 5 ? now + 900000 : 0).run(); return J({ error: "bad recovery" }, 403); } await env.DB.prepare("DELETE FROM msgs WHERE inbox = ?1").bind(id).run(); await env.DB.prepare("UPDATE inboxes SET read_hash = ?2, rec_hash = COALESCE(?3, rec_hash), fails = 0, lock_until = 0 WHERE id = ?1").bind(id, b.read, rh).run(); return J({ ok: true }); }
async function drop(req, env, id) { if (!okKey(req, env)) return J({ error: "forbidden" }, 403); const row = await env.DB.prepare("SELECT id FROM inboxes WHERE id = ?1").bind(id).first(); if (!row) return J({ error: "no inbox" }, 404); const b = await req.json(); const data = typeof b.data === "string" ? b.data : ""; if (!data) return J({ error: "empty" }, 400); if (data.length > 1100000) return J({ error: "too big" }, 413); const now = Date.now(); await env.DB.prepare("DELETE FROM msgs WHERE created < ?1").bind(now - 3 * 86400000).run(); await env.DB.prepare("INSERT INTO msgs (inbox, from_id, mid, idx, total, ts, data, created) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)").bind(id, String(b.from || "").slice(0, 64), String(b.mid || "").slice(0, 32), Number(b.idx) || 0, Number(b.total) || 1, Number(b.ts) || 0, data, now).run(); return J({ ok: true }); }
async function del(req, env, id) { if (!okKey(req, env)) return J({ error: "forbidden" }, 403); const b = await req.json(); if (!MID.test(b.mid || "")) return J({ error: "bad mid" }, 400); await env.DB.prepare("DELETE FROM msgs WHERE inbox = ?1 AND mid = ?2").bind(id, b.mid).run(); return J({ ok: true }); }
async function readOk(req, env, id) { const row = await env.DB.prepare("SELECT read_hash FROM inboxes WHERE id = ?1").bind(id).first(); if (!row) return 404; return (await sha(req.headers.get("X-Read") || "")) === row.read_hash ? 200 : 403; }
async function poll(req, env, id, url) { const c = await readOk(req, env, id); if (c !== 200) return J({ error: "denied" }, c); await env.DB.prepare("DELETE FROM msgs WHERE created < ?1").bind(Date.now() - 3 * 86400000).run(); const after = Number(url.searchParams.get("after")) || 0; const r = await env.DB.prepare("SELECT seq, from_id AS sender, mid, idx, total, ts, data FROM msgs WHERE inbox = ?1 AND seq > ?2 ORDER BY seq LIMIT 2").bind(id, after).all(); return J({ msgs: r.results.map(m => ({ seq: m.seq, from: m.sender, mid: m.mid, idx: m.idx, total: m.total, ts: m.ts, data: m.data })) }); }
async function ack(req, env, id) { const c = await readOk(req, env, id); if (c !== 200) return J({ error: "denied" }, c); const b = await req.json(); await env.DB.prepare("DELETE FROM msgs WHERE inbox = ?1 AND seq <= ?2").bind(id, Number(b.upto) || 0).run(); const mids = (Array.isArray(b.mids) ? b.mids : []).filter(m => MID.test(m)).slice(0, 20); for (const m of mids) { await env.DB.prepare("DELETE FROM msgs WHERE inbox = ?1 AND mid = ?2").bind(id, m).run(); } return J({ ok: true }); }
async function route(req, env) { const url = new URL(req.url); const p = url.pathname.split("/").filter(Boolean); if (p[0] === "ping") return new Response("sh-so"); const id = p[1] || ""; if (!H64.test(id)) return J({ error: "bad id" }, 400); if (p[0] === "reg" && req.method === "POST") return reg(req, env, id); if (p[0] === "drop" && req.method === "POST") return drop(req, env, id); if (p[0] === "del" && req.method === "POST") return del(req, env, id); if (p[0] === "poll" && req.method === "GET") return poll(req, env, id, url); if (p[0] === "ack" && req.method === "POST") return ack(req, env, id); return J({ error: "not found" }, 404); }
export default { async fetch(req, env) { try { return await route(req, env); } catch (e) { return J({ error: "server error" }, 500); } } };
""".trim()
    }

    private fun dp(v: Int): Int = Neo.dp(this, v)

    // ---------- small building blocks ----------
    private fun soft(fill: Int, stroke: Int): GradientDrawable = Neo.box(this, fill, stroke, 14)

    /** Colored info box. kind 0 = blue tip, 1 = orange warning, 2 = green good news. Returns the box so more can be added. */
    private fun tip(col: LinearLayout, kind: Int, title: String, body: String): LinearLayout {
        val fill = when (kind) { 1 -> 0xFFFFF4E5.toInt(); 2 -> 0xFFE8F5E9.toInt(); else -> 0xFFE8EDFB.toInt() }
        val line = when (kind) { 1 -> 0xFFF5B041.toInt(); 2 -> 0xFF66BB6A.toInt(); else -> 0xFF9DB1F2.toInt() }
        val icon = when (kind) { 1 -> "⚠️"; 2 -> "✅"; else -> "💡" }
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = soft(fill, line)
        val p = dp(12)
        card.setPadding(p, p, p, p)
        card.addView(Neo.text(this, "$icon  $title", 10, Neo.TEXT).apply { typeface = Neo.BOLD })
        if (body.isNotEmpty()) card.addView(Neo.text(this, body, 9, Neo.DIM).apply { setPadding(0, dp(4), 0, 0) })
        Neo.add(col, card, 10)
        return card
    }

    /** A colored section banner with a big emoji. */
    private fun banner(col: LinearLayout, emoji: String, text: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = soft(0xFFE8EDFB.toInt(), 0x00000000)
        val p = dp(12)
        row.setPadding(p, dp(10), p, dp(10))
        row.addView(Neo.text(this, emoji, 14, Neo.TEXT))
        row.addView(Neo.text(this, text, 11, Neo.ACCENT).apply {
            typeface = Neo.BOLD
            setPadding(dp(10), 0, 0, 0)
        })
        Neo.add(col, row, 22)
    }

    /** One step on a vertical timeline: a numbered badge, a line to the next step, and a card. */
    private fun step(col: LinearLayout, title: String, body: String = "", last: Boolean = false, extra: ((LinearLayout) -> Unit)? = null) {
        n++
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val left = FrameLayout(this)
        if (!last) {
            val line = View(this)
            line.setBackgroundColor(Neo.withAlpha(Neo.ACCENT, 70))
            left.addView(line, FrameLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        }
        val sz = dp(34)
        val badge = Neo.text(this, "$n", 9, Color.WHITE)
        badge.gravity = Gravity.CENTER
        badge.typeface = Neo.BOLD
        badge.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Neo.ACCENT) }
        left.addView(badge, FrameLayout.LayoutParams(sz, sz, Gravity.CENTER_HORIZONTAL or Gravity.TOP))
        row.addView(left, LinearLayout.LayoutParams(sz, ViewGroup.LayoutParams.MATCH_PARENT))

        val card = Neo.card(this)
        val cp = dp(12)
        card.setPadding(cp, cp, cp, cp)
        card.addView(Neo.text(this, title, 10, Neo.TEXT).apply { typeface = Neo.BOLD })
        if (body.isNotEmpty()) card.addView(Neo.text(this, body, 9, Neo.DIM).apply { setPadding(0, dp(4), 0, 0) })
        extra?.invoke(card)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.leftMargin = dp(10)
        lp.bottomMargin = dp(10)
        row.addView(card, lp)
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** A box with a command and a Copy button. Put it inside a step or a tip. */
    private fun codeBlock(parent: LinearLayout, label: String, code: String) {
        parent.addView(Neo.text(this, label, 9, Neo.ACCENT).apply { typeface = Neo.BOLD; setPadding(0, dp(10), 0, dp(4)) })
        val tv = TextView(this)
        tv.text = code
        tv.typeface = Typeface.MONOSPACE
        tv.textSize = 12f
        tv.setTextColor(Neo.TEXT)
        val p = dp(8)
        tv.setPadding(p, p, p, p)
        val sv = ScrollView(this)
        sv.addView(tv)
        val box = FrameLayout(this)
        box.background = Neo.box(this, Color.WHITE, Neo.BORDER, 10)
        box.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))
        Neo.add(parent, box, 0)
        val copy = compact(Neo.primary(this, "📋 " + tr("Copy", "کپی")))
        Neo.add(parent, copy, 6)
        copy.setOnClickListener { Neo.copy(this, code) }
    }

    /** Makes a random server password (20 letters and numbers) that you can copy. */
    private fun passwordMaker(parent: LinearLayout) {
        val shown = Neo.text(this, "— — —", 11, Neo.TEXT)
        shown.typeface = Typeface.MONOSPACE
        shown.setTextIsSelectable(true)
        shown.background = soft(0xFFF2F5FB.toInt(), Neo.BORDER)
        shown.setPadding(dp(12), dp(10), dp(12), dp(10))
        shown.gravity = Gravity.CENTER
        Neo.add(parent, shown, 12)
        val make = compact(Neo.button(this, "🎲 " + tr("Make a password", "ساخت رمز")))
        val copy = compact(Neo.primary(this, "📋 " + tr("Copy", "کپی")))
        Neo.add(parent, formRow(this, make to 1f, copy to 1f), 8)
        make.setOnClickListener { shown.text = Crypto.randomKey(20) }
        copy.setOnClickListener {
            val s = shown.text.toString()
            if (s.length == 20) Neo.copy(this, s)
        }
    }

    // ---------- the picture at the top ----------
    private fun node(emoji: String, caption: String): LinearLayout {
        val v = LinearLayout(this)
        v.orientation = LinearLayout.VERTICAL
        v.gravity = Gravity.CENTER
        v.background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(14).toFloat() }
        v.setPadding(dp(10), dp(8), dp(10), dp(8))
        v.addView(Neo.text(this, emoji, 16, Color.WHITE).apply { gravity = Gravity.CENTER })
        v.addView(Neo.text(this, caption, 8, Color.WHITE).apply { gravity = Gravity.CENTER })
        return v
    }

    private fun arrow(): TextView {
        val t = Neo.text(this, "🔒➜", 9, Color.WHITE)
        t.setPadding(dp(4), 0, dp(4), 0)
        return t
    }

    private fun hero(col: LinearLayout) {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Neo.ACCENT, 0xFF7C9BFF.toInt()))
            .apply { cornerRadius = dp(20).toFloat() }
        val p = dp(18)
        card.setPadding(p, p, p, p)
        card.addView(Neo.text(this, tr("Your own private mailbox", "صندوق پستیِ خصوصیِ خودت"), 14, Color.WHITE).apply { typeface = Neo.BOLD })
        card.addView(Neo.text(this, tr(
            "Messages are locked on your phone. The server only keeps locked boxes. It can never open them.",
            "پیام‌ها روی گوشی تو قفل می‌شوند. سرور فقط جعبه‌های قفل‌شده را نگه می‌دارد و هرگز نمی‌تواند بازشان کند."),
            9, 0xE6FFFFFF.toInt()).apply { setPadding(0, dp(6), 0, dp(14)) })
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.addView(node("📱", tr("You", "تو")))
        row.addView(arrow())
        row.addView(node("☁️", tr("Server", "سرور")))
        row.addView(arrow())
        row.addView(node("📱", tr("Friend", "دوست")))
        card.addView(row)
        Neo.add(col, card, 0)
    }

    // ---------- the two ways ----------
    private fun wayCard(col: LinearLayout, emoji: String, title: String, badge: String, needs: String): LinearLayout {
        val card = Neo.card(this)
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(Neo.text(this, emoji, 16, Neo.TEXT))
        top.addView(Neo.text(this, title, 12, Neo.TEXT).apply { typeface = Neo.BOLD; setPadding(dp(10), 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Neo.text(this, badge, 8, Neo.ACCENT).apply {
            typeface = Neo.BOLD
            background = soft(0xFFE8EDFB.toInt(), 0x00000000)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        })
        card.addView(top)
        card.addView(Neo.text(this, needs, 9, Neo.DIM).apply { setPadding(0, dp(6), 0, 0) })
        Neo.add(col, card, 12)
        return card
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Neo.styleWindow(this)
        n = 0

        val outer = Neo.vertical(this)
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Neo.PANEL)
        bar.elevation = dp(3).toFloat()
        val bp = dp(4)
        bar.setPadding(bp, bp, bp, bp)
        bar.addView(Icons.button(this, "back", Neo.BARTEXT) { finish() })
        bar.addView(Neo.text(this, tr("Server guide", "راهنمای سرور"), 12, Neo.BARTEXT).apply { typeface = Neo.BOLD })
        outer.addView(bar)

        val sv = ScrollView(this)
        val col = Neo.vertical(this)
        val p = dp(14)
        col.setPadding(p, p, p, p * 3)
        sv.addView(col)
        outer.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        hero(col)
        tip(col, 0, tr("Who needs accounts?", "چه کسی حساب لازم دارد؟"), tr(
            "Only the person who BUILDS a server (a free Cloudflare account, and for the automatic way also a free GitHub account). Your friend just gets the address and the password from you. No account at all.",
            "فقط کسی که سرور را «می‌سازد» (یک حساب رایگان Cloudflare، و برای راه خودکار یک حساب رایگان GitHub هم). دوستت فقط «آدرس» و «رمز» را از تو می‌گیرد. هیچ حسابی لازم ندارد."))

        // ================= choose a way =================
        val manual = Neo.vertical(this)
        manual.visibility = View.GONE

        val a = wayCard(col, "⚡", tr("Automatic", "خودکار"), tr("about 5 minutes", "حدود ۵ دقیقه"),
            tr("Needs: free Cloudflare + free GitHub account. One button builds everything.",
                "لازم: حساب رایگان Cloudflare و GitHub. یک دکمه همه‌چیز را می‌سازد."))
        val go = Neo.primary(this, "🚀 " + tr("Create the server automatically", "ساخت خودکار سرور"))
        Neo.add(a, go, 12)
        go.setOnClickListener { ServerSetup.open(this) }
        val cp = compact(Neo.button(this, "🔗 " + tr("Copy the link", "کپی لینک")))
        Neo.add(a, cp, 8)
        cp.setOnClickListener { Neo.copy(this, ServerSetup.DEPLOY_URL) }

        val b = wayCard(col, "🛠", tr("By hand", "دستی"), tr("about 20 minutes", "حدود ۲۰ دقیقه"),
            tr("Needs: only a free Cloudflare account. Works when the automatic way does not.",
                "لازم: فقط حساب رایگان Cloudflare. وقتی راه خودکار جواب نداد، این جواب می‌دهد."))
        var open = false
        val show = compact(Neo.button(this, tr("Show the manual steps  ▾", "نمایش مرحله‌های دستی  ▾")))
        Neo.add(b, show, 12)
        show.setOnClickListener {
            open = !open
            manual.visibility = if (open) View.VISIBLE else View.GONE
            show.text = tr("Show the manual steps  ", "نمایش مرحله‌های دستی  ") + (if (open) "▴" else "▾")
        }

        // ================= automatic steps =================
        banner(col, "⚡", tr("Automatic: step by step", "خودکار: قدم به قدم"))
        n = 0
        step(col, "🚀 " + tr("Press the big blue button", "دکمه‌ی بزرگ آبی را بزن"),
            tr("It is at the top (Create the server automatically). The browser of your phone opens a Cloudflare page.",
                "بالای همین صفحه است (ساخت خودکار سرور). مرورگر گوشی یک صفحه‌ی Cloudflare باز می‌کند."))
        step(col, "👤 " + tr("Log in", "وارد شو"),
            tr("Log in to Cloudflare. No account? Make one (free: email + password, then tap the link in the email). Do the same for GitHub when it asks, and allow the connection.",
                "وارد Cloudflare شو. حساب نداری؟ بساز (رایگان: ایمیل و رمز، بعد لینک داخل ایمیل را بزن). وقتی GitHub خواست همین کار را بکن و اتصال را قبول کن."))
        step(col, "🔑 " + tr("Type the server password", "رمز سرور را بنویس"),
            tr("A form asks for SERVER_KEY. Make a password here, copy it, paste it into the form. WRITE IT ON PAPER: you need it in the app. Do not keep the example text.",
                "یک فرم می‌آید که SERVER_KEY می‌خواهد. همین‌جا یک رمز بساز، کپی کن و در فرم paste کن. حتماً روی کاغذ بنویس: در برنامه لازمش داری. متن نمونه را نگه ندار.")) { passwordMaker(it) }
        step(col, "▶️ " + tr("Press Deploy", "Deploy را بزن"),
            tr("Leave the other boxes as they are (names like shadow-server and shadow-db). Tap Deploy and wait until it says it is done.",
                "بقیه‌ی کادرها را همان‌طور که هست بگذار (اسم‌هایی مثل shadow-server و shadow-db). Deploy را بزن و صبر کن تا بنویسد تمام شد."))
        step(col, "🔗 " + tr("Copy the server address", "آدرس سرور را کپی کن"),
            tr("Cloudflare shows it, like https://shadow-server.something.workers.dev. Press and hold it and copy. Lost it? Cloudflare menu → Workers & Pages → shadow-server.",
                "Cloudflare آن را نشان می‌دهد، شبیه https://shadow-server.something.workers.dev. انگشتت را روی آن نگه دار و کپی کن. گمش کردی؟ منوی Cloudflare ← Workers & Pages ← shadow-server."))
        step(col, "✅ " + tr("Test it", "آزمایشش کن"),
            tr("Open a new browser tab, paste the address and write /ping at the end. You must see only the word sh-so. If you see it, the server works!",
                "یک تب جدید مرورگر باز کن، آدرس را paste کن و آخرش /ping بنویس. باید فقط کلمه‌ی sh-so را ببینی. اگر دیدی، سرور کار می‌کند!"))
        step(col, "📲 " + tr("Put them in the app", "در برنامه وارد کن"),
            tr("Address (without /ping) in \"Server address\", password in \"Server password\". Then press Register (or in Online settings: Save and register).",
                "آدرس (بدون /ping) را در «آدرس سرور» و رمز را در «رمز سرور» بنویس. بعد «ثبت نام» را بزن (یا در تنظیمات آنلاین: ذخیره و ثبت)."))
        step(col, "🛟 " + tr("Write down the recovery code", "کد بازیابی را یادداشت کن"),
            tr("The app shows 12 letters and numbers. WRITE THEM ON PAPER. If you ever delete and reinstall the app, you need them to take your number back.",
                "برنامه ۱۲ حرف و عدد نشانت می‌دهد. روی کاغذ بنویس. اگر روزی برنامه را پاک و دوباره نصب کنی، برای پس گرفتن شماره‌ات لازمشان داری."))
        step(col, "👥 " + tr("Tell your friend", "به دوستت بگو"),
            tr("In Online settings press \"Send my server address\" and send it to him. He pastes it into his app. Then save each other's phone numbers as contacts. Done!",
                "در تنظیمات آنلاین «ارسال آدرس سرور من» را بزن و برایش بفرست. او آن را در برنامه‌ی خودش وارد می‌کند. بعد شماره‌ی همدیگر را به‌عنوان مخاطب ذخیره کنید. تمام!"),
            last = true)
        tip(col, 1, tr("The automatic way stopped with an error?", "راه خودکار با خطا متوقف شد؟"),
            tr("Do not worry. Take a screenshot of the error and use the manual way: it does the same by hand and always works.",
                "نگران نشو. از خطا عکس صفحه بگیر و از راه دستی استفاده کن: همین کار را با دست انجام می‌دهد و همیشه جواب می‌دهد."))

        // ================= manual steps (hidden until you tap "Show") =================
        col.addView(manual, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        n = 0
        banner(manual, "🌐", tr("Part 1 · Account", "بخش ۱ · حساب"))
        step(manual, tr("Open the browser", "مرورگر را باز کن"), tr("For example Chrome.", "مثلاً Chrome."))
        step(manual, tr("Go to the sign-up page", "برو به صفحه‌ی ثبت‌نام"),
            tr("In the address bar type dash.cloudflare.com/sign-up and press Enter (or Go).",
                "در نوار آدرس تایپ کن dash.cloudflare.com/sign-up و Enter (یا برو) را بزن."))
        step(manual, tr("Make the account", "حساب را بساز"),
            tr("Write your email. Choose a password and write it on paper. Press Sign up.",
                "ایمیلت را بنویس. یک رمز انتخاب کن و روی کاغذ بنویس. Sign up را بزن."))
        step(manual, tr("Check your email", "ایمیلت را ببین"),
            tr("There is a letter from Cloudflare. Tap the link inside it (Verify email).",
                "نامه‌ای از Cloudflare آمده. روی لینک داخلش (Verify email) بزن."))
        step(manual, tr("Log in", "وارد شو"),
            tr("Go back to dash.cloudflare.com and log in. If it asks to add a domain, you do NOT need one: ignore it. No menu? Tap the three lines (☰). If it asks for a plan, choose Free.",
                "دوباره برو dash.cloudflare.com و وارد شو. اگر گفت «دامنه اضافه کن»، لازم نیست؛ نادیده بگیر. منو دیده نمی‌شود؟ سه‌خط (☰) را بزن. اگر پلن پرسید، Free را انتخاب کن."),
            last = true)

        banner(manual, "🗄", tr("Part 2 · Database (where messages wait)", "بخش ۲ · دیتابیس (جایی که پیام‌ها می‌مانند)"))
        step(manual, tr("Open D1", "D1 را باز کن"),
            tr("In the menu tap Storage & databases, then D1 SQL database.", "از منو بزن Storage & databases و بعد D1 SQL database."))
        step(manual, tr("Create the database", "دیتابیس را بساز"),
            tr("Tap Create database. For the name write exactly shso (small letters). Tap Create.",
                "Create database را بزن. اسمش را دقیقاً بنویس shso (حروف کوچک). Create را بزن."))
        step(manual, tr("Open the Console", "Console را باز کن"),
            tr("Tap the name shso to open it. At the top tap the tab Console.", "روی اسم shso بزن. بالای صفحه تب Console را بزن."))
        step(manual, tr("Give it 3 commands", "سه دستور را به آن بده"),
            tr("One by one: press Copy, go to Cloudflare, tap the big Console box, paste, press Execute. Wait for success, come back for the next. (\"already exists\" = made before, no problem.)",
                "یکی‌یکی: کپی را بزن، برو Cloudflare، توی کادر بزرگ Console بزن و paste کن، Execute را بزن. صبر کن success بنویسد، برگرد برای بعدی. («already exists» یعنی قبلاً ساخته شده، مشکلی نیست.)"),
            last = true) {
            codeBlock(it, tr("Command 1", "دستور ۱"), SQL1)
            codeBlock(it, tr("Command 2", "دستور ۲"), SQL2)
            codeBlock(it, tr("Command 3", "دستور ۳"), SQL3)
        }
        val up = tip(manual, 1, tr("Already built a server before?", "قبلاً سرور ساخته بودی؟"),
            tr("Then your table is old. Do NOT make it again. Give it these 3 upgrade commands instead (one by one). They add the places for the recovery code.",
                "پس جدولت قدیمی است. دوباره نساز. به‌جایش این ۳ دستور ارتقا را (یکی‌یکی) بده. این‌ها جای کد بازیابی را اضافه می‌کنند."))
        codeBlock(up, tr("Upgrade 1", "ارتقا ۱"), UP1)
        codeBlock(up, tr("Upgrade 2", "ارتقا ۲"), UP2)
        codeBlock(up, tr("Upgrade 3", "ارتقا ۳"), UP3)

        n = 0
        banner(manual, "⚙️", tr("Part 3 · Worker (the brain of the mailbox)", "بخش ۳ · Worker (مغز صندوق پستی)"))
        step(manual, tr("Open Workers", "Workers را باز کن"),
            tr("In the menu tap Compute (Workers), then Workers & Pages.", "از منو بزن Compute (Workers) و بعد Workers & Pages."))
        step(manual, tr("Create it", "بسازش"),
            tr("Tap Create. Choose the template Hello World. For the name write shadow. Tap Deploy.",
                "Create را بزن. قالب Hello World را انتخاب کن. اسمش را بنویس shadow. Deploy را بزن."))
        step(manual, tr("Open the code", "کد را باز کن"),
            tr("When it is ready tap Edit code. A page with writing opens.", "وقتی ساخته شد Edit code را بزن. یک صفحه‌ی پر از نوشته باز می‌شود."))
        step(manual, tr("Empty the page", "صفحه را خالی کن"),
            tr("Tap inside the writing. Hold your finger, choose Select all, then press the delete key until the page is EMPTY.",
                "داخل نوشته‌ها بزن. انگشتت را نگه دار، Select all را بزن، بعد پاک‌کردن کیبورد را بزن تا صفحه کاملاً خالی شود."))
        step(manual, tr("Paste the Worker code", "کد Worker را paste کن"),
            tr("Press Copy, go to Cloudflare and paste into the empty page. Paste only ONE time.",
                "کپی را بزن، برو Cloudflare و توی صفحه‌ی خالی paste کن. فقط یک بار paste کن.")) {
            codeBlock(it, tr("Worker code", "کد Worker"), WORKER)
        }
        step(manual, tr("Check and Deploy", "بررسی و Deploy"),
            tr("At the bottom there is an error counter. It must be 0. If not, delete everything and paste again. When it is 0 tap Deploy.",
                "پایین صفحه یک شمارنده‌ی خطا هست. باید صفر باشد. اگر نبود همه را پاک کن و دوباره paste کن. وقتی صفر بود Deploy را بزن."),
            last = true)

        n = 0
        banner(manual, "🔌", tr("Part 4 · Connect the database", "بخش ۴ · وصل کردن دیتابیس"))
        step(manual, tr("Open Settings", "Settings را باز کن"),
            tr("Go back to the page of shadow (Workers & Pages → shadow). Tap the tab Settings.",
                "برگرد به صفحه‌ی shadow (Workers & Pages ← shadow). تب Settings را بزن."))
        step(manual, tr("Add a binding", "یک Binding اضافه کن"),
            tr("Find Bindings. Tap Add. Choose D1 database.", "بخش Bindings را پیدا کن. Add را بزن. D1 database را انتخاب کن."))
        step(manual, tr("Name it DB", "اسمش را DB بگذار"),
            tr("In Variable name write exactly DB (two BIG letters). In the database box choose shso. Tap Deploy (or Save).",
                "در Variable name دقیقاً بنویس DB (دو حرف بزرگ). در کادر دیتابیس shso را انتخاب کن. Deploy (یا Save) را بزن."),
            last = true)

        n = 0
        banner(manual, "🔑", tr("Part 5 · The password of the server", "بخش ۵ · رمز سرور"))
        step(manual, tr("Add a secret", "یک Secret اضافه کن"),
            tr("Still in Settings, find Variables and secrets and tap Add.", "هنوز در Settings هستی. Variables and secrets را پیدا کن و Add را بزن."))
        step(manual, tr("Name it SERVER_KEY", "اسمش را SERVER_KEY بگذار"),
            tr("Type = Secret. Variable name = SERVER_KEY (big letters). For Value make a password here, copy and paste it. WRITE IT ON PAPER: you need it in the app. Tap Deploy.",
                "Type را روی Secret بگذار. Variable name را بنویس SERVER_KEY (حروف بزرگ). برای Value همین‌جا یک رمز بساز، کپی و paste کن. روی کاغذ بنویس: در برنامه لازمش داری. Deploy را بزن."),
            last = true) { passwordMaker(it) }

        n = 0
        banner(manual, "🔗", tr("Part 6 · The address", "بخش ۶ · آدرس سرور"))
        step(manual, tr("Copy the address", "آدرس را کپی کن"),
            tr("Go to the main page of the Worker (Overview). You see an address like https://shadow.something.workers.dev. Press and hold it and copy.",
                "برو به صفحه‌ی اصلی Worker (Overview). آدرسی شبیه https://shadow.something.workers.dev می‌بینی. نگهش دار و کپی کن."))
        step(manual, tr("Test it", "آزمایشش کن"),
            tr("New tab, paste the address, add /ping at the end. You must see only sh-so. Then continue with the last steps of the automatic way (put them in the app).",
                "تب جدید، آدرس را paste کن و آخرش /ping بگذار. باید فقط sh-so ببینی. بعد مرحله‌های آخر راه خودکار را انجام بده (در برنامه وارد کن)."),
            last = true)

        // ================= problems =================
        banner(col, "🧯", tr("If something goes wrong", "اگر مشکلی پیش آمد"))
        tip(col, 0, tr("\"This number is already registered\"", "«این شماره قبلاً ثبت شده»"),
            tr("You registered before. The app asks for your recovery code. Type it. 5 wrong tries lock it for 15 minutes.",
                "قبلاً ثبت‌نام کرده‌ای. برنامه کد بازیابی‌ات را می‌خواهد. وارد کن. ۵ بار اشتباه = ۱۵ دقیقه قفل."))
        tip(col, 0, tr("I lost the recovery code", "کد بازیابی را گم کرده‌ام"),
            tr("Tap \"I lost the code\" in the app. It gives 2 commands. The owner of the server runs them one by one in D1 → Console. Then you register again.",
                "در برنامه «کد را گم کرده‌ام» را بزن. دو دستور می‌دهد. صاحب سرور آن‌ها را یکی‌یکی در D1 ← Console اجرا می‌کند. بعد دوباره ثبت‌نام کن."))
        tip(col, 0, tr("\"Wrong server key\"", "«کلید سرور اشتباه است»"),
            tr("The password in the app is not the same as SERVER_KEY in Cloudflare.",
                "رمز داخل برنامه با SERVER_KEY در Cloudflare یکی نیست."))
        tip(col, 0, tr("\"Not found\" (manual way)", "«پیدا نشد» (راه دستی)"),
            tr("Check that the binding name is exactly DB (big letters), or run the 3 commands again.",
                "اسم DB را دوباره چک کن (حروف بزرگ)، یا سه دستور را دوباره اجرا کن."))
        tip(col, 0, tr("\"No connection\"", "«اتصال برقرار نشد»"),
            tr("Check your internet. The site workers.dev may be blocked on your network: try a VPN.",
                "اینترنتت را چک کن. شاید workers.dev در شبکه‌ات بسته باشد؛ VPN را امتحان کن."))
        tip(col, 0, tr("The code page shows an error after pasting", "بعد از paste صفحه‌ی کد خطا می‌دهد"),
            tr("Tap the three dots of the browser and choose Desktop site, then paste again.",
                "سه‌نقطه‌ی مرورگر را بزن و Desktop site را انتخاب کن، بعد دوباره paste کن."))

        setContentView(Neo.backdrop(this, outer))
    }
}
