package com.shadow.society.app

import android.content.ContentValues
import android.content.Context
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper

class Contact(var id: Long, var name: String, var phone: String, var level: Int, var createdAt: Long)

/**
 * body = plain text, or Base64(IV+ciphertext) when cipher = true.
 * status: 0 pending, 1 sent, 2 failed, 3 delivered.   tag bits: 1 imported, 2 exported.
 */
class Msg(var id: Long, var contactId: Long, var body: String, var sent: Boolean, var ts: Long,
          var read: Boolean, var cipher: Boolean, var status: Int = 1, var tag: Int = 0)

/** SQLCipher database (whole file AES-256). Key lives in RAM only (SessionVault). */
class Db private constructor(c: Context) : SQLiteOpenHelper(c, NAME, null, 2) {

    companion object {
        const val NAME = "shso.db"
        private var inst: Db? = null

        @Synchronized fun get(c: Context): Db {
            if (inst == null) {
                SQLiteDatabase.loadLibs(c.applicationContext)
                inst = Db(c.applicationContext)
            }
            return inst!!
        }

        @Synchronized fun close() {
            try { inst?.close() } catch (e: Exception) { }
            inst = null
        }

        /** Try to unlock with this key. Wrong key = false. */
        fun open(c: Context, key: String): Boolean {
            SessionVault.unlock(key)
            return try { get(c).db(); true } catch (e: Exception) { SessionVault.lock(); false }
        }
    }

    fun db(): SQLiteDatabase = getWritableDatabase(SessionVault.dbKey())

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE contacts(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                "phone TEXT NOT NULL UNIQUE, trust_level INTEGER NOT NULL DEFAULT 0, created_at INTEGER)")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, contact_id INTEGER NOT NULL, " +
                "body TEXT NOT NULL, is_sent INTEGER NOT NULL, timestamp INTEGER NOT NULL, " +
                "is_read INTEGER NOT NULL DEFAULT 0, is_cipher INTEGER NOT NULL DEFAULT 0, " +
                "status INTEGER NOT NULL DEFAULT 1, tag INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX idx_msg_contact ON messages(contact_id, timestamp)")
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onOpen(db: SQLiteDatabase) {
        try { db.rawQuery("PRAGMA secure_delete=ON", null).close() } catch (e: Exception) { }
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN status INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE messages ADD COLUMN tag INTEGER NOT NULL DEFAULT 0")
        }
    }

    fun getMeta(k: String): String? =
        db().rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k)).use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        }

    fun setMeta(k: String, v: String) {
        val cv = ContentValues()
        cv.put("k", k)
        cv.put("v", v)
        db().insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

object Contacts {
    private const val COLS = "id,name,phone,trust_level,created_at"

    private fun read(cur: android.database.Cursor) =
        Contact(cur.getLong(0), cur.getString(1), cur.getString(2), cur.getInt(3), cur.getLong(4))

    fun all(c: Context): List<Contact> =
        Db.get(c).db().rawQuery("SELECT $COLS FROM contacts ORDER BY name COLLATE NOCASE", null).use { cur ->
            val l = ArrayList<Contact>()
            while (cur.moveToNext()) l.add(read(cur))
            l
        }

    fun byId(c: Context, id: Long): Contact? =
        Db.get(c).db().rawQuery("SELECT $COLS FROM contacts WHERE id=?", arrayOf(id.toString())).use { cur ->
            if (cur.moveToFirst()) read(cur) else null
        }

    fun byPhone(c: Context, phone: String): Contact? = all(c).firstOrNull { Phones.same(it.phone, phone) }

    /** Returns the new id, or -1 if the number already exists. */
    fun insert(c: Context, ct: Contact): Long {
        val cv = ContentValues()
        cv.put("name", ct.name)
        cv.put("phone", ct.phone)
        cv.put("trust_level", ct.level)
        cv.put("created_at", System.currentTimeMillis())
        return Db.get(c).db().insert("contacts", null, cv)
    }

    fun update(c: Context, ct: Contact): Boolean {
        val cv = ContentValues()
        cv.put("name", ct.name)
        cv.put("phone", ct.phone)
        cv.put("trust_level", ct.level)
        return try {
            Db.get(c).db().update("contacts", cv, "id=?", arrayOf(ct.id.toString())) > 0
        } catch (e: Exception) { false }
    }

    fun delete(c: Context, id: Long) {
        val db = Db.get(c).db()
        db.beginTransaction()
        try {
            db.delete("messages", "contact_id=?", arrayOf(id.toString()))
            db.delete("contacts", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}

object Messages {
    fun list(c: Context, cid: Long): List<Msg> =
        Db.get(c).db().rawQuery(
            "SELECT id,contact_id,body,is_sent,timestamp,is_read,is_cipher,status,tag FROM messages " +
                    "WHERE contact_id=? ORDER BY timestamp, id", arrayOf(cid.toString())).use { cur ->
            val l = ArrayList<Msg>()
            while (cur.moveToNext()) {
                l.add(Msg(cur.getLong(0), cur.getLong(1), cur.getString(2), cur.getInt(3) == 1,
                    cur.getLong(4), cur.getInt(5) == 1, cur.getInt(6) == 1, cur.getInt(7), cur.getInt(8)))
            }
            l
        }

    /** Returns the new row id. */
    fun insert(c: Context, m: Msg): Long {
        val cv = ContentValues()
        cv.put("contact_id", m.contactId)
        cv.put("body", m.body)
        cv.put("is_sent", if (m.sent) 1 else 0)
        cv.put("timestamp", m.ts)
        cv.put("is_read", if (m.read) 1 else 0)
        cv.put("is_cipher", if (m.cipher) 1 else 0)
        cv.put("status", m.status)
        cv.put("tag", m.tag)
        return Db.get(c).db().insert("messages", null, cv)
    }

    fun unread(c: Context, cid: Long): Int =
        Db.get(c).db().rawQuery("SELECT COUNT(*) FROM messages WHERE contact_id=? AND is_read=0",
            arrayOf(cid.toString())).use { cur -> if (cur.moveToFirst()) cur.getInt(0) else 0 }

    fun markRead(c: Context, cid: Long) {
        Db.get(c).db().execSQL("UPDATE messages SET is_read=1 WHERE contact_id=? AND is_read=0", arrayOf<Any?>(cid))
    }

    fun setStatus(c: Context, id: Long, st: Int) {
        Db.get(c).db().execSQL("UPDATE messages SET status=? WHERE id=? AND status<>3", arrayOf<Any?>(st, id))
    }

    fun tagAll(c: Context, cid: Long, bit: Int) {
        Db.get(c).db().execSQL("UPDATE messages SET tag=(tag | ?) WHERE contact_id=?", arrayOf<Any?>(bit, cid))
    }

    fun exists(c: Context, m: Msg): Boolean =
        Db.get(c).db().rawQuery(
            "SELECT COUNT(*) FROM messages WHERE contact_id=? AND timestamp=? AND body=? AND is_sent=?",
            arrayOf(m.contactId.toString(), m.ts.toString(), m.body, if (m.sent) "1" else "0")
        ).use { cur -> cur.moveToFirst() && cur.getInt(0) > 0 }
}
