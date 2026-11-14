package com.nativephp.mobile.utils

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

// Test stand-in for NativePHP's NativeActionCoordinator, same signature.
class NativeActionCoordinator : Fragment() {
    companion object {
        fun dispatchEvent(activity: FragmentActivity, event: String, payloadJson: String) {}
    }
}
