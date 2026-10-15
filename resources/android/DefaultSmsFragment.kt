package com.blutrixx.plugins.nativephp_sms

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONObject

/**
 * Asks the user to make this app the default SMS app and reports the outcome to PHP/JS as the
 * DefaultSmsResult event. Headless fragment, same shape as nativephp-device-utils'
 * PermissionRequestFragment (it must live in android.R.id.content to actually start).
 *
 *   API 29+ : RoleManager.createRequestRoleIntent(ROLE_SMS)
 *   API 26-28: Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT
 *
 * The result is never inferred from the activity result code alone: after it returns, the
 * truth is SmsStore.isDefaultSmsApp().
 */
class DefaultSmsFragment : Fragment() {

    companion object {
        const val TAG_NAME = "nativephp_sms_default_request"
        private const val TAG = "NativephpSms.Role"
        private const val ARG_ID = "id"

        fun newInstance(id: String?) = DefaultSmsFragment().apply {
            arguments = Bundle().apply { putString(ARG_ID, id) }
        }
    }

    private var launched = false

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        report(if (result.resultCode == Activity.RESULT_OK) null else "cancelled")
    }

    override fun onStart() {
        super.onStart()
        if (launched) return
        launched = true

        val context = requireContext()
        if (SmsStore.isDefaultSmsApp(context)) {
            report(null)
            return
        }

        try {
            val request: Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roles = context.getSystemService(RoleManager::class.java)
                if (roles == null || !roles.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    report("role_unavailable")
                    return
                }
                roles.createRequestRoleIntent(RoleManager.ROLE_SMS)
            } else {
                Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                    .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
            }
            launcher.launch(request)
        } catch (e: Exception) {
            Log.e(TAG, "could not launch the default-SMS request", e)
            report("launch_failed: ${e.message}")
        }
    }

    private fun report(reason: String?) {
        val host = activity
        if (host == null) {
            if (isAdded) parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
            return
        }
        val granted = SmsStore.isDefaultSmsApp(host)
        val payload = JSONObject()
            .put("granted", granted)
            .put("default_package", Telephony.Sms.getDefaultSmsPackage(host) ?: "")
            .put("api", Build.VERSION.SDK_INT)
            .put("reason", if (granted) "" else (reason ?: "not_default"))
        arguments?.getString(ARG_ID)?.let { payload.put("id", it) }

        EventJournal.append(host, "default_sms_result", payload)
        NativeActionCoordinator.dispatchEvent(host, "Blutrixx\\NativephpSms\\Events\\DefaultSmsResult", payload.toString())
        parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
    }
}
