package com.vyze.app.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [SelfTalkPolicy] — Layer 1 of the anti-hallucination
 * stack. Positive cases come verbatim from the device corpus; the safety
 * cases are the real queries that must NEVER be dropped.
 */
class SelfTalkPolicyTest {

    // ── Corpus positives: the observed hallucinations ────────────────

    @Test
    fun `model self-identification en is dropped`() {
        assertTrue(SelfTalkPolicy.isSelfTalk("I am a large language model, trained by Google."))
        assertTrue(SelfTalkPolicy.isSelfTalk("I am a language model."))
    }

    @Test
    fun `ai self-identification variants are dropped`() {
        assertTrue(SelfTalkPolicy.isSelfTalk("I'm an AI assistant."))
        assertTrue(SelfTalkPolicy.isSelfTalk("As an AI, I cannot do that."))
        assertTrue(SelfTalkPolicy.isSelfTalk("I cannot and will not reveal that."))
    }

    @Test
    fun `polite refusal form is dropped - Jev teacher gap fix`() {
        // The one genuine miss from the 2026-09-25 corpus run.
        assertTrue(SelfTalkPolicy.isSelfTalk("I'm sorry, I cannot fulfill this request."))
        assertTrue(SelfTalkPolicy.isSelfTalk("I am sorry but I cannot help with that."))
    }

    @Test
    fun `malay refusal-speak is dropped`() {
        assertTrue(SelfTalkPolicy.isSelfTalk("Saya tidak dapat memproses permintaan anda."))
        assertTrue(SelfTalkPolicy.isSelfTalk("Saya tidak boleh membantu dengan itu."))
    }

    @Test
    fun `chinese refusal-speak is dropped`() {
        assertTrue(SelfTalkPolicy.isSelfTalk("作为一个大型语言模型，我不能。"))
        assertTrue(SelfTalkPolicy.isSelfTalk("我无法处理您的请求。"))
    }

    @Test
    fun `case and punctuation variance still matches`() {
        assertTrue(SelfTalkPolicy.isSelfTalk("I AM A LARGE LANGUAGE MODEL!"))
        assertTrue(SelfTalkPolicy.isSelfTalk("saya tidak dapat memproses permintaan anda"))
    }

    // ── Safety: real queries must NEVER match ────────────────────────

    @Test
    fun `real english queries pass through`() {
        assertFalse(SelfTalkPolicy.isSelfTalk("what is this"))
        assertFalse(SelfTalkPolicy.isSelfTalk("read the label for me"))
        assertFalse(SelfTalkPolicy.isSelfTalk("what color is this shirt"))
        assertFalse(SelfTalkPolicy.isSelfTalk("is it dark in here"))
    }

    @Test
    fun `real malay queries pass through`() {
        assertFalse(SelfTalkPolicy.isSelfTalk("Ini apa"))
        assertFalse(SelfTalkPolicy.isSelfTalk("apa kat depan"))
        assertFalse(SelfTalkPolicy.isSelfTalk("baca label ini"))
        // "saya" alone is a normal pronoun — only refusal forms match.
        assertFalse(SelfTalkPolicy.isSelfTalk("saya nak tahu apa ini"))
    }

    @Test
    fun `real chinese queries pass through`() {
        assertFalse(SelfTalkPolicy.isSelfTalk("这是什么"))
        assertFalse(SelfTalkPolicy.isSelfTalk("这个是什么"))
        assertFalse(SelfTalkPolicy.isSelfTalk("那是一棵绿色的树。"))
    }

    @Test
    fun `scene answers describing themselves pass through`() {
        // The Layer 2 call site sees ANSWER text — descriptions of objects
        // must never be suppressed. (Contrived, but pins the boundary.)
        assertFalse(SelfTalkPolicy.isSelfTalk("That is a black pen next to a glass of water."))
        assertFalse(SelfTalkPolicy.isSelfTalk("那是一本名为《高等数学》的教科书。"))
    }

    @Test
    fun `empty and blank transcripts are not self-talk`() {
        assertFalse(SelfTalkPolicy.isSelfTalk(""))
        assertFalse(SelfTalkPolicy.isSelfTalk("   "))
    }
}
