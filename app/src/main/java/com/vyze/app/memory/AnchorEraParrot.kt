package com.vyze.app.memory

import com.vyze.app.data.SuspectMarker

/**
 * Detects ANCHOR-ERA PARROT OUTPUTS (2026-10-20): answers the model produced
 * by completing a concrete example object that the prompt itself carried
 * (olive oil, dark blue shirt, bright green background — en/ms/zh). Those
 * answers were STORED to `interaction_records` with empty feedback, and the
 * similarity search then re-injected them as "Prior scene memory" into fresh
 * prompts — the poisoned anchor outliving its removal from the prompt by one
 * hop through memory (record 527 matched a new frame at ≥ 0.6 cosine
 * similarity and the answer picked up its "dark-colored" phrasing).
 *
 * The phrases below are the exact parrot targets that shipped in
 * DynamicPromptBuilder during the anchor era. They exist NOWHERE else in the
 * codebase (the prompt templates use abstract placeholders since the
 * 2026-10-20 fix), so a stored record containing one of them is
 * retroactively provable poison — no heuristic involved.
 *
 * CONTRACT: PURE — strings in, decision out, no Android, no state.
 * JVM-testable ([AnchorEraParrotTest]).
 */
object AnchorEraParrot {

    /**
     * The concrete objects that once sat in the prompt as inline examples,
     * in all three languages. Matched case-insensitively as substrings —
     * the model completed these phrases verbatim, so substring recall is
     * exact, not fuzzy.
     */
    private val ANCHOR_PHRASES: List<String> = listOf(
        // en — olive-oil era (earliest anchor)
        "olive oil",
        // en — dark-shirt era (the 2026-10-20 regression)
        "dark blue shirt",
        // ms translations that shipped in the six-language example lists
        "baju biru gelap",
        "minyak zaitun",
        // zh translations
        "深蓝色衬衫",
        "橄榄油",
    )

    /**
     * True when [output] contains a phrase the anchor-era prompt carried as
     * an inline example — i.e. the output is provably template parroting
     * rather than scene description, and must never be re-injected as
     * prior-scene memory.
     *
     * Also true for BRACKET-TEMPLATE ECHOS (fix #5, 2026-10-21): after the
     * concrete anchors were removed, the model completed the then-quoted
     * placeholder verbatim — "That is a [color] [object] you actually
     * see." (device records 536/537, empty feedback). Same class, single
     * detector — [SuspectMarker.isTemplateEcho] owns the placeholder
     * vocabulary so store-time tags and this purge can never disagree.
     */
    fun isAnchorEraParrot(output: String?): Boolean {
        if (output.isNullOrBlank()) return false
        if (SuspectMarker.isTemplateEcho(output)) return true
        val folded = output.lowercase()
        return ANCHOR_PHRASES.any { folded.contains(it) }
    }
}
