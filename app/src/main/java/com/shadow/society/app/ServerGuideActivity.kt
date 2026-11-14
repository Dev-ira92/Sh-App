package com.shadow.society.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Step by step: how to build the server on Cloudflare. Written for a child.
 * Way 1: one button (Deploy to Cloudflare). Way 2: by hand (every command has a Copy button).
 */
class ServerGuideActivity : AppCompatActivity() {
    private var n = 0

    private companion object {
        /** The public GitHub repository with the server files. The button copies it into the user's own accounts. */
        const val DEPLOY_URL = "https://deploy.workers.cloudflare.com/?url=https://github.com/rahimian2022/shadow-server"

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

    private fun heading(col: LinearLayout, s: String) {
        val t = Neo.text(this, s, 12, Neo.ACCENT)
        t.typeface = Neo.BOLD
        t.setPadding(0, Neo.dp(this, 20), 0, Neo.dp(this, 2))
        col.addView(t)
    }

    private fun step(col: LinearLayout, title: String, body: String = "") {
        n++
        val card = Neo.card(this)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val sz = Neo.dp(this, 38)
        val badge = Neo.text(this, "$n", 10, Color.WHITE)
        badge.gravity = Gravity.CENTER
        badge.typeface = Neo.BOLD
        badge.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Neo.ACCENT) }
        row.addView(badge, LinearLayout.LayoutParams(sz, sz))
        val tc = Neo.vertical(this)
        tc.addView(Neo.text(this, title, 10, Neo.TEXT).apply { typeface = Neo.BOLD })
        if (body.isNotEmpty()) {
            tc.addView(Neo.text(this, body, 9, Neo.DIM).apply { setPadding(0, Neo.dp(this@ServerGuideActivity, 4), 0, 0) })
        }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.leftMargin = Neo.dp(this, 12)
        row.addView(tc, lp)
        card.addView(row)
        Neo.add(col, card, 10)
    }

    private fun note(col: LinearLayout, title: String, body: String) {
        val card = Neo.card(this)
        card.addView(Neo.text(this, title, 10, Neo.ACCENT).apply { typeface = Neo.BOLD })
        card.addView(Neo.text(this, body, 9, Neo.DIM).apply { setPadding(0, Neo.dp(this@ServerGuideActivity, 4), 0, 0) })
        Neo.add(col, card, 10)
    }

    private fun codeBlock(col: LinearLayout, label: String, code: String) {
        val card = Neo.card(this)
        card.addView(Neo.text(this, label, 9, Neo.ACCENT).apply { typeface = Neo.BOLD })
        val tv = TextView(this)
        tv.text = code
        tv.typeface = Typeface.MONOSPACE
        tv.textSize = 12f
        tv.setTextColor(Neo.TEXT)
        val p = Neo.dp(this, 8)
        tv.setPadding(p, p, p, p)
        val sv = ScrollView(this)
        sv.addView(tv)
        val box = FrameLayout(this)
        box.background = Neo.box(this, Color.WHITE, Neo.BORDER, 10)
        box.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Neo.dp(this, 120)))
        Neo.add(card, box, 8)
        val copy = compact(Neo.primary(this, tr("Copy", "کپی")))
        Neo.add(card, copy, 8)
        copy.setOnClickListener { Neo.copy(this, code) }
        Neo.add(col, card, 8)
    }

    /** Opens the Cloudflare deploy page in the browser (or copies the link if there is no browser). */
    private fun openDeploy() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DEPLOY_URL)))
        } catch (e: Exception) {
            Neo.copy(this, DEPLOY_URL)
            Toast.makeText(this, tr("No browser found. The link was copied: paste it in a browser.", "مرورگر پیدا نشد. لینک کپی شد: در یک مرورگر paste کن."), Toast.LENGTH_LONG).show()
        }
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
        bar.elevation = Neo.dp(this, 3).toFloat()
        val bp = Neo.dp(this, 4)
        bar.setPadding(bp, bp, bp, bp)
        bar.addView(Icons.button(this, "back", Neo.BARTEXT) { finish() })
        bar.addView(Neo.text(this, tr("Server guide", "راهنمای سرور"), 12, Neo.BARTEXT).apply { typeface = Neo.BOLD })
        outer.addView(bar)

        val sv = ScrollView(this)
        val col = Neo.vertical(this)
        val p = Neo.dp(this, 14)
        col.setPadding(p, p, p, p * 3)
        sv.addView(col)
        outer.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- intro ----
        note(col, tr("What is a server?", "سرور چیست؟"), tr(
            "A server is like a MAILBOX on the internet. Your locked messages wait in it until your friend comes and picks them up. It is free. Only ONE of you two has to build it; your friend just gets the address and the password from you and needs no account at all.\n\nWho needs accounts? Only the person who BUILDS a server: a free Cloudflare account and a free GitHub account.",
            "سرور مثل یک «صندوق پستی» روی اینترنت است. پیام‌های قفل‌شده‌ات آن‌جا می‌مانند تا دوستت بیاید و بردارد. رایگان است. فقط یکی از شما دو نفر باید آن را بسازد؛ دوستت فقط «آدرس» و «رمز» را از تو می‌گیرد و هیچ حسابی لازم ندارد.\n\nچه کسی حساب لازم دارد؟ فقط کسی که سرور را «می‌سازد»: یک حساب رایگان Cloudflare و یک حساب رایگان GitHub."))

        // ================= WAY 1: automatic =================
        heading(col, tr("Way 1 · Automatic (one button, about 5 minutes)", "راه ۱ · خودکار (یک دکمه، حدود ۵ دقیقه)"))
        val auto = Neo.card(this)
        auto.addView(Neo.text(this, tr("Cloudflare builds everything by itself: the Worker, the database and its tables.",
            "Cloudflare خودش همه‌چیز را می‌سازد: Worker، دیتابیس و جدول‌ها."), 10, Neo.TEXT).apply { typeface = Neo.BOLD })
        val go = Neo.primary(this, tr("Create the server automatically", "ساخت خودکار سرور"))
        go.textSize = 16f
        Neo.add(auto, go, 12)
        go.setOnClickListener { openDeploy() }
        val cp = compact(Neo.button(this, tr("Copy the link instead", "به‌جایش لینک را کپی کن")))
        Neo.add(auto, cp, 8)
        cp.setOnClickListener { Neo.copy(this, DEPLOY_URL) }
        Neo.add(col, auto, 8)

        step(col, tr("Press the big button above. The browser of your phone opens a Cloudflare page.",
            "دکمه‌ی بزرگ بالا را بزن. مرورگر گوشی یک صفحه‌ی Cloudflare باز می‌کند."))
        step(col, tr("Log in to Cloudflare. If you have no account, make one (it is free: email + password, then tap the link in the email). Do the same for GitHub when it asks. When Cloudflare asks to connect GitHub, allow it.",
            "وارد Cloudflare شو. اگر حساب نداری بساز (رایگان است: ایمیل و رمز، بعد لینک داخل ایمیل را بزن). وقتی GitHub خواست همین کار را بکن. وقتی Cloudflare اجازه‌ی اتصال به GitHub را خواست، قبول کن."))
        step(col, tr("A form asks for  SERVER_KEY . This is the password of your server. Press the button below to make one, press Copy, and paste it into the form. WRITE IT ON YOUR PAPER: you need it in the app. Do not keep the example text.",
            "یک فرم می‌آید که  SERVER_KEY  می‌خواهد. این «رمز سرور» توست. دکمه‌ی پایین را بزن تا یک رمز بسازد، «کپی» را بزن و در فرم paste کن. حتماً روی کاغذت بنویس، چون در برنامه لازمش داری. متن نمونه را نگه ندار."))
        passwordMaker(col)
        step(col, tr("Leave the other boxes as they are (names like shadow-server and shadow-db). Tap  Deploy  and wait until it says it is done.",
            "بقیه‌ی کادرها را همان‌طور که هست بگذار (اسم‌هایی مثل shadow-server و shadow-db). دکمه‌ی  Deploy  را بزن و صبر کن تا بنویسد تمام شد."))
        step(col, tr("Cloudflare shows the address of your server, like  https://shadow-server.something.workers.dev . Press and hold it and copy it. (You can always find it again: Cloudflare menu → Workers & Pages → shadow-server.)",
            "Cloudflare آدرس سرورت را نشان می‌دهد، شبیه  https://shadow-server.something.workers.dev . انگشتت را روی آن نگه دار و کپی کن. (همیشه می‌توانی دوباره پیدایش کنی: منوی Cloudflare ← Workers & Pages ← shadow-server.)"))
        step(col, tr("Test it: open a new browser tab, paste the address and write  /ping  at the end. You must see only the word  sh-so . If you see it, everything is right!",
            "آزمایش: یک تب جدید مرورگر باز کن، آدرس را paste کن و آخرش  /ping  بنویس. باید فقط کلمه‌ی  sh-so  را ببینی. اگر دیدی، همه‌چیز درست است!"))
        step(col, tr("Back in the app: write the address (without /ping) in \"Server address\" and the password in \"Server password\", then press Register (or, in Online settings, \"Save and register\").",
            "برگرد به برنامه: آدرس را (بدون /ping) در «آدرس سرور» و رمز را در «رمز سرور» بنویس، بعد «ثبت نام» را بزن (یا در تنظیمات آنلاین «ذخیره و ثبت»)."))
        step(col, tr("The app shows a RECOVERY CODE (12 letters and numbers). WRITE IT ON YOUR PAPER. If you ever delete and reinstall the app, you need it to take your number back.",
            "برنامه یک «کد بازیابی» (۱۲ حرف و عدد) نشانت می‌دهد. حتماً روی کاغذت بنویس. اگر روزی برنامه را پاک و دوباره نصب کنی، برای پس گرفتن شماره‌ات لازمش داری."))
        step(col, tr("For your friend: in Online settings press \"Send my server address\" and send it to him. He pastes it into his app. Then save each other's phone numbers as contacts. Done!",
            "برای دوستت: در تنظیمات آنلاین «ارسال آدرس سرور من» را بزن و برایش بفرست. او آن را در برنامه‌ی خودش وارد می‌کند. بعد شماره‌ی همدیگر را به‌عنوان مخاطب ذخیره کنید. تمام!"))

        note(col, tr("If the automatic way stops with an error", "اگر راه خودکار با خطا متوقف شد"), tr(
            "Do not worry. Take a screenshot of the error and use Way 2 below: it does the same by hand and always works.",
            "نگران نشو. از خطا عکس صفحه بگیر و از راه ۲ (پایین) استفاده کن: همین کار را دستی انجام می‌دهد و همیشه جواب می‌دهد."))

        // ================= WAY 2: by hand =================
        n = 0
        heading(col, tr("Way 2 · By hand (only Cloudflare needed, no GitHub)", "راه ۲ · دستی (فقط Cloudflare لازم است، بدون GitHub)"))

        // ---- part 1 ----
        heading(col, tr("Part 1 · Make an account", "بخش ۱ · ساخت حساب"))
        step(col, tr("Open the web browser of your phone (for example Chrome).", "مرورگر گوشی‌ات را باز کن (مثلاً Chrome)."))
        step(col, tr("In the address bar at the top type:  dash.cloudflare.com/sign-up  and press Enter (or Go).",
            "در نوار آدرس بالای صفحه تایپ کن:  dash.cloudflare.com/sign-up  و Enter (یا برو) را بزن."))
        step(col, tr("Write your email. Choose a password and write it on your paper. Press the button Sign up.",
            "ایمیلت را بنویس. یک رمز انتخاب کن و روی کاغذت بنویس. دکمه‌ی Sign up را بزن."))
        step(col, tr("Open your email. There is a letter from Cloudflare. Tap the link inside it (Verify email).",
            "برو توی ایمیلت. نامه‌ای از Cloudflare آمده. روی لینک داخلش (Verify email) بزن."))
        step(col, tr("Go back to dash.cloudflare.com and log in. If it asks you to add a domain, you do NOT need one: ignore it. If you cannot see the menu, tap the three lines (☰) at the top. If it asks for a plan, choose Free.",
            "دوباره برو dash.cloudflare.com و وارد شو. اگر گفت «دامنه اضافه کن» (Add a domain)، ما دامنه نمی‌خواهیم؛ نادیده بگیر. اگر منو دیده نمی‌شود روی علامت سه‌خط (☰) بالای صفحه بزن. اگر پلن پرسید، Free (رایگان) را انتخاب کن."))

        // ---- part 2 ----
        heading(col, tr("Part 2 · Make the database (the place where messages wait)", "بخش ۲ · ساخت دیتابیس (جایی که پیام‌ها می‌مانند)"))
        step(col, tr("In the menu tap  Storage & databases , then  D1 SQL database .",
            "از منو بزن:  Storage & databases  و بعد  D1 SQL database ."))
        step(col, tr("Tap  Create database . For the name write exactly:  shso  (small letters). Then tap Create.",
            "دکمه‌ی  Create database  را بزن. اسمش را دقیقاً بنویس:  shso  (حروف کوچک). بعد Create را بزن."))
        step(col, tr("Tap the name  shso  to open it. At the top tap the tab  Console .",
            "روی اسم  shso  بزن تا باز شود. بالای صفحه تب  Console  را بزن."))
        step(col, tr("Now you give it 3 commands, one by one. Below there are 3 boxes. For each box: press Copy here, go to Cloudflare, tap the big box of the Console, paste, then press Execute. Wait until it says success, come back here and take the next box. (If it says \"already exists\" it was made before. No problem.)",
            "حالا سه دستور را یکی‌یکی به آن می‌دهی. زیر همین مرحله سه کادر هست. برای هر کادر: همین‌جا «کپی» را بزن، برو Cloudflare، توی کادر بزرگ Console بزن و paste کن، بعد دکمه‌ی Execute را بزن. صبر کن بنویسد success، برگرد این‌جا و کادر بعدی را بردار. (اگر نوشت already exists یعنی قبلاً ساخته شده؛ مشکلی نیست.)"))
        codeBlock(col, tr("Command 1", "دستور ۱"), SQL1)
        codeBlock(col, tr("Command 2", "دستور ۲"), SQL2)
        codeBlock(col, tr("Command 3", "دستور ۳"), SQL3)
        note(col, tr("Already built a server before?", "قبلاً سرور ساخته بودی؟"), tr(
            "Then your table is old. Do NOT make it again. Give it these 3 upgrade commands instead (one by one, same way). They add the places for the recovery code.",
            "پس جدولت قدیمی است. دوباره نساز. به‌جایش این ۳ دستور ارتقا را بده (یکی‌یکی، به همان روش). این‌ها جای کد بازیابی را اضافه می‌کنند."))
        codeBlock(col, tr("Upgrade 1", "ارتقا ۱"), UP1)
        codeBlock(col, tr("Upgrade 2", "ارتقا ۲"), UP2)
        codeBlock(col, tr("Upgrade 3", "ارتقا ۳"), UP3)

        // ---- part 3 ----
        heading(col, tr("Part 3 · Make the Worker (the brain of the mailbox)", "بخش ۳ · ساخت Worker (مغز صندوق پستی)"))
        step(col, tr("In the menu tap  Compute (Workers) , then  Workers & Pages .", "از منو بزن:  Compute (Workers)  و بعد  Workers & Pages ."))
        step(col, tr("Tap  Create . Choose the template  Hello World . For the name write:  shadow . Then tap Deploy.",
            "دکمه‌ی  Create  را بزن. قالب  Hello World  را انتخاب کن. اسمش را بنویس:  shadow . بعد Deploy را بزن."))
        step(col, tr("When it is ready tap the button  Edit code . A page with writing opens.",
            "وقتی ساخته شد، دکمه‌ی  Edit code  را بزن. یک صفحه‌ی پر از نوشته باز می‌شود."))
        step(col, tr("Tap inside the writing. Hold your finger, choose Select all, then press the delete key of the keyboard until the page is EMPTY.",
            "داخل نوشته‌ها بزن. انگشتت را نگه دار و «Select all» را بزن، بعد دکمه‌ی پاک‌کردن کیبورد را بزن تا صفحه کاملاً خالی شود."))
        step(col, tr("Below is the box \"Worker code\". Press Copy, go to Cloudflare and paste into the empty page. Paste only ONE time.",
            "زیر همین مرحله کادر «کد Worker» است. «کپی» را بزن، برو Cloudflare و توی صفحه‌ی خالی paste کن. فقط یک بار paste کن."))
        codeBlock(col, tr("Worker code", "کد Worker"), WORKER)
        step(col, tr("At the bottom of the page there is an error counter. It must be 0. If it is not 0, delete everything and paste again. When it is 0, tap  Deploy .",
            "پایین صفحه یک شمارنده‌ی خطا هست. باید صفر باشد. اگر صفر نبود همه را پاک کن و دوباره paste کن. وقتی صفر بود دکمه‌ی  Deploy  را بزن."))

        // ---- part 4 ----
        heading(col, tr("Part 4 · Connect the database to the Worker", "بخش ۴ · وصل کردن دیتابیس به Worker"))
        step(col, tr("Go back to the page of  shadow  (Workers & Pages, then tap shadow). Tap the tab  Settings .",
            "برگرد به صفحه‌ی  shadow  (از Workers & Pages روی shadow بزن). تب  Settings  را بزن."))
        step(col, tr("Find  Bindings . Tap  Add . Choose  D1 database .", "بخش  Bindings  را پیدا کن. روی  Add  بزن. گزینه‌ی  D1 database  را انتخاب کن."))
        step(col, tr("In the box Variable name write exactly:  DB  (two BIG letters). In the database box choose  shso . Then tap Deploy (or Save).",
            "در کادر Variable name دقیقاً بنویس:  DB  (دو حرف بزرگ). در کادر دیتابیس،  shso  را انتخاب کن. بعد Deploy (یا Save) را بزن."))

        // ---- part 5 ----
        heading(col, tr("Part 5 · The password of the server", "بخش ۵ · رمز سرور"))
        step(col, tr("Still in Settings, find  Variables and secrets  and tap  Add .",
            "هنوز در Settings هستی. بخش  Variables and secrets  را پیدا کن و  Add  را بزن."))
        step(col, tr("Set Type to  Secret . For Variable name write exactly:  SERVER_KEY  (big letters). For Value you need a password: press the button below to make one, press Copy and paste it. WRITE IT ON YOUR PAPER: you need it in the app. Then tap Deploy.",
            "Type را روی  Secret  بگذار. در Variable name دقیقاً بنویس:  SERVER_KEY  (حروف بزرگ). برای Value یک رمز لازم است: دکمه‌ی پایین را بزن تا رمز بسازد، «کپی» را بزن و paste کن. حتماً روی کاغذت بنویس، چون در برنامه لازمش داری. بعد Deploy را بزن."))
        passwordMaker(col)

        // ---- part 6 ----
        heading(col, tr("Part 6 · The address of the server", "بخش ۶ · آدرس سرور"))
        step(col, tr("Go to the main page of the Worker (button Overview). You see an address like  https://shadow.something.workers.dev . Press and hold it, and copy it.",
            "برو به صفحه‌ی اصلی Worker (دکمه‌ی Overview). آدرسی شبیه  https://shadow.something.workers.dev  می‌بینی. انگشتت را روی آن نگه دار و کپی کن."))
        step(col, tr("Test it: open a new browser tab, paste the address and write  /ping  at the end. You must see only the word  sh-so . If you see it, everything is right! Then go on with the last steps of Way 1 (write the address and password in the app).",
            "آزمایش: یک تب جدید مرورگر باز کن، آدرس را paste کن و آخرش  /ping  بنویس. باید فقط کلمه‌ی  sh-so  را ببینی. اگر دیدی، همه‌چیز درست است! بعد مرحله‌های آخر راه ۱ را انجام بده (آدرس و رمز را در برنامه بنویس)."))

        // ---- problems ----
        heading(col, tr("If something goes wrong", "اگر مشکلی پیش آمد"))
        note(col, tr("Problems and answers", "مشکل‌ها و جواب‌ها"), tr(
            "• \"This number is already registered\": you registered before. The app asks for your recovery code. Type it. 5 wrong tries lock it for 15 minutes.\n\n• You lost the recovery code: tap \"I lost the code\" in the app. It gives 2 commands. The owner of the server runs them one by one in D1 → Console. Then you register again.\n\n• \"Not found\": (by hand) check that the binding name is exactly DB (big letters), or run the 3 commands again.\n\n• \"Wrong server key\": the password in the app is not the same as SERVER_KEY in Cloudflare.\n\n• \"No connection\": check your internet. The site workers.dev may be blocked on your network: try a VPN.\n\n• The code page shows an error after pasting: tap the three dots of the browser and choose Desktop site, then paste again.",
            "• «این شماره قبلاً ثبت شده»: قبلاً ثبت‌نام کرده‌ای. برنامه کد بازیابی‌ات را می‌خواهد. وارد کن. ۵ بار اشتباه = ۱۵ دقیقه قفل.\n\n• کد بازیابی را گم کرده‌ای: در برنامه «کد را گم کرده‌ام» را بزن. دو دستور می‌دهد. صاحب سرور آن‌ها را یکی‌یکی در D1 ← Console اجرا می‌کند. بعد دوباره ثبت‌نام کن.\n\n• «پیدا نشد»: (در راه دستی) اسم DB را دوباره چک کن (حروف بزرگ)، یا سه دستور را دوباره اجرا کن.\n\n• «کلید سرور اشتباه است»: رمز داخل برنامه با SERVER_KEY در Cloudflare یکی نیست.\n\n• «اتصال برقرار نشد»: اینترنتت را چک کن. شاید workers.dev در شبکه‌ات بسته باشد؛ VPN را امتحان کن.\n\n• بعد از paste، صفحه‌ی کد خطا می‌دهد: سه‌نقطه‌ی مرورگر را بزن و Desktop site را انتخاب کن، بعد دوباره paste کن."))

        setContentView(Neo.backdrop(this, outer))
    }

    /** Makes a random server password (20 letters and numbers) that you can copy. */
    private fun passwordMaker(col: LinearLayout) {
        val card = Neo.card(this)
        val shown = Neo.text(this, "—", 11, Neo.TEXT)
        shown.typeface = Typeface.MONOSPACE
        shown.setTextIsSelectable(true)
        card.addView(shown)
        val make = compact(Neo.button(this, tr("Make a server password", "ساخت رمز سرور")))
        Neo.add(card, make, 8)
        val copy = compact(Neo.primary(this, tr("Copy", "کپی")))
        Neo.add(card, copy, 8)
        make.setOnClickListener { shown.text = Crypto.randomKey(20) }
        copy.setOnClickListener {
            val s = shown.text.toString()
            if (s.length == 20) Neo.copy(this, s)
        }
        Neo.add(col, card, 8)
    }
}
