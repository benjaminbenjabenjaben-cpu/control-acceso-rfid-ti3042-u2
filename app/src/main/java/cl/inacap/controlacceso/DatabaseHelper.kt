package cl.inacap.controlacceso

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class User(val id: Long, val name: String)
data class AccessEvent(val id: Long, val uid: String, val allowed: Boolean, val origin: String, val date: Long)

class DatabaseHelper(context: Context, name: String = "acceso.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL, salt TEXT NOT NULL)")
        db.execSQL("CREATE TABLE access_events (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL REFERENCES users(id), uid_encrypted TEXT NOT NULL, allowed INTEGER NOT NULL CHECK(allowed IN (0,1)), origin TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX events_by_user ON access_events(user_id, id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versión inicial. Las futuras versiones deberán migrar sin borrar registros.
        error("Falta la migración de $oldVersion a $newVersion")
    }
    fun register(username: String, password: String): Boolean {
        val salt = Security.salt()
        val values = ContentValues().apply {
            put("username", username); put("password_hash", Security.hash(password, salt)); put("salt", salt)
        }
        return writableDatabase.insertWithOnConflict("users", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    fun login(username: String, password: String): User? {
        readableDatabase.rawQuery("SELECT id, username, password_hash, salt FROM users WHERE username = ?", arrayOf(username)).use {
            if (it.moveToFirst() && Security.matches(password, it.getString(3), it.getString(2))) return User(it.getLong(0), it.getString(1))
        }
        return null
    }
    fun addEvent(userId: Long, uid: String, allowed: Boolean, origin: String): AccessEvent {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("user_id", userId); put("uid_encrypted", Security.encryptUid(uid)); put("allowed", if (allowed) 1 else 0)
            put("origin", origin); put("created_at", now)
        }
        val id = writableDatabase.insertOrThrow("access_events", null, values)
        return AccessEvent(id, uid, allowed, origin, now)
    }
    fun events(userId: Long): List<AccessEvent> {
        val rows = mutableListOf<AccessEvent>()
        readableDatabase.rawQuery("SELECT id, uid_encrypted, allowed, origin, created_at FROM access_events WHERE user_id = ? ORDER BY id DESC", arrayOf(userId.toString())).use {
            while (it.moveToNext()) rows.add(AccessEvent(it.getLong(0), Security.decryptUid(it.getString(1)), it.getInt(2) == 1, it.getString(3), it.getLong(4)))
        }
        return rows
    }
}
