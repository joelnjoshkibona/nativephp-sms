package com.nativephp.mobile.bridge

// Test stand-ins for NativePHP's classes (vendor/nativephp/mobile/resources/androidstudio/.../bridge), same signatures.

interface BridgeFunction {
    fun execute(parameters: Map<String, Any>): Map<String, Any>
}

object BridgeResponse {
    fun success(data: Map<String, Any> = emptyMap()): Map<String, Any> = data
    fun error(code: String, message: String, data: Map<String, Any> = emptyMap()): Map<String, Any> =
        mapOf("status" to "error", "code" to code, "message" to message, "data" to data)
}

class PHPBridge(@Suppress("UNUSED_PARAMETER") context: android.content.Context) {
    fun getLaravelPath(): String = ""
    fun nativeEphemeralBoot(bootstrapPath: String): Int = 0
    fun nativeEphemeralArtisan(command: String): String = ""
}
