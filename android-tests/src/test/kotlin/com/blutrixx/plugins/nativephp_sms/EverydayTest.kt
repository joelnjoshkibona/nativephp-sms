package com.blutrixx.plugins.nativephp_sms

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 1.2's plugin side: names for numbers, search, and a conversation's notification with Reply and Mark as read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EverydayTest : DbTestBase() {
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Before fun allow() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        SmsNotifier.activeThread = null
    }

    private fun receive(from: String, body: String, ts: Long = System.currentTimeMillis()) =
        ReceivePipeline.receive(context, ReceivePipeline.Delivered(from, body, ts, ts, 1, 0, 1))

    // -- contacts ---------------------------------------------------------------------------------------------------------

    @Test fun aContactNamesEveryWayOfWritingItsNumberAndASyncReplacesTheCache() {
        ContactSync.source = { listOf(ContactSync.Contact("+255 712 000 111", "Neema Kweka", null), ContactSync.Contact("0712000111", "Neema (old)", null), ContactSync.Contact("", "No number", null)) }
        assertEquals(1, ContactSync.sync(context))
        assertEquals("Neema Kweka", ContactSync.nameFor(context, "0712000111"))
        assertEquals("+255 712 000 111", SmsDb.get(context).rawQuery("SELECT number FROM contact_cache", null).use { it.moveToFirst(); it.getString(0) })
        ContactSync.source = { emptyList() }
        ContactSync.sync(context)
        assertNull(ContactSync.nameFor(context, "+255712000111"))
    }

    // -- search -----------------------------------------------------------------------------------------------------------

    @Test fun searchFindsWordsAndTheirStartsInAnyOrderNewestFirstAndFollowsChanges() {
        val a = receive("+255700000001", "Your parcel is ready for pickup at counter 3", ts = 1_000)
        val b = receive("+255700000002", "Pickup moved to Saturday", ts = 2_000)
        receive("+255700000003", "Nothing to see", ts = 3_000)
        assertEquals(listOf(b.messageId, a.messageId), MessageSearch.search(context, "pick").map { it.id })
        assertEquals(listOf(a.messageId), MessageSearch.search(context, "counter PARCEL").map { it.id })
        assertEquals(emptyList<String>(), MessageSearch.search(context, "\" OR * NEAR(").map { it.id }) // typed operators are words
        StoreWrites.deleteMessage(context, b.messageId!!)
        assertEquals(listOf(a.messageId), MessageSearch.search(context, "pickup").map { it.id })
    }

    // -- notifications ----------------------------------------------------------------------------------------------------

    @Test fun aConversationGetsOneNotificationWithItsUnreadTextsReplyAndMarkAsRead() {
        ContactSync.source = { listOf(ContactSync.Contact("+255712000111", "Neema Kweka", null)) }
        ContactSync.sync(context)
        receive("+255712000111", "First")
        val second = receive("0712000111", "Second")
        val shown = notifications.allNotifications.single()
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shown)!!
        assertEquals(listOf("First", "Second"), style.messages.map { it.text.toString() })
        assertEquals("Neema Kweka", style.messages.last().person?.name.toString())
        assertEquals(listOf("Reply", "Mark as read"), shown.actions.map { it.title.toString() })
        assertTrue(shown.actions[0].remoteInputs?.isNotEmpty() == true)
        assertEquals(NotificationCompat.CATEGORY_MESSAGE, shown.category)
        assertEquals(SmsNotifier.notificationId(second.threadId!!), context.getSystemService(NotificationManager::class.java).activeNotifications.single().id)
    }

    @Test fun aMutedConversationOrTheOneOnScreenPostsNothing() {
        val first = receive("+255700000009", "hello")
        context.getSystemService(NotificationManager::class.java).cancelAll()
        StoreWrites.saveThread(context, first.threadId!!, mapOf("muted" to true))
        receive("+255700000009", "again")
        assertEquals(0, notifications.size())

        StoreWrites.saveThread(context, first.threadId!!, mapOf("muted" to false))
        SmsNotifier.activeThread = first.threadId
        receive("+255700000009", "while open")
        assertEquals(0, notifications.size())
        SmsNotifier.activeThread = null
        receive("+255700000009", "after")
        assertEquals(1, notifications.size())
    }

    @Test fun markAsReadFromTheShadeClearsTheUnreadAndTheNotification() {
        val r = receive("+255700000010", "read me")
        NotificationActionReceiver().handle(context, NotificationActionReceiver.ACTION_MARK_READ, r.threadId!!, "+255700000010", "")
        assertEquals(0L, count("SELECT unread FROM threads WHERE id = ?", r.threadId!!))
        assertEquals(0L, count("SELECT COUNT(*) FROM messages WHERE read = 0"))
        assertEquals(0, notifications.size())
    }

    @Test fun aReplyFromTheShadeIsSentLikeAnyTextAndReadsTheConversation() {
        val r = receive("+255700000011", "are you there?")
        NotificationActionReceiver().handle(context, NotificationActionReceiver.ACTION_REPLY, r.threadId!!, "+255700000011", "On my way")
        assertEquals(1L, count("SELECT COUNT(*) FROM messages WHERE thread_id = ? AND direction = 'out' AND body = 'On my way' AND status = 'queued'", r.threadId!!))
        assertEquals(0L, count("SELECT unread FROM threads WHERE id = ?", r.threadId!!))
        assertEquals(0, notifications.size())
    }

    @Test fun aTextWithACodeGetsCopyCodeAndItsTapPutsTheCodeOnTheClipboard() {
        receive("+255700000012", "Your verification code is 482913.")
        val shown = notifications.allNotifications.single()
        val copy = shown.actions.single { it.title == "Copy code" }
        assertEquals("482913", shadowOf(copy.actionIntent).savedIntent.getStringExtra(SmsNotifier.EXTRA_CODE))
        NotificationActionReceiver().onReceive(context, shadowOf(copy.actionIntent).savedIntent)
        assertEquals("482913", context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        assertEquals(1, notifications.size()) // the notification stays
    }

    @Test fun aTextWithoutACodeHasNoCopyCode() {
        receive("+255700000013", "See you at 1800 tomorrow")
        assertTrue(notifications.allNotifications.single().actions.none { it.title == "Copy code" })
    }

    @Test fun aTappedNotificationLeavesTheConversationToOpenOnce() {
        SmsPrefs.setPendingOpen(context, "T1")
        assertEquals("T1", SmsPrefs.takePendingOpen(context))
        assertNull(SmsPrefs.takePendingOpen(context))
    }

    @Suppress("unused") private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)
}
