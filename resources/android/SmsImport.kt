package com.blutrixx.plugins.nativephp_sms

import android.content.Context
import android.provider.Telephony
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The first-run import: the system SMS store copied into sms.sqlite, newest first, so the conversation list is usable
 * within seconds while older years keep coming in. 500 rows per transaction, so a text arriving waits for one batch at
 * most. The cursor (date, _id of the oldest row copied) is saved with every batch, so an import cut short (the app killed,
 * the phone restarted) carries on from there; a row already copied is recognised by its system_id and skipped.
 *
 * Afterwards, catchUp() copies rows newer than the newest one seen (texts another SMS app stored while this one was not
 * the default). Drafts are not imported. MMS is not imported until Phase 4.
 */
object SmsImport {
    private const val TAG = "NativephpSms.Import"
    const val BATCH = 500

    data class SystemRow(
        val id: Long,
        val threadId: Long?,
        val address: String,
        val body: String,
        val date: Long,
        val type: Int,
        val read: Boolean,
        val status: Int,
        val subId: Int?,
        /** When the other phone's network sent it (inbox rows; 0 when unknown). */
        val dateSent: Long = 0,
    )

    /** The system store, as the import reads it. ContentResolver on a phone; tests give rows. */
    interface Source {
        fun count(): Int
        /** Rows older than (date, id), newest first. */
        fun olderThan(date: Long, id: Long, limit: Int): List<SystemRow>
        /** Rows with _id above [id], oldest first. */
        fun newerThan(id: Long, limit: Int): List<SystemRow>
    }

    @Volatile internal var sourceFor: (Context) -> Source = { ResolverSource(it) }
    private val running = AtomicBoolean(false)

    private object Keys {
        const val STATE = "import.state" // running | done
        const val CURSOR_DATE = "import.cursor_date"
        const val CURSOR_ID = "import.cursor_id"
        const val TOTAL = "import.total"
        const val DONE = "import.done"
        const val MAX_ID = "import.max_system_id"
        const val ERROR = "import.error"
        const val STARTED_AT = "import.started_at"
        const val FINISHED_AT = "import.finished_at"
    }

    fun status(context: Context): Map<String, Any> {
        val state = SmsDb.setting(context, Keys.STATE) ?: "none"
        return mapOf(
            "state" to if (state == "running" && !running.get()) "interrupted" else state,
            "running" to running.get(),
            "done" to (SmsDb.setting(context, Keys.DONE)?.toIntOrNull() ?: 0),
            "total" to (SmsDb.setting(context, Keys.TOTAL)?.toIntOrNull() ?: 0),
            "oldest_ts" to (SmsDb.setting(context, Keys.CURSOR_DATE)?.toLongOrNull() ?: 0L),
            "started_at" to (SmsDb.setting(context, Keys.STARTED_AT)?.toLongOrNull() ?: 0L),
            "finished_at" to (SmsDb.setting(context, Keys.FINISHED_AT)?.toLongOrNull() ?: 0L),
            "error" to (SmsDb.setting(context, Keys.ERROR) ?: ""),
        )
    }

