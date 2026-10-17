package com.blutrixx.plugins.nativephp_sms

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Runs once per process start, before any activity or receiver, so the keep-alive service comes
 * back whenever the process does (app opened, or woken by an incoming text) and not only when
 * the setting is toggled. Provides no data.
 */
class KeepAliveInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { KeepAliveService.startIfEnabled(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
