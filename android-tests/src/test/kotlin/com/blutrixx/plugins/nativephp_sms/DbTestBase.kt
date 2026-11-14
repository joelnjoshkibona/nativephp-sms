package com.blutrixx.plugins.nativephp_sms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before

/** Each test starts on an empty sms.sqlite. */
abstract class DbTestBase {
    protected lateinit var context: Context

    @Before fun openFresh() {
        context = ApplicationProvider.getApplicationContext()
        SmsDb.closeForTests()
        SmsDb.file(context).parentFile?.listFiles()?.forEach { it.delete() }
        EventJournal.resetForTests()
    }

    @After fun close() = SmsDb.closeForTests()

    protected fun count(sql: String, vararg args: String): Long =
        SmsDb.get(context).rawQuery(sql, args).use { it.moveToFirst(); it.getLong(0) }
}
