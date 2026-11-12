package com.vyze.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for model-missing user messaging (whole-app audit P2 #34,
 * 2026-11-09).
 *
 * A missing sideloaded model used to surface a multi-line dump (raw
 * absolute paths + `adb push` instructions) through the user-facing
 * onError — displayed verbatim on the loading screen — while the
 * controller SPOKE "check your connection" for a file that has nothing
 * to do with any connection. The diagnostic dump stays in logcat / the
 * crash log (dev-facing); the user paths now carry short safe strings:
 * a one-line display message that keeps LoadingFragment's `not found`
 * adb-hint trigger, and a trilingual spoken prompt that names the real
 * fix (place the file in Downloads).
 */
class InitFailureMessageTest {

    @Test
    fun `missing-model display message is one short line without paths`() {
        val msg = VlmEngineManager.companionModelMissingUserMessage(BrainModel.PROD_DEFAULT)
        // Single line — the old dump was ~10 lines of paths + adb.
        assertFalse(msg.contains("\n"))
        assertTrue(msg.length < 120)
        // LoadingFragment shows its adb-push hint only when the message
        // contains "not found" — the short text must keep that trigger.
        assertTrue(msg.contains("not found"))
        // Never a path, an adb command, or a raw bundle filename.
        assertFalse(msg.contains("/"))
        assertFalse(msg.contains("adb"))
        assertFalse(msg.contains(".litertlm"))
    }

    @Test
    fun `missing-model display message names the brain for the debug corridor`() {
        // A forced debug brain that is missing must name THAT brain, not
        // the production default.
        val msg = VlmEngineManager.companionModelMissingUserMessage(BrainModel.GEMMA_4E2B)
        assertTrue(msg.contains(BrainModel.GEMMA_4E2B.displayName))
        assertTrue(msg.contains("not found"))
    }

    @Test
    fun `missing-model speech is trilingual and names the real fix`() {
        val (en, ms, zh) = VyzeCoreController.companionMissingModelSpeech()
        assertTrue(en.isNotBlank())
        assertTrue(ms.isNotBlank())
        assertTrue(zh.isNotBlank())
        // Pinned exact strings (same discipline as the refusal phrases).
        assertEquals(
            "AI model file not found. Please place the model file in Downloads and try again.",
            en
        )
        assertEquals(
            "Fail model AI tidak dijumpai. Sila letakkan fail model dalam folder Downloads dan cuba lagi.",
            ms
        )
        assertEquals(
            "找不到AI模型文件。请把模型文件放入下载文件夹，然后重试。",
            zh
        )
        // TTS-safe: no paths, no adb, no filenames, nothing to spell out.
        for (s in listOf(en, ms, zh)) {
            assertFalse(s.contains("/"))
            assertFalse(s.contains("adb"))
            assertFalse(s.contains(".litertlm"))
        }
    }
}
