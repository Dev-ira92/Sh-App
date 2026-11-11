package com.shadow.society.app

import android.app.Activity
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import java.text.Collator
import java.util.Locale

/** All countries with their dial codes. Names come from Android itself (so they are in your language). */
object Countries {
    private const val DATA = "AD:376 AE:971 AF:93 AG:1268 AI:1264 AL:355 AM:374 AO:244 AR:54 AS:1684 AT:43 AU:61 AW:297 AX:358 AZ:994 " +
            "BA:387 BB:1246 BD:880 BE:32 BF:226 BG:359 BH:973 BI:257 BJ:229 BL:590 BM:1441 BN:673 BO:591 BQ:599 BR:55 BS:1242 BT:975 BW:267 BY:375 BZ:501 " +
            "CA:1 CC:61 CD:243 CF:236 CG:242 CH:41 CI:225 CK:682 CL:56 CM:237 CN:86 CO:57 CR:506 CU:53 CV:238 CW:599 CX:61 CY:357 CZ:420 " +
            "DE:49 DJ:253 DK:45 DM:1767 DO:1809 DZ:213 EC:593 EE:372 EG:20 EH:212 ER:291 ES:34 ET:251 FI:358 FJ:679 FK:500 FM:691 FO:298 FR:33 " +
            "GA:241 GB:44 GD:1473 GE:995 GF:594 GG:44 GH:233 GI:350 GL:299 GM:220 GN:224 GP:590 GQ:240 GR:30 GT:502 GU:1671 GW:245 GY:592 " +
            "HK:852 HN:504 HR:385 HT:509 HU:36 ID:62 IE:353 IL:972 IM:44 IN:91 IO:246 IQ:964 IR:98 IS:354 IT:39 JE:44 JM:1876 JO:962 JP:81 " +
            "KE:254 KG:996 KH:855 KI:686 KM:269 KN:1869 KP:850 KR:82 KW:965 KY:1345 KZ:7 LA:856 LB:961 LC:1758 LI:423 LK:94 LR:231 LS:266 LT:370 LU:352 LV:371 LY:218 " +
            "MA:212 MC:377 MD:373 ME:382 MF:590 MG:261 MH:692 MK:389 ML:223 MM:95 MN:976 MO:853 MP:1670 MQ:596 MR:222 MS:1664 MT:356 MU:230 MV:960 MW:265 MX:52 MY:60 MZ:258 " +
            "NA:264 NC:687 NE:227 NF:672 NG:234 NI:505 NL:31 NO:47 NP:977 NR:674 NU:683 NZ:64 OM:968 " +
            "PA:507 PE:51 PF:689 PG:675 PH:63 PK:92 PL:48 PM:508 PR:1787 PS:970 PT:351 PW:680 PY:595 QA:974 RE:262 RO:40 RS:381 RU:7 RW:250 " +
            "SA:966 SB:677 SC:248 SD:249 SE:46 SG:65 SH:290 SI:386 SJ:47 SK:421 SL:232 SM:378 SN:221 SO:252 SR:597 SS:211 ST:239 SV:503 SX:1721 SY:963 SZ:268 " +
            "TC:1649 TD:235 TG:228 TH:66 TJ:992 TK:690 TL:670 TM:993 TN:216 TO:676 TR:90 TT:1868 TV:688 TW:886 TZ:255 " +
            "UA:380 UG:256 US:1 UY:598 UZ:998 VA:379 VC:1784 VE:58 VG:1284 VI:1340 VN:84 VU:678 WF:681 WS:685 XK:383 YE:967 YT:262 ZA:27 ZM:260 ZW:263"

    class Country(val iso: String, val code: String, val name: String) {
        val flag: String get() =
            String(Character.toChars(0x1F1E6 + (iso[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (iso[1] - 'A')))
        fun label(): String = "$flag  $name  +$code"
    }

    /** Sorted by country name (in the current language). */
    fun all(): List<Country> {
        val loc = Locale(Lang.code)
        val col = Collator.getInstance(loc)
        return DATA.split(" ").filter { it.isNotEmpty() }.map {
            val p = it.split(":")
            val nm = Locale("", p[0]).getDisplayCountry(loc)
            Country(p[0], p[1], if (nm.isEmpty()) p[0] else nm)
        }.sortedWith(Comparator { a, b -> col.compare(a.name, b.name) })
    }

    fun find(iso: String): Country? = all().firstOrNull { it.iso.equals(iso, true) }

    /** "+989121234567" → ("+98", "9121234567"). Falls back to +98. */
    fun splitPhone(full: String): Pair<String, String> {
        val digits = Phones.digits(full)
        val codes = DATA.split(" ").mapNotNull { it.split(":").getOrNull(1) }.toSet()
        for (n in 4 downTo 1) {
            if (digits.length > n && codes.contains(digits.take(n))) return Pair("+" + digits.take(n), digits.drop(n))
        }
        return Pair("+98", digits)
    }
}

/** A list of all countries with a search box. */
fun pickCountry(a: Activity, onPick: (Countries.Country) -> Unit) {
    val all = Countries.all()
    var shown = all
    val labels = ArrayList<String>(all.map { it.label() })
    val adapter = ArrayAdapter(a, android.R.layout.simple_list_item_1, labels)

    val col = Neo.vertical(a)
    val p = Neo.dp(a, 12)
    col.setPadding(p, p, p, 0)
    val search = Neo.input(a, tr("Search country or code", "جستجوی کشور یا کد"))
    Neo.add(col, search)
    val lv = ListView(a)
    lv.adapter = adapter
    col.addView(lv, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        (a.resources.displayMetrics.heightPixels * 0.55f).toInt()))

    val dlg: AlertDialog = Neo.dialog(a).setTitle(tr("Country code", "کد کشور")).setView(col)
        .setNegativeButton(tr("Cancel", "انصراف"), null).create()

    search.doAfterTextChanged {
        val q = it.toString().trim().removePrefix("+")
        shown = if (q.isEmpty()) all else all.filter { c -> c.name.contains(q, true) || c.code.startsWith(q) || c.iso.equals(q, true) }
        labels.clear()
        labels.addAll(shown.map { c -> c.label() })
        adapter.notifyDataSetChanged()
    }
    lv.setOnItemClickListener { _, _, pos, _ ->
        dlg.dismiss()
        if (pos in shown.indices) onPick(shown[pos])
    }
    dlg.show()
}
