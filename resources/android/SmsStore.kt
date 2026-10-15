package com.blutrixx.plugins.nativephp_sms

import android.app.role.RoleManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.util.Log

/**
 * Thin wrapper over the Android Telephony provider. Writes only succeed while this app
 * holds the default-SMS role (the provider rejects them with a SecurityException
 * otherwise); reads need READ_SMS. Every caller treats a provider failure as data, not a
 * crash -- a failed store write must never stop a message being notified or sent.
 */
object SmsStore {
    private const val TAG = "NativephpSms.Store"

    data class Inserted(val id: Long?, val threadId: Long?)

    /**
     * API 29+ asks RoleManager. Telephony.Sms.getDefaultSmsPackage() returns null for every
     * app when the role holder is missing the READ_CELL_BROADCASTS app op (seen on the API 36
     * emulator, for Google Messages too), which would make the real default look like it isn't.
     */
    fun isDefaultSmsApp(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }

    fun threadIdFor(context: Context, address: String): Long? = try {
        Telephony.Threads.getOrCreateThreadId(context, address)
    } catch (e: Exception) {
        Log.w(TAG, "getOrCreateThreadId failed for $address", e)
        null
    }

    fun insertInbox(
        context: Context,
        address: String,
        body: String,
        dateMillis: Long,
        dateSentMillis: Long,
        subscriptionId: Int
    ): Inserted {
        val threadId = threadIdFor(context, address)
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, dateMillis)
            put(Telephony.Sms.DATE_SENT, dateSentMillis)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            if (subscriptionId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
            if (threadId != null) put(Telephony.Sms.THREAD_ID, threadId)
        }
        return try {
            val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            Inserted(uri?.lastPathSegment?.toLongOrNull(), threadId)
        } catch (e: Exception) {
            Log.e(TAG, "insertInbox failed", e)
            Inserted(null, threadId)
        }
    }

    fun insertOutbox(context: Context, address: String, body: String, subscriptionId: Int): Inserted {
        val threadId = threadIdFor(context, address)
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING)
            if (subscriptionId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
            if (threadId != null) put(Telephony.Sms.THREAD_ID, threadId)
        }
        return try {
            val uri = context.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values)
            Inserted(uri?.lastPathSegment?.toLongOrNull(), threadId)
        } catch (e: Exception) {
            Log.e(TAG, "insertOutbox failed", e)
            Inserted(null, threadId)
        }
    }

    /** onlyIfTypeIn: only touch the row while its type is one of these (multipart parts report separately).
     * type: Telephony.Sms.MESSAGE_TYPE_*; status: Telephony.Sms.STATUS_* (null = leave as is). */
    fun updateOutgoing(context: Context, rowId: Long, type: Int?, status: Int?, onlyIfTypeIn: IntArray? = null): Boolean {
        if (rowId < 0) return false
        val values = ContentValues()
        if (type != null) values.put(Telephony.Sms.TYPE, type)
        if (status != null) values.put(Telephony.Sms.STATUS, status)
        if (values.size() == 0) return false
        return try {
            context.contentResolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, rowId), values,
                onlyIfTypeIn?.let { "${Telephony.Sms.TYPE} IN (${it.joinToString(",")})" }, null) > 0
        } catch (e: Exception) {
            Log.e(TAG, "updateOutgoing failed for row $rowId", e)
            false
        }
    }

    fun markThreadRead(context: Context, threadId: Long): Int {
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        return context.contentResolver.update(
            Telephony.Sms.CONTENT_URI,
            values,
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
            arrayOf(threadId.toString())
        )
    }

    // ── reads (READ_SMS) ────────────────────────────────────────────────────────

    private fun canonicalAddresses(context: Context): Map<Long, String> {
        val map = HashMap<Long, String>()
        try {
            context.contentResolver.query(
                Uri.parse("content://mms-sms/canonical-addresses"),
                arrayOf("_id", "address"), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    map[c.getLong(0)] = c.getString(1) ?: ""
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "canonical-addresses unavailable", e)
        }
        return map
    }

    /**
     * Newest-first conversation list. Uses the "simple" thread projection of the MMS/SMS
     * provider; if that query is rejected on a given ROM it falls back to grouping recent
     * SMS rows (message_count is then approximate, flagged by "approximate": true).
     */
    fun listThreads(context: Context, limit: Int, beforeDate: Long?): List<Map<String, Any>> {
        val simple = Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build()
        val selection = if (beforeDate != null) "date < ?" else null
        val args = if (beforeDate != null) arrayOf(beforeDate.toString()) else null
        try {
            val cursor: Cursor? = context.contentResolver.query(
                simple,
                arrayOf("_id", "date", "message_count", "recipient_ids", "snippet", "read"),
                selection, args, "date DESC LIMIT $limit"
            )
            if (cursor != null) {
                cursor.use { c ->
                    val addresses = canonicalAddresses(context)
                    val out = ArrayList<Map<String, Any>>()
                    while (c.moveToNext() && out.size < limit) {
                        val recipients = (c.getString(3) ?: "").trim().split(Regex("\\s+"))
                            .mapNotNull { it.toLongOrNull() }
                            .mapNotNull { addresses[it] }
                        out.add(
                            mapOf(
                                "thread_id" to c.getLong(0),
                                "date" to c.getLong(1),
                                "message_count" to c.getInt(2),
                                "addresses" to recipients,
                                "snippet" to (c.getString(4) ?: ""),
                                "read" to (c.getInt(5) == 1),
                                "approximate" to false
                            )
                        )
                    }
                    return out
                }
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "simple threads query failed, falling back to grouping SMS rows", e)
        }
        return listThreadsFallback(context, limit, beforeDate)
    }

    private fun listThreadsFallback(context: Context, limit: Int, beforeDate: Long?): List<Map<String, Any>> {
        val threads = LinkedHashMap<Long, MutableMap<String, Any>>()
        val selection = if (beforeDate != null) "${Telephony.Sms.DATE} < ?" else null
        val args = if (beforeDate != null) arrayOf(beforeDate.toString()) else null
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ),
            selection, args, "${Telephony.Sms.DATE} DESC LIMIT 2000"
        )?.use { c ->
            while (c.moveToNext()) {
                val threadId = c.getLong(0)
                val existing = threads[threadId]
                if (existing == null) {
                    if (threads.size >= limit) continue
                    threads[threadId] = mutableMapOf(
                        "thread_id" to threadId,
                        "date" to c.getLong(3),
                        "message_count" to 1,
                        "addresses" to listOf(c.getString(1) ?: ""),
                        "snippet" to (c.getString(2) ?: ""),
                        "read" to (c.getInt(4) == 1),
                        "approximate" to true
                    )
                } else {
                    existing["message_count"] = (existing["message_count"] as Int) + 1
                }
            }
        }
        return threads.values.toList()
    }

    /** Newest-first page of one conversation. beforeId pages backwards. */
    fun listMessages(context: Context, threadId: Long, beforeId: Long?, limit: Int): List<Map<String, Any>> {
        val selection = StringBuilder("${Telephony.Sms.THREAD_ID} = ?")
        val args = arrayListOf(threadId.toString())
        if (beforeId != null) {
            selection.append(" AND ${Telephony.Sms._ID} < ?")
            args.add(beforeId.toString())
        }
        val out = ArrayList<Map<String, Any>>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
                Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.STATUS,
                Telephony.Sms.SUBSCRIPTION_ID
            ),
            selection.toString(), args.toTypedArray(), "${Telephony.Sms._ID} DESC LIMIT $limit"
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out.add(
                    mapOf(
                        "id" to c.getLong(0),
                        "thread_id" to threadId,
                        "address" to (c.getString(1) ?: ""),
                        "body" to (c.getString(2) ?: ""),
                        "date" to c.getLong(3),
                        "date_sent" to c.getLong(4),
                        "type" to c.getInt(5),
                        "read" to (c.getInt(6) == 1),
                        "status" to c.getInt(7),
                        "subscription_id" to c.getInt(8)
                    )
                )
            }
        }
        return out
    }

    fun countMessages(context: Context): Int =
        context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), null, null, null)
            ?.use { it.count } ?: 0
}
