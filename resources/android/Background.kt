package com.blutrixx.plugins.nativephp_sms

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One shared worker for receiver work that must not run on the main thread. */
object Background {
    val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nativephp-sms-worker").apply { isDaemon = true }
    }
}
