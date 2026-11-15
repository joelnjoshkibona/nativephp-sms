package com.blutrixx.plugins.nativephp_sms

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse

/**
 * Bridge functions (PHP: Blutrixx\NativephpSms\Sms, JS: resources/js/sms.ts).
 * Every function returns BridgeResponse.success(map) or BridgeResponse.error(code, message);
 * provider/permission problems come back as errors, never exceptions.
 */
object SmsFunctions {

    private const val TAG = "NativephpSms.Functions"

    val RUNTIME_PERMISSIONS = listOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CONTACTS,
        "android.permission.POST_NOTIFICATIONS"
    )

    private fun granted(context: Context, permission: String): Boolean {
        // POST_NOTIFICATIONS only exists (and is only runtime) on API 33+
        if (permission == "android.permission.POST_NOTIFICATIONS" && Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun intParam(parameters: Map<String, Any>, key: String): Int? = (parameters[key] as? Number)?.toInt()
    private fun longParam(parameters: Map<String, Any>, key: String): Long? = (parameters[key] as? Number)?.toLong()
    private val READER = Regex("^[a-z0-9_-]{1,40}$")

    /** Role / permission / device state, in one call. The first thing the spike screen shows. */
    /** Installed from a file (a browser download, a file manager) on Android 13 or newer: restricted settings apply. */
    private fun restrictedSettingsLikely(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val source = context.packageManager.getInstallSourceInfo(context.packageName).packageSource
            source == android.content.pm.PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || source == android.content.pm.PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
        } catch (e: Exception) {
            false
        }
    }

    class GetStatus(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val defaultPackage = Telephony.Sms.getDefaultSmsPackage(context) ?: ""
            var roleAvailable = false
            var roleHeld = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roles = context.getSystemService(RoleManager::class.java)
                roleAvailable = roles?.isRoleAvailable(RoleManager.ROLE_SMS) == true
                roleHeld = roles?.isRoleHeld(RoleManager.ROLE_SMS) == true
            }
            if (SmsStore.isDefaultSmsApp(context)) {
                SmsPrefs.markDefault(context)
                SmsNotifier.cancelDefaultLost(context)
                // texts another SMS app stored while this one was not the default
                if (SmsImport.status(context)["state"] == "done") SmsImport.start(context)
            }
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return BridgeResponse.success(
                mapOf(
                    "api" to Build.VERSION.SDK_INT,
                    "manufacturer" to Build.MANUFACTURER,
                    "transsion" to KeepAliveService.isTranssion,
                    "model" to Build.MODEL,
                    "package" to context.packageName,
                    "default_package" to defaultPackage,
                    "is_default" to SmsStore.isDefaultSmsApp(context),
                    "role_available" to roleAvailable,
                    "role_held" to roleHeld,
                    "uses_role_manager" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q),
                    // Android 13+ holds back the SMS role from an app installed from a file until the person allows
                    // "restricted settings" for it (App info > menu): first run then says how
                    "restricted_settings" to restrictedSettingsLikely(context),
                    "permissions" to RUNTIME_PERMISSIONS.associateWith { granted(context, it) },
                    "notifications_enabled" to NotificationManagerCompat.from(context).areNotificationsEnabled(),
                    "battery_exempt" to power.isIgnoringBatteryOptimizations(context.packageName),
                    "blocklist_size" to Blocklist.size(context),
                    "vault_has_key" to VaultStore.hasKey(context),
                    "vault_messages" to VaultStore.count(context),
                    "ephemeral_enabled" to SmsPrefs.isEphemeralEnabled(context),
                    "keep_alive_enabled" to SmsPrefs.isKeepAliveEnabled(context),
                    "keep_alive_running" to KeepAliveService.running,
                    // when the phone last started (epoch ms) and when the boot receiver last ran: a run after the phone's
                    // start proves Android lets the app start by itself (Transsion's auto-start, for one)
                    "booted_at" to (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()),
                    "last_boot_at" to SmsPrefs.lastBootAt(context),
                    "last_boot_reason" to SmsPrefs.lastBootReason(context),
                    "last_boot_keep_alive" to SmsPrefs.lastBootKeepAlive(context),
                    "last_boot_completed_at" to SmsPrefs.lastBootCompletedAt(context),
                    // when the app was found to have lost the SMS role it once held (0: it has it, or never had it)
                    "default_lost_at" to SmsPrefs.defaultLostAt(context)
                )
            )
        }
    }

    /** Opens the system "make this your default SMS app" prompt. Result arrives as DefaultSmsResult. */
    class RequestDefault(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = parameters["id"] as? String
            activity.runOnUiThread {
                val fm = activity.supportFragmentManager
                fm.findFragmentByTag(DefaultSmsFragment.TAG_NAME)?.let { stale ->
                    // A previous request whose result was lost would otherwise lock out every later one.
                    Log.w(TAG, "RequestDefault: replacing a stale in-flight request")
                    fm.beginTransaction().remove(stale).commitNowAllowingStateLoss()
                }
                // android.R.id.content container required: a containerless headless fragment can
                // silently never start (see nativephp-device-utils RequestPermissions).
                fm.beginTransaction()
                    .add(android.R.id.content, DefaultSmsFragment.newInstance(id), DefaultSmsFragment.TAG_NAME)
                    .commitAllowingStateLoss()
            }
            return BridgeResponse.success(mapOf("launched" to true))
        }
    }

    class ListThreads(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val limit = (intParam(parameters, "limit") ?: 50).coerceIn(1, 500)
            val started = System.currentTimeMillis()
            return try {
                val threads = SmsStore.listThreads(context, limit, longParam(parameters, "before_date"))
                BridgeResponse.success(mapOf("threads" to threads, "count" to threads.size, "ms" to (System.currentTimeMillis() - started)))
            } catch (e: SecurityException) {
                BridgeResponse.error("PERMISSION_DENIED", "READ_SMS is not granted")
            } catch (e: Exception) {
                Log.e(TAG, "ListThreads failed", e)
                BridgeResponse.error("QUERY_FAILED", e.message ?: "Could not read conversations")
            }
        }
    }

    class ListMessages(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val threadId = longParam(parameters, "thread_id")
                ?: return BridgeResponse.error("MISSING_THREAD", "thread_id is required")
            val limit = (intParam(parameters, "limit") ?: 50).coerceIn(1, 500)
            val started = System.currentTimeMillis()
            return try {
                val messages = SmsStore.listMessages(context, threadId, longParam(parameters, "before_id"), limit)
                BridgeResponse.success(mapOf("messages" to messages, "count" to messages.size, "ms" to (System.currentTimeMillis() - started)))
            } catch (e: SecurityException) {
                BridgeResponse.error("PERMISSION_DENIED", "READ_SMS is not granted")
            } catch (e: Exception) {
                Log.e(TAG, "ListMessages failed", e)
                BridgeResponse.error("QUERY_FAILED", e.message ?: "Could not read messages")
            }
        }
    }

    /** Total rows in the system SMS store, plus how long a full count takes (E5 sizing). */
    class CountMessages(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val started = System.currentTimeMillis()
            return try {
                val count = SmsStore.countMessages(context)
                BridgeResponse.success(mapOf("count" to count, "ms" to (System.currentTimeMillis() - started)))
            } catch (e: SecurityException) {
                BridgeResponse.error("PERMISSION_DENIED", "READ_SMS is not granted")
            } catch (e: Exception) {
                BridgeResponse.error("QUERY_FAILED", e.message ?: "Could not count messages")
            }
        }
    }

    class MarkRead(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val threadId = longParam(parameters, "thread_id")
                ?: return BridgeResponse.error("MISSING_THREAD", "thread_id is required")
            if (!SmsStore.isDefaultSmsApp(context)) {
                return BridgeResponse.error("NOT_DEFAULT", "Only the default SMS app can change read state")
            }
            return try {
                val updated = SmsStore.markThreadRead(context, threadId)
                NotificationManagerCompat.from(context).cancel(threadId.toInt())
                BridgeResponse.success(mapOf("updated" to updated))
            } catch (e: Exception) {
                BridgeResponse.error("UPDATE_FAILED", e.message ?: "Could not mark thread read")
            }
        }
    }

    class Send(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val to = (parameters["to"] as? String)?.trim().orEmpty()
            val body = parameters["body"] as? String ?: ""
            if (to.isEmpty()) return BridgeResponse.error("MISSING_TO", "to is required")
            if (body.isEmpty()) return BridgeResponse.error("MISSING_BODY", "body is required")
            val clientRef = parameters["client_ref"] as? String ?: java.util.UUID.randomUUID().toString()
            return try {
                val result = SmsSender.send(context, to, body, clientRef, intParam(parameters, "subscription_id") ?: -1)
                BridgeResponse.success(
                    mapOf(
                        "client_ref" to result.clientRef,
                        "queued" to true,
                        "stored" to result.stored,
                        "row_id" to (result.rowId ?: -1L),
                        "thread_id" to (result.threadId ?: -1L),
                        "parts" to result.parts,
                        "message_id" to (result.messageId ?: ""),
                        "thread" to (result.storeThreadId ?: ""),
                        "duplicate" to result.duplicate
                    )
                )
            } catch (e: SecurityException) {
                BridgeResponse.error("PERMISSION_DENIED", "SEND_SMS is not granted")
            } catch (e: Exception) {
                BridgeResponse.error("SEND_FAILED", e.message ?: "Could not send")
            }
        }
    }

    class ListSims(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val sims = SimSlots.listActive(context).map {
                mapOf(
                    "slot" to it.slot,
                    "subscription_id" to it.subscriptionId,
                    "phone_number" to (it.phoneNumber ?: ""),
                    "operator" to (it.operator ?: "")
                )
            }
            return BridgeResponse.success(mapOf("sims" to sims, "default_subscription_id" to SimSlots.defaultSmsSubscription()))
        }
    }

    /** Replaces the blocked numbers used by the receive path (normalised natively; prefixes, keywords and private numbers stay). */
    class SetBlocklist(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            // The bridge router hands JSON arrays over as org.json.JSONArray, not List.
            val numbers: List<String> = when (val raw = parameters["numbers"]) {
                is org.json.JSONArray -> (0 until raw.length()).mapNotNull { i -> raw.optString(i, "").takeIf { it.isNotBlank() } }
                is List<*> -> raw.mapNotNull { it?.toString() }
                else -> emptyList()
            }
            Blocklist.replaceNumbers(context, numbers)
            return BridgeResponse.success(mapOf("size" to Blocklist.size(context)))
        }
    }

    /** Toggles the optional ephemeral-PHP hand-off from the receivers. */
    class Configure(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            (parameters["ephemeral"] as? Boolean)?.let { SmsPrefs.setEphemeralEnabled(context, it) }
            (parameters["keep_alive"] as? Boolean)?.let {
                SmsPrefs.setKeepAliveEnabled(context, it)
                if (it) KeepAliveService.startIfEnabled(context) else KeepAliveService.stop(context)
            }
            return BridgeResponse.success(
                mapOf(
                    "ephemeral_enabled" to SmsPrefs.isEphemeralEnabled(context),
                    "keep_alive_enabled" to SmsPrefs.isKeepAliveEnabled(context)
                )
            )
        }
    }

    /** Returns (and clears) the journal the headless receivers wrote: events_json is a JSON array. */
    class DrainEvents(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return BridgeResponse.success(mapOf("events_json" to EventJournal.drain(context).toString()))
        }
    }

    // -- the everyday app: contacts, search, notifications ---------------------------------------------------------------

    /** Copies the phone's contacts into sms.sqlite (names for numbers). PERMISSION_DENIED without READ_CONTACTS. */
    class SyncContacts(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val n = ContactSync.sync(context)
            return if (n < 0) BridgeResponse.error("PERMISSION_DENIED", "READ_CONTACTS is not granted") else BridgeResponse.success(mapOf("named" to n))
        }
    }

    /** Messages whose words match q (each word, or the start of one), newest first: ids, threads and times. */
    class Search(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val hits = MessageSearch.search(context, parameters["q"] as? String ?: "", intParam(parameters, "limit") ?: 100)
            return BridgeResponse.success(mapOf(
                "ids" to hits.map { it.id },
                "hits_json" to org.json.JSONArray(hits.map { org.json.JSONObject().put("id", it.id).put("thread_id", it.threadId).put("ts", it.ts) }).toString(),
            ))
        }
    }

    /** The conversation now on screen (id), or none (no id): its texts post no notification, and its notification goes. */
    class SetActiveThread(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = (parameters["id"] as? String)?.takeIf { it.isNotEmpty() }
            SmsNotifier.activeThread = id
            id?.let { SmsNotifier.cancelThread(context, it) }
            return BridgeResponse.success(mapOf("active" to (id ?: "")))
        }
    }

    /** The conversation a tapped notification asked to open, once (empty when none). */
    class TakePendingOpen(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> =
            BridgeResponse.success(mapOf("thread" to (SmsPrefs.takePendingOpen(context) ?: "")))
    }

    // -- backup and export (2.1) -------------------------------------------------------------------------------------------

    private fun pick(activity: FragmentActivity, id: String?, mode: String) = activity.runOnUiThread {
        val fm = activity.supportFragmentManager
        fm.findFragmentByTag(DocumentFragment.TAG_NAME)?.let { fm.beginTransaction().remove(it).commitNowAllowingStateLoss() }
        fm.beginTransaction().add(android.R.id.content, DocumentFragment.newInstance(id, mode), DocumentFragment.TAG_NAME).commitAllowingStateLoss()
    }

    /** Asks where to save a backup file and writes it there; the result is the DocumentResult event. Needs a vault. */
    class BackupExport(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!VaultStore.exists(activity)) return BridgeResponse.error("NO_VAULT", "Set up the vault first.")
            pick(activity, parameters["id"] as? String, "backup")
            return BridgeResponse.success(mapOf("launched" to true))
        }
    }

    /** Asks where to save the readable export (a zip of the normal texts) and writes it; the DocumentResult event. */
    class ExportReadable(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            pick(activity, parameters["id"] as? String, "readable")
            return BridgeResponse.success(mapOf("launched" to true))
        }
    }

    /** Asks for a backup file and reads its header; the DocumentResult event says when it was made. */
    class BackupPick(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            pick(activity, parameters["id"] as? String, "open")
            return BridgeResponse.success(mapOf("launched" to true))
        }
    }

    /** Restores the picked backup with the passphrase or the recovery key (one Argon2id). */
    class BackupRestore(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val file = DocumentFragment.restoreFile(context)
            if (!file.exists()) return BridgeResponse.error("NO_FILE", "Pick a backup file first.")
            return try {
                val r = Backup.restore(context, file.readBytes(), (parameters["typed"] as? String) ?: "")
                file.delete()
                EventJournal.append(context, "backup_restored", org.json.JSONObject().put("vault_rows", r.vaultRows).put("rules", r.rules))
                BridgeResponse.success(mapOf("vault_rows" to r.vaultRows, "rules" to r.rules, "settings" to r.settings, "conversations" to r.conversations))
            } catch (e: Backup.WrongKey) {
                BridgeResponse.error("WRONG_KEY", "That passphrase or recovery key does not open this backup.")
            } catch (e: Backup.VaultExists) {
                BridgeResponse.error("EXISTS", e.message ?: "A vault exists")
            } catch (e: Backup.NotABackup) {
                BridgeResponse.error("NOT_A_BACKUP", e.message ?: "Not a backup")
            } catch (e: Exception) {
                Log.e(TAG, "restore failed", e)
                BridgeResponse.error("RESTORE_FAILED", e.javaClass.simpleName)
            }
        }
    }

    /** {vault, last_file_at}: whether a backup can be made, and when the last one was saved to a file. */
    class BackupState(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> =
            BridgeResponse.success(mapOf("vault" to VaultStore.exists(context), "last_file_at" to (SmsDb.setting(context, "backup.last-file")?.toLongOrNull() ?: 0L)))
    }

    // -- the vault (1.3): setup, unlock, what is in it, hiding, blocking, private numbers, spam -----------------------------

    /** LOCKED, NO_VAULT, EXISTS, INVALID, NOT_FOUND or VAULT_FAILED; never an exception across the bridge. */
    private inline fun vault(block: () -> Map<String, Any>): Map<String, Any> = try {
        BridgeResponse.success(block())
    } catch (e: VaultStore.VaultLocked) {
        BridgeResponse.error("LOCKED", "The vault is locked.")
    } catch (e: VaultOps.NoVault) {
        BridgeResponse.error("NO_VAULT", "Set up the vault first.")
    } catch (e: VaultStore.VaultExists) {
        BridgeResponse.error("EXISTS", "A vault is already set up.")
    } catch (e: StoreWrites.Invalid) {
        BridgeResponse.error("INVALID", e.message ?: "Not valid")
    } catch (e: StoreWrites.NotFound) {
        BridgeResponse.error("NOT_FOUND", e.message ?: "Not found")
    } catch (e: Exception) {
        Log.e(TAG, "vault failed", e)
        BridgeResponse.error("VAULT_FAILED", e.javaClass.simpleName)
    }

    private fun ids(parameters: Map<String, Any>, key: String = "ids"): List<String> = when (val raw = parameters[key]) {
        is org.json.JSONArray -> (0 until raw.length()).map { raw.optString(it) }.filter { it.isNotEmpty() }
        is List<*> -> raw.mapNotNull { it?.toString() }
        else -> emptyList()
    }

    private fun text(parameters: Map<String, Any>, key: String): String =
        (parameters[key] as? String)?.takeIf { it.isNotEmpty() } ?: throw StoreWrites.Invalid("$key is required")

    /** {exists, open}: a vault set up by the person, and whether it is open now. */
    class VaultState(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault { mapOf("exists" to VaultStore.exists(context), "open" to VaultStore.isOpen()) }
    }

    /** Makes the vault with a passphrase; answers the recovery key, the only time it is shown. Leaves it open. */
    class VaultSetup(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault {
            val pass = text(parameters, "passphrase")
            if (pass.length < 4) throw StoreWrites.Invalid("A passphrase needs at least 4 characters.")
            mapOf("recovery_key" to VaultStore.setup(context, pass))
        }
    }

    /** Tries the passphrase or the recovery key, always at the same cost. {open}. */
    class VaultUnlock(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault { mapOf("open" to VaultStore.unlock(context, (parameters["typed"] as? String)?.trim() ?: "")) }
    }

    class VaultLock(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault { VaultStore.lock(); mapOf("open" to false) }
    }

    class VaultChangePassphrase(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault {
            val pass = text(parameters, "passphrase")
            if (pass.length < 4) throw StoreWrites.Invalid("A passphrase needs at least 4 characters.")
            VaultStore.changePassphrase(context, pass)
            mapOf("changed" to true)
        }
    }

    class VaultWipe(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault { VaultOps.wipe(context); mapOf("wiped" to true) }
    }

    /** The open vault's conversations, newest first. */
    class VaultConversations(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault {
            val list = VaultOps.conversations(context).map {
                org.json.JSONObject().put("address", it.address).put("name", it.name ?: org.json.JSONObject.NULL).put("tag", it.tag)
                    .put("notify", it.notify ?: org.json.JSONObject.NULL).put("last_at", it.lastAt).put("last_body", it.lastBody)
                    .put("last_out", it.lastOut).put("count", it.count)
            }
            mapOf("conversations_json" to org.json.JSONArray(list).toString(), "rules" to Blocklist.size(context))
        }
    }

    class VaultMessages(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault {
            mapOf("messages_json" to org.json.JSONArray(VaultOps.messages(context, text(parameters, "address"))).toString())
        }
    }

    class HideMessages(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault { mapOf("hidden" to VaultOps.hideMessages(context, ids(parameters))) }
    }

    class HideThread(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault { mapOf("hidden" to VaultOps.hideThread(context, text(parameters, "id"))) }
    }

    class VaultRestore(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault { mapOf("restored" to VaultOps.restore(context, ids(parameters))) }
    }

    class VaultDelete(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault { VaultStore.delete(context, ids(parameters)); mapOf("deleted" to ids(parameters).size) }
    }

    class VaultDeleteConversation(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault { mapOf("deleted" to VaultOps.deleteConversation(context, text(parameters, "address"))) }
    }

    /** A reply written inside the vault: sent by SMS and kept only there. */
    class VaultSend(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault {
            VaultStore.secretKey()
            val ref = (parameters["client_ref"] as? String) ?: java.util.UUID.randomUUID().toString()
            SmsSender.sendFromVault(context, text(parameters, "to"), text(parameters, "body"), ref, intParam(parameters, "subscription_id") ?: -1)
            mapOf("client_ref" to ref)
        }
    }

    /** Block a number; with thread_id (and move true) its conversation moves into the vault. */
    class Block(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault {
            val move = parameters["move"] == true || parameters["move"] == "true"
            mapOf("moved" to VaultOps.block(context, text(parameters, "number"), if (move) parameters["thread_id"] as? String else null))
        }
    }

    class KeepPrivate(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault {
            mapOf("moved" to VaultOps.keepPrivate(context, text(parameters, "thread_id"), (parameters["notify"] as? String) ?: "generic"))
        }
    }

    class SetPrivateNotify(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault {
            VaultStore.secretKey()
            VaultOps.setPrivateNotify(context, text(parameters, "address"), text(parameters, "notify"))
            mapOf("notify" to text(parameters, "notify"))
        }
    }

    class MoveOutOfVault(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault { mapOf("restored" to VaultOps.moveOut(context, text(parameters, "address"))) }
    }

    /** The rules, inside the open vault only. */
    class Rules(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault {
            val list = VaultOps.rules(context).map {
                org.json.JSONObject().put("id", it.id).put("kind", it.kind).put("action", it.action).put("notify", it.notify ?: org.json.JSONObject.NULL).put("pattern", it.pattern)
            }
            mapOf("rules_json" to org.json.JSONArray(list).toString())
        }
    }

    class AddRule(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault { mapOf("id" to VaultOps.addRule(context, text(parameters, "kind"), text(parameters, "pattern"))) }
    }

    class RemoveRule(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault {
            val r = VaultOps.removeRule(context, text(parameters, "id"))
            mapOf("kind" to (r?.kind ?: ""), "pattern" to (r?.pattern ?: ""))
        }
    }

    /** The private numbers' comparison keys (contacts leave them out). */
    class PrivateKeys(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault { mapOf("keys" to VaultOps.privateKeys(context)) }
    }

    class ReportSpam(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault { VaultOps.reportSpam(context, text(parameters, "thread_id")); mapOf("spam" to true) }
    }

    class NotSpam(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault { VaultOps.notSpam(context, text(parameters, "thread_id")); mapOf("spam" to false) }
    }

    class PurgeSpam(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = vault { mapOf("purged" to VaultOps.purgeSpam(context)) }
    }

    /** DEBUG BUILDS ONLY: the real libsodium on this phone: a seal and open, a wrong key failing, and Argon2id's time. */
    class VaultSelfTest(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!VaultStore.isDebuggable(context)) return BridgeResponse.error("NOT_AVAILABLE", "only in a debug build")
            return vault {
                val c = SodiumCrypto
                val (pk, sk) = c.keypair()
                val sealed = c.seal(pk, "self test".toByteArray())
                val opened = c.sealOpen(pk, sk, sealed)?.let { String(it) }
                val wrong = c.sealOpen(pk, c.keypair().second, sealed)
                val t0 = System.currentTimeMillis()
                val key = c.pwhash("passphrase".toByteArray(), c.random(VaultCrypto.SALT_BYTES), VaultCrypto.OPS, VaultCrypto.MEM)
                val ms = System.currentTimeMillis() - t0
                val boxed = c.secretbox(key, sk)
                mapOf("seal_open" to (opened == "self test"), "wrong_key_fails" to (wrong == null), "argon2id_ms" to ms, "secretbox" to (c.secretboxOpen(key, boxed)?.contentEquals(sk) == true))
            }
        }
    }

    // -- writes for the app's Laravel, which opens sms.sqlite read-only (StoreWrites) --------------------------------------

    private fun str(parameters: Map<String, Any>, key: String): String? = (parameters[key] as? String)?.takeIf { it.isNotEmpty() }

    /** INVALID (with a message the screen can show), NOT_FOUND or STORE_FAILED; never an exception across the bridge. */
    private inline fun write(block: () -> Map<String, Any>): Map<String, Any> = try {
        BridgeResponse.success(block())
    } catch (e: StoreWrites.NotFound) {
        BridgeResponse.error("NOT_FOUND", e.message ?: "Not found")
    } catch (e: StoreWrites.Invalid) {
        BridgeResponse.error("INVALID", e.message ?: "Not valid")
    } catch (e: Exception) {
        Log.e(TAG, "store write failed", e)
        BridgeResponse.error("STORE_FAILED", e.message ?: e.javaClass.simpleName)
    }

    private fun idParam(parameters: Map<String, Any>): String =
        str(parameters, "id") ?: throw StoreWrites.Invalid("id is required")

    /** id, and any of pinned, muted, folder, color, wallpaper, read. */
    class SaveThread(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = write {
            val id = idParam(parameters)
            StoreWrites.saveThread(context, id, parameters.filterKeys { it in setOf("pinned", "muted", "folder", "color", "wallpaper", "read") })
            mapOf("id" to id)
        }
    }

    class DeleteThread(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = write { idParam(parameters).also { StoreWrites.deleteThread(context, it) }.let { mapOf("id" to it) } }
    }

    class DeleteMessage(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = write { idParam(parameters).also { StoreWrites.deleteMessage(context, it) }.let { mapOf("id" to it) } }
    }

    /** id (omit to create), title, body, shortcut. */
    class SaveTemplate(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = write {
            mapOf("id" to StoreWrites.saveTemplate(context, str(parameters, "id"), parameters["title"] as? String, parameters["body"] as? String, parameters["shortcut"] as? String))
        }
    }

    class DeleteTemplate(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = write { idParam(parameters).also { StoreWrites.deleteTemplate(context, it) }.let { mapOf("id" to it) } }
    }

    /** id (omit to create), thread_id or to, body, send_at (ms), subscription_id, status (waiting or cancelled). */
    class SaveScheduled(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = write {
            mapOf(
                "id" to StoreWrites.saveScheduled(
                    context, str(parameters, "id"), str(parameters, "thread_id"), str(parameters, "to"), parameters["body"] as? String,
                    longParam(parameters, "send_at"), intParam(parameters, "subscription_id"), str(parameters, "status"),
                )
            )
        }
    }

    class DeleteScheduled(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = write { idParam(parameters).also { StoreWrites.deleteScheduled(context, it) }.let { mapOf("id" to it) } }
    }

    /** key (app.*), value (a string; null or missing removes it). */
    class SetSetting(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = write {
            val key = str(parameters, "key") ?: throw StoreWrites.Invalid("key is required")
            StoreWrites.setSetting(context, key, parameters["value"]?.takeIf { it != org.json.JSONObject.NULL }?.toString())
            mapOf("key" to key)
        }
    }

    /** Starts (or resumes) the first-run import of the system store into sms.sqlite; once done, copies only what is new. */
    class StartImport(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!granted(context, Manifest.permission.READ_SMS)) return BridgeResponse.error("PERMISSION_DENIED", "READ_SMS is not granted")
            val started = SmsImport.start(context)
            return BridgeResponse.success(SmsImport.status(context) + mapOf("started" to started))
        }
    }

    /** The import's progress: state (none, running, interrupted, done), done, total, oldest_ts, error. */
    class ImportStatus(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> = BridgeResponse.success(SmsImport.status(context))
    }

    /**
     * DEBUG BUILDS ONLY: seal the vault to a test public key (32 bytes, base64) so the private and blocked routes can be
     * exercised before vault setup exists (milestone 1.3). A release build refuses it.
     */
    class VaultSetTestKey(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!VaultStore.isDebuggable(context)) return BridgeResponse.error("NOT_AVAILABLE", "only in a debug build")
            val key = try {
                android.util.Base64.decode(parameters["public_key"] as? String ?: "", android.util.Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                return BridgeResponse.error("INVALID", "public_key must be base64")
            }
            return try {
                VaultStore.setPublicKey(context, key, test = true)
                BridgeResponse.success(mapOf("vault_messages" to VaultStore.count(context)))
            } catch (e: IllegalArgumentException) {
                BridgeResponse.error("INVALID", e.message ?: "bad key")
            }
        }
    }

    /** Opens (and on first use creates and migrates) sms.sqlite, so a reader such as the app's Laravel can open it read-only. */
    class OpenStore(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return try {
                val version = SmsDb.version(context)
                BridgeResponse.success(mapOf("path" to SmsDb.file(context).absolutePath, "version" to version))
            } catch (e: Exception) {
                Log.e(TAG, "OpenStore failed", e)
                BridgeResponse.error("STORE_FAILED", e.message ?: "sms.sqlite could not be opened")
            }
        }
    }

    /**
     * Journal events after a cursor, oldest first: `after` (an event id), or the last id `reader` acknowledged.
     * Nothing is removed by reading; the reader acknowledges what it kept with AckEvents.
     */
    class ReadEvents(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val reader = (parameters["reader"] as? String)?.takeIf { READER.matches(it) }
            val after = longParam(parameters, "after") ?: reader?.let { EventJournal.acked(context, it) } ?: 0L
            val events = EventJournal.read(context, after, intParam(parameters, "limit") ?: 200)
            val last = if (events.length() > 0) events.getJSONObject(events.length() - 1).getLong("id") else after
            return BridgeResponse.success(mapOf("events_json" to events.toString(), "last_id" to last))
        }
    }

    /** Marks the journal as kept by `reader` up to `up_to`; events every reader has kept are pruned after 14 days. */
    class AckEvents(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val reader = parameters["reader"] as? String
            val upTo = longParam(parameters, "up_to")
            if (reader == null || !READER.matches(reader) || upTo == null) {
                return BridgeResponse.error("INVALID", "reader ([a-z0-9_-]{1,40}) and up_to are required")
            }
            EventJournal.ack(context, reader, upTo)
            return BridgeResponse.success(mapOf("reader" to reader, "acked" to EventJournal.acked(context, reader)))
        }
    }

    /** Returns (and clears) a compose request left by ComposeActivity (sms:/smsto: link). */
    class TakePendingCompose(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val pending = SmsPrefs.takePendingCompose(context)
                ?: return BridgeResponse.success(mapOf("pending" to false))
            return BridgeResponse.success(mapOf("pending" to true, "to" to pending.first, "body" to pending.second))
        }
    }

    class RequestBatteryOptimizationExemption(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                BridgeResponse.success(mapOf("launched" to true))
            } catch (e: Exception) {
                BridgeResponse.error("LAUNCH_FAILED", e.message ?: "Failed to open the battery-optimisation prompt")
            }
        }
    }

    /**
     * Opens one of the app's Android settings pages: `notifications` (the app's notification settings), `autostart`
     * (as OpenAutostartSettings) or `app` (the app's own page). `screen` says which one opened.
     */
    class OpenSettings(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val page = parameters["page"] as? String ?: "app"
            if (page == "autostart") return OpenAutostartSettings(activity).execute(parameters)
            if (page == "notifications" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                    return BridgeResponse.success(mapOf("launched" to true, "screen" to "notifications"))
                } catch (e: Exception) {
                    // fall through to the app's page
                }
            }
            return try {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${activity.packageName}")))
                BridgeResponse.success(mapOf("launched" to true, "screen" to "app_details"))
            } catch (e: Exception) {
                BridgeResponse.error("LAUNCH_FAILED", e.message ?: "Failed to open the settings")
            }
        }
    }

    /**
     * Opens Phone Master's auto-start screen on Transsion phones (Infinix, Tecno, itel), where the app must be allowed or
     * texts and the keep-alive restart are dropped after a reboot. Elsewhere, or if that screen is missing, it opens the
     * app's own settings page. `screen` says which one opened.
     */
    class OpenAutostartSettings(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (KeepAliveService.isTranssion) {
                try {
                    activity.startActivity(Intent("com.cyin.himgr.applicationmanager.view.activities.AUTO_START_ACTIVITY"))
                    return BridgeResponse.success(mapOf("launched" to true, "screen" to "autostart"))
                } catch (e: Exception) {
                    // an older or newer Phone Master without this screen: fall through to the app's settings
                }
            }
            return try {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${activity.packageName}")))
                BridgeResponse.success(mapOf("launched" to true, "screen" to "app_details"))
            } catch (e: Exception) {
                BridgeResponse.error("LAUNCH_FAILED", e.message ?: "Failed to open the auto-start settings")
            }
        }
    }

    // -- paired browsers (2.4): pair by QR, list, remove ----------------------------------------------------------------

    /** Network work off the calling thread (the bridge may call on the main one), waited for here. */
    private fun <T> offMain(block: () -> T): T = java.util.concurrent.Executors.newSingleThreadExecutor().let { ex ->
        try { ex.submit<T> { block() }.get(45, java.util.concurrent.TimeUnit.SECONDS) } finally { ex.shutdown() }
    }

    private fun browserMap(b: RelayLink.Browser): Map<String, Any> =
        mapOf("id" to b.id, "name" to b.name, "paired_at" to b.pairedAt, "last_seen" to (b.lastSeen ?: 0L))

    /** A pairing's failure as the bridge reports it: BAD_CODE, EXPIRED, OTHER_ACCOUNT, MISMATCH, NO_ANSWER, OFFLINE. */
    private fun pairError(e: Throwable?): Map<String, Any> = when (e) {
        is java.util.concurrent.ExecutionException -> pairError(e.cause)
        is java.util.concurrent.TimeoutException -> BridgeResponse.error("OFFLINE", "The server did not answer.")
        is RelayLink.BadCode -> BridgeResponse.error("BAD_CODE", e.message ?: "")
        is RelayLink.KeyMismatch -> BridgeResponse.error("MISMATCH", e.message ?: "")
        is RelayLink.NoAnswer -> BridgeResponse.error("NO_ANSWER", e.message ?: "")
        is RelayHttp.Failed -> BridgeResponse.error(
            if (e.status == 422 && (e.message ?: "").contains("another account")) "OTHER_ACCOUNT" else if (e.status == 422) "EXPIRED" else "RELAY_${e.status}",
            e.message ?: "",
        )
        is java.io.IOException -> BridgeResponse.error("OFFLINE", "Could not reach the server.")
        else -> { Log.e(TAG, "pair failed", e); BridgeResponse.error("PAIR_FAILED", e?.javaClass?.simpleName ?: "") }
    }

    /** Sms.RelayPair {code}: the scanned QR code. */
    class RelayPair(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val code = parameters["code"] as? String ?: return BridgeResponse.error("BAD_CODE", "No code")
            return try {
                val b = offMain { RelayLink.pair(context, code) }
                SyncAgent.restart(context)
                BridgeResponse.success(browserMap(b))
            } catch (e: Exception) {
                pairError(e)
            }
        }
    }

    /**
     * Sms.RelayPairCode {server, code}: the short code typed from the browser's screen. Answers {id, name, digits}: the
     * browser stays pending until Sms.RelayConfirm. Waits up to a minute for the browser's part of the exchange.
     */
    class RelayPairCode(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val code = parameters["code"] as? String ?: return BridgeResponse.error("BAD_CODE", "No code")
            val server = (parameters["server"] as? String)?.trimEnd('/') ?: return BridgeResponse.error("BAD_CODE", "No server")
            return try {
                val p = java.util.concurrent.Executors.newSingleThreadExecutor().let { ex ->
                    try { ex.submit<RelayLink.Pending> { RelayLink.pairByCode(context, server, code) }.get(90, java.util.concurrent.TimeUnit.SECONDS) } finally { ex.shutdown() }
                }
                BridgeResponse.success(mapOf("id" to p.id, "name" to p.name, "digits" to p.digits))
            } catch (e: Exception) {
                pairError(e)
            }
        }
    }

    /** Sms.RelayConfirm {id, match}: the digits on both screens matched (paired) or not (removed). */
    class RelayConfirm(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = parameters["id"] as? String ?: return BridgeResponse.error("NOT_FOUND", "No id")
            val match = parameters["match"] == true
            val paired = runCatching { offMain { RelayLink.confirm(context, id, match) } }.getOrDefault(false)
            SyncAgent.restart(context)
            if (paired) SyncAgent.greet(context, id)
            return BridgeResponse.success(mapOf("paired" to paired))
        }
    }

    /** Sms.RelayBrowsers: the paired browsers and the connection's state (off, connecting, connected). */
    class RelayBrowsers(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            SyncAgent.start(context)
            return BridgeResponse.success(mapOf("browsers" to RelayLink.browsers(context).map { browserMap(it) }, "state" to SyncAgent.state(), "server" to (RelayLink.identity(context)?.url ?: "")))
        }
    }

    /** Sms.RelayUnpair {id}: removes a browser here and on the relay; the browser wipes what it holds. */
    class RelayUnpair(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = parameters["id"] as? String ?: return BridgeResponse.error("NOT_FOUND", "No id")
            runCatching { offMain { RelayLink.unpair(context, id) } }.onFailure { RelayLink.forget(context, id) }
            SyncAgent.restart(context)
            return BridgeResponse.success(mapOf("removed" to id))
        }
    }

    /** Sms.RelaySelfTest: the phone's libsodium against the browser's test vectors. */
    class RelaySelfTest(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val failed = SodiumSync.selfTest()
            return BridgeResponse.success(mapOf("ok" to failed.isEmpty(), "failed" to failed.joinToString(", ")))
        }
    }
}
