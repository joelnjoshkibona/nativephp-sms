package com.blutrixx.plugins.nativephp_sms

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The notification's Copy code finds the same codes as the app (resources/js/codeCases.json, read by both). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodeFinderTest {
    @Test fun theSharedCases() {
        val cases = JSONArray(File("../resources/js/codeCases.json").readText())
        assertTrue(cases.length() >= 10)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val want = if (c.isNull("code")) null else c.getString("code")
            assertEquals(c.getString("body"), want, CodeFinder.find(c.getString("body")))
        }
    }
}
