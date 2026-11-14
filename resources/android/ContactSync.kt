package com.blutrixx.plugins.nativephp_sms

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * The phone's contacts as names for numbers (sms.sqlite contact_cache): one row per number's comparison key
 * (SmsPrefs.normalize, the last 9 digits), so "+255712345678" and "0712345678" find the same name. A conversation's name
 * is read by joining it, never copied into threads. Needs READ_CONTACTS; without it the cache stays as it was.
 * The whole cache is replaced in one transaction: a few hundred rows, and a contact deleted on the phone goes too.
 */
object ContactSync {
    data class Contact(val number: String, val name: String, val photo: String?)

    /** The phone's contacts; tests give their own. */
    @Volatile internal var source: (Context) -> List<Contact> = ::readPhone

    fun canRead(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Returns how many numbers have a name, or -1 when contacts may not be read. */
    fun sync(context: Context, now: Long = System.currentTimeMillis()): Int {
        val contacts = try { source(context) } catch (e: SecurityException) { return -1 }
        val byKey = LinkedHashMap<String, Contact>()
        for (c in contacts) {
            val key = SmsPrefs.normalize(c.number)
            if (key.isNotEmpty() && c.name.isNotBlank()) byKey.putIfAbsent(key, c)
        }
        SmsDb.write(context) { db ->
            db.delete("contact_cache", null, null)
            byKey.forEach { (key, c) ->
                db.insert("contact_cache", null, ContentValues().apply {
                    put("number_e164", key)
                    put("name", c.name.trim())
                    if (c.photo != null) put("avatar_ref", c.photo)
                    put("updated_at", now)
                })
            }
            SmsDb.setSetting(db, "contacts.synced_at", now.toString())
        }
        return byKey.size
    }

    /** The name saved for a number, or null. */
    fun nameFor(context: Context, address: String): String? {
        val key = SmsPrefs.normalize(address).ifEmpty { return null }
        return SmsDb.get(context).rawQuery("SELECT name FROM contact_cache WHERE number_e164 = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun readPhone(context: Context): List<Contact> {
        if (!canRead(context)) throw SecurityException("READ_CONTACTS")
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
        )
        return context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(Contact(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2)))
            }
        } ?: emptyList()
    }
}
