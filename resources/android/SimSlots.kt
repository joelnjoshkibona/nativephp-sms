package com.blutrixx.plugins.nativephp_sms

import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log

/** SubscriptionManager access (READ_PHONE_STATE gated); copy of the proven telephony-gateway helper. */
object SimSlots {
    private const val TAG = "NativephpSms.SimSlots"

    data class Info(val slot: Int, val subscriptionId: Int, val phoneNumber: String?, val operator: String?)

    private fun manager(context: Context): SubscriptionManager? =
        context.getSystemService(SubscriptionManager::class.java)

    @Suppress("DEPRECATION")
    private fun numberOf(info: SubscriptionInfo): String? = info.number?.takeIf { it.isNotBlank() }

    fun listActive(context: Context): List<Info> {
        return try {
            val infos = manager(context)?.activeSubscriptionInfoList ?: emptyList()
            infos.map {
                Info(it.simSlotIndex, it.subscriptionId, numberOf(it), it.carrierName?.toString())
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "listActive: READ_PHONE_STATE not granted", e)
            emptyList()
        } catch (e: Throwable) {
            Log.e(TAG, "listActive failed", e)
            emptyList()
        }
    }

    fun slotForSubscriptionId(context: Context, subscriptionId: Int): Int? {
        if (subscriptionId < 0) return null
        return try {
            manager(context)?.getActiveSubscriptionInfo(subscriptionId)?.simSlotIndex
        } catch (e: Throwable) {
            Log.w(TAG, "slotForSubscriptionId failed for subId=$subscriptionId", e)
            null
        }
    }
}