    /** Starts (or resumes) the import on its own thread; when it is done, runs a catch-up instead. False if one is running. */
    fun start(context: Context): Boolean {
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) return false
        try {
            prepare(app, sourceFor(app)) // counted before answering, so the caller's status already says how many
        } catch (e: Throwable) {
            running.set(false)
            throw e
        }
        Thread({
            try {
                if (SmsDb.setting(app, Keys.STATE) == "done") catchUp(app) else run(app)
            } catch (e: Throwable) {
                Log.e(TAG, "import failed", e)
                SmsDb.setSetting(app, Keys.ERROR, e.message ?: e.javaClass.simpleName)
                EventJournal.append(app, "import_failed", JSONObject().put("error", e.message ?: e.javaClass.simpleName))
            } finally {
                running.set(false)
            }
        }, "nativephp-sms-import").start()
        return true
    }

    /** The import itself, on the calling thread (start() runs it on its own). */
    fun run(context: Context) {
        val source = sourceFor(context)
        val began = System.currentTimeMillis()
        prepare(context, source)
        SmsDb.setSetting(context, Keys.ERROR, null)
        var date = SmsDb.setting(context, Keys.CURSOR_DATE)?.toLongOrNull() ?: Long.MAX_VALUE
        var id = SmsDb.setting(context, Keys.CURSOR_ID)?.toLongOrNull() ?: Long.MAX_VALUE
        while (true) {
            val page = source.olderThan(date, id, BATCH)
            if (page.isEmpty()) break
            SmsDb.write(context) { db ->
                page.forEach { copy(db, it) }
                val last = page.last()
                date = last.date
                id = last.id
                val maxId = maxOf(SmsDb.setting(context, Keys.MAX_ID)?.toLongOrNull() ?: 0L, page.maxOf { it.id })
                val done = (SmsDb.setting(context, Keys.DONE)?.toIntOrNull() ?: 0) + page.size
                SmsDb.setSetting(db, Keys.CURSOR_DATE, date.toString())
                SmsDb.setSetting(db, Keys.CURSOR_ID, id.toString())
                SmsDb.setSetting(db, Keys.MAX_ID, maxId.toString())
                SmsDb.setSetting(db, Keys.DONE, done.toString())
            }
            if (page.size < BATCH) break
        }
        SmsDb.write(context) { db ->
            SmsDb.setSetting(db, Keys.STATE, "done")
            SmsDb.setSetting(db, Keys.FINISHED_AT, System.currentTimeMillis().toString())
            Backup.applyThreadPrefs(db) // a restored backup's pinned, muted, folders, now that the conversations exist
        }
        if (ContactSync.canRead(context)) runCatching { ContactSync.sync(context) } // names for the conversations just made
        EventJournal.append(
            context, "import_done",
            JSONObject().put("done", SmsDb.setting(context, Keys.DONE)).put("ms", System.currentTimeMillis() - began)
        )
    }

    /** The first time only: how many rows there are, and that an import is under way. */
    private fun prepare(context: Context, source: Source) {
        if (SmsDb.setting(context, Keys.STATE) != null) return
        val total = source.count()
        SmsDb.write(context) { db ->
            SmsDb.setSetting(db, Keys.STATE, "running")
            SmsDb.setSetting(db, Keys.TOTAL, total.toString())
            SmsDb.setSetting(db, Keys.DONE, "0")
            SmsDb.setSetting(db, Keys.STARTED_AT, System.currentTimeMillis().toString())
        }
        EventJournal.append(context, "import_started", JSONObject().put("total", total))
    }

    /** Rows the system store gained since the newest one seen. Returns how many were new here. */
    fun catchUp(context: Context): Int {
        val source = sourceFor(context)
        var after = SmsDb.setting(context, Keys.MAX_ID)?.toLongOrNull() ?: 0L
        var added = 0
        while (true) {
            val page = source.newerThan(after, BATCH)
            if (page.isEmpty()) break
            SmsDb.write(context) { db ->
                page.forEach { if (copy(db, it)) added++ }
                after = page.maxOf { it.id }
                SmsDb.setSetting(db, Keys.MAX_ID, after.toString())
            }
            if (page.size < BATCH) break
        }
        if (added > 0) {
            SmsDb.write(context) { Backup.applyThreadPrefs(it) }
            EventJournal.append(context, "import_caught_up", JSONObject().put("added", added))
        }
        return added
    }

    /** One system row into sms.sqlite; false when it was already there (or is a draft). */
    private fun copy(db: android.database.sqlite.SQLiteDatabase, r: SystemRow): Boolean {
        if (r.type == Telephony.Sms.MESSAGE_TYPE_DRAFT || r.address.isBlank()) return false
        val now = System.currentTimeMillis()
        val saved = if (r.type == Telephony.Sms.MESSAGE_TYPE_INBOX) {
            MessageStore.insertIncoming(
                db,
                MessageStore.Incoming(r.address, r.body, r.date, r.subId, null, r.id, r.threadId, read = r.read, smscAt = r.dateSent.takeIf { it > 0 }),
                now,
            )
        } else {
            val status = when {
                r.type == Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
                r.type == Telephony.Sms.MESSAGE_TYPE_SENT && r.status == Telephony.Sms.STATUS_COMPLETE -> "delivered"
                r.type == Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
                else -> "failed" // an outbox or queued row left behind by another app was never sent
            }
            MessageStore.insertImportedOutgoing(db, r.address, r.body, r.date, r.subId, r.id, r.threadId, status, now)
        }
        return saved.created
    }

    private class ResolverSource(private val context: Context) : Source {
        private val columns = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
            Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.STATUS, Telephony.Sms.SUBSCRIPTION_ID, Telephony.Sms.DATE_SENT,
        )

        override fun count(): Int = context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), null, null, null)?.use { it.count } ?: 0

        override fun olderThan(date: Long, id: Long, limit: Int) = rows(
            "${Telephony.Sms.DATE} < ? OR (${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} < ?)",
            arrayOf(date.toString(), date.toString(), id.toString()),
            "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC",
            limit,
        )

        override fun newerThan(id: Long, limit: Int) =
            rows("${Telephony.Sms._ID} > ?", arrayOf(id.toString()), "${Telephony.Sms._ID} ASC", limit)

        /** A LIMIT in the sort order is not honoured by every ROM, so the cursor is read only as far as [limit]. */
        private fun rows(selection: String, args: Array<String>, order: String, limit: Int): List<SystemRow> =
            context.contentResolver.query(Telephony.Sms.CONTENT_URI, columns, selection, args, order)?.use { c ->
                val out = ArrayList<SystemRow>(limit)
                while (out.size < limit && c.moveToNext()) {
                    out.add(
                        SystemRow(
                            id = c.getLong(0),
                            threadId = if (c.isNull(1)) null else c.getLong(1),
                            address = c.getString(2) ?: "",
                            body = c.getString(3) ?: "",
                            date = c.getLong(4),
                            type = c.getInt(5),
                            read = c.getInt(6) != 0,
                            status = if (c.isNull(7)) -1 else c.getInt(7),
                            subId = if (c.isNull(8)) null else c.getInt(8),
                            dateSent = if (c.isNull(9)) 0 else c.getLong(9),
                        )
                    )
                }
                out
            } ?: emptyList()
    }
}
