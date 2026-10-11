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

    /** Role / permission / device state, in one call. The first thing the spike screen shows. */
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
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return BridgeResponse.success(
                mapOf(
                    "api" to Build.VERSION.SDK_INT,
                    "manufacturer" to Build.MANUFACTURER,
                    "model" to Build.MODEL,
                    "package" to context.packageName,
                    "default_package" to defaultPackage,
                    "is_default" to (defaultPackage == context.packageName),
                    "role_available" to roleAvailable,
                    "role_held" to roleHeld,
                    "uses_role_manager" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q),
                    "permissions" to RUNTIME_PERMISSIONS.associateWith { granted(context, it) },
                    "notifications_enabled" to NotificationManagerCompat.from(context).areNotificationsEnabled(),
                    "battery_exempt" to power.isIgnoringBatteryOptimizations(context.packageName),
                    "blocklist_size" to SmsPrefs.blocklistSize(context),
                    "ephemeral_enabled" to SmsPrefs.isEphemeralEnabled(context)
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
                        "parts" to result.parts
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
            return BridgeResponse.success(mapOf("sims" to sims))
        }
    }

    /** Replaces the native blocklist used by the receive path (numbers are normalised natively). */
    class SetBlocklist(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            // The bridge router hands JSON arrays over as org.json.JSONArray, not List.
            val numbers: List<String> = when (val raw = parameters["numbers"]) {
                is org.json.JSONArray -> (0 until raw.length()).mapNotNull { i -> raw.optString(i, "").takeIf { it.isNotBlank() } }
                is List<*> -> raw.mapNotNull { it?.toString() }
                else -> emptyList()
            }
            SmsPrefs.setBlocklist(context, numbers)
            return BridgeResponse.success(mapOf("size" to SmsPrefs.blocklistSize(context)))
        }
    }

    /** Toggles the optional ephemeral-PHP hand-off from the receivers. */
    class Configure(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            (parameters["ephemeral"] as? Boolean)?.let { SmsPrefs.setEphemeralEnabled(context, it) }
            return BridgeResponse.success(mapOf("ephemeral_enabled" to SmsPrefs.isEphemeralEnabled(context)))
        }
    }

    /** Returns (and clears) the journal the headless receivers wrote: events_json is a JSON array. */
    class DrainEvents(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return BridgeResponse.success(mapOf("events_json" to EventJournal.drain(context).toString()))
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
}
