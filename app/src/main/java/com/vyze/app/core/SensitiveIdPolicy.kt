package com.vyze.app.core

/**
 * PRIVACY CONTRACT for identity documents (pure policy, JVM-testable —
 * the [SelfTalkPolicy] house style).
 *
 * CONTEXT (2026-09-26 device session): a banknote asked about in Malay
 * ("Berapakah duit ini") was described as a "kad pengenalan" and the model
 * SPEAK the printed serial aloud. Banknote serials are public print —
 * reading them is acceptable. But the same answer shape pointed at an
 * identity card or bank card would read a MYKad/bank-card number aloud —
 * a shoulder-surfing hazard for a blind user in public, and worse than a
 * wrong answer: it is a wrong answer that leaks.
 *
 * CONTRACT:
 *  - PURE function: query in → boolean out. No Android, no state.
 *  - CONSERVATIVE in what it BLOCKS: only identity-document / account
 *    numbers are prohibited. Banknote serials are explicitly carved out —
 *    asking for the serial on money stays allowed (owner decision,
 *    2026-09-26).
 *  - The refusal is a PINNED canned phrase per language (same discipline
 *    as the failure-phrase override): the user hears the same sentence
 *    every time, in their language, and the TTS voice never reads digits.
 *  - The prompt clause built from [refusalPhrase] lands in the prompt via
 *    DynamicPromptBuilder; the native lane sets the flag from the query at
 *    trigger time and the agent lane re-checks per dispatch — the
 *    deterministic router owns the branch, the model never self-decides.
 */
object SensitiveIdPolicy {

    /**
     * Keywords that mark a query as asking to read a SENSITIVE number.
     * Lowercase substring match after [isSensitiveIdQuery] lowercases the
     * query — matches the CURRENCY_KEYWORDS matching style.
     */
    val SENSITIVE_ID_KEYWORDS: List<String> = listOf(
        // English
        "identity card", "ic number", "mykad", "national id", "nric",
        "passport number", "account number", "card number", "id number",
        // Malay / Bahasa Melayu
        "kad pengenalan", "nombor ic", "no ic", "nombor id",
        "nombor akaun", "no akaun", "nombor kad", "no kad",
        "nombor dalam kad", "nombor atas kad", "nombor kat kad",
        // Chinese
        "身份证", "身份証", "證件號", "证件号", "卡号", "卡號",
        "银行卡号", "銀行卡號", "账号", "帳號", "账户号码", "戶口號碼"
    )

    /**
     * Explicit NON-sensitive carve-outs. Banknote serial asks ("nombor
     * siri duit ini", "the serial on this note") stay allowed — checked
     * FIRST so a money-serial ask never trips the sensitive keywords.
     */
    val MONEY_SERIAL_KEYWORDS: List<String> = listOf(
        "serial", "nombor siri", "no siri", "siri duit",
        "序列号", "序列號", "钞票编号", "鈔票編號", "纸币编号"
    )

    /**
     * True when the query asks to read aloud an identity-document or
     * account number. Null/blank queries never trigger (nothing asked,
     * nothing to refuse).
     */
    fun isSensitiveIdQuery(query: String?): Boolean {
        if (query.isNullOrBlank()) return false
        val lower = query.lowercase()
        if (MONEY_SERIAL_KEYWORDS.any { lower.contains(it) }) return false
        return SENSITIVE_ID_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * The pinned refusal phrase in the user's language. Never returns
     * null: unknown languages fall back to English (the prompt's base
     * language) so the contract is always present. The phrase states the
     * prohibition AND the allowed alternative — describe the card, never
     * the number — so the answer stays useful instead of a bare "no".
     */
    fun refusalPhrase(language: String): String = when (language) {
        "ms" ->
            "Membaca nombor kad pengenalan atau nombor kad bank dengan kuat " +
            "adalah dilarang. Saya boleh terangkan kad ini, tetapi bukan nombornya."
        "zh" ->
            "朗读您的身份证号码或银行卡号码是被禁止的。我可以描述这张卡，但不能读出号码。"
        else ->
            "Reading your identity card number or bank card number aloud is " +
            "prohibited. I can describe the card, but I cannot read its number."
    }
}
