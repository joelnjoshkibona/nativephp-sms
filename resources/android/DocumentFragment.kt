package com.blutrixx.plugins.nativephp_sms

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONObject
import java.io.File

/**
 * The system's file picker for the backup and the export (headless, as DefaultSmsFragment): `backup` and `readable`
 * ask where to save (ACTION_CREATE_DOCUMENT) and write the file there; `open` asks for a backup file
 * (ACTION_OPEN_DOCUMENT), keeps a copy in the app's cache for BackupRestore and reads its header. The outcome arrives
 * as the DocumentResult event: {id, mode, ok, reason, name, bytes, count, created_at}.
 */
class DocumentFragment : Fragment() {

    companion object {
        const val TAG_NAME = "nativephp_sms_document"
        private const val TAG = "NativephpSms.Doc"
        const val RESTORE_FILE = "restore.smsbackup"

        fun newInstance(id: String?, mode: String) = DocumentFragment().apply {
            arguments = Bundle().apply { putString("id", id); putString("mode", mode) }
        }

        fun restoreFile(context: Context) = File(context.cacheDir, RESTORE_FILE)
    }

    private var launched = false
    private val mode get() = arguments?.getString("mode") ?: "backup"

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) report(JSONObject().put("ok", false).put("reason", "cancelled"))
        else work(uri)
    }

    override fun onStart() {
        super.onStart()
        if (launched) return
        launched = true
        val intent = when (mode) {
            "open" -> Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            "readable" -> Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE, Backup.readableName())
            else -> Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, Backup.fileName())
        }
        try {
            launcher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "no picker", e)
            report(JSONObject().put("ok", false).put("reason", "no_picker"))
        }
    }

    private fun work(uri: Uri) {
        val app = requireContext().applicationContext
        Background.executor.execute {
            val out = JSONObject()
            try {
                when (mode) {
                    "open" -> {
                        val bytes = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        val header = Backup.header(bytes)
                        restoreFile(app).writeBytes(bytes)
                        out.put("ok", true).put("created_at", header.createdAt).put("app_version", header.appVersion).put("bytes", bytes.size)
                    }
                    "readable" -> {
                        val n = app.contentResolver.openOutputStream(uri, "wt")!!.use { Backup.writeReadable(app, it) }
                        out.put("ok", true).put("count", n)
                    }
                    else -> {
                        val bytes = Backup.build(app, appVersion(app))
                        app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                        SmsDb.setSetting(app, "backup.last-file", System.currentTimeMillis().toString())
                        out.put("ok", true).put("bytes", bytes.size)
                    }
                }
                out.put("name", nameOf(app, uri))
            } catch (e: Backup.NotABackup) {
                out.put("ok", false).put("reason", "not_a_backup")
            } catch (e: Backup.NoVault) {
                out.put("ok", false).put("reason", "no_vault")
            } catch (e: Exception) {
                Log.e(TAG, "$mode failed", e)
                out.put("ok", false).put("reason", e.javaClass.simpleName)
            }
            EventJournal.append(app, "document_$mode", JSONObject().put("ok", out.optBoolean("ok")).put("reason", out.optString("reason")).put("bytes", out.optInt("bytes")).put("count", out.optInt("count")))
            report(out)
        }
    }

    private fun report(payload: JSONObject) {
        val host = activity ?: return
        payload.put("mode", mode)
        arguments?.getString("id")?.let { payload.put("id", it) }
        host.runOnUiThread {
            NativeActionCoordinator.dispatchEvent(host, "Blutrixx\\NativephpSms\\Events\\DocumentResult", payload.toString())
            if (isAdded) parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
        }
    }

    private fun nameOf(context: Context, uri: Uri): String =
        runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull() ?: ""

    private fun appVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" }.getOrDefault("")
}
