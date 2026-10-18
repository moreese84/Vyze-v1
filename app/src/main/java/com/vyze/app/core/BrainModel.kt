package com.vyze.app.core

/**
 * Which on-device brain (LiteRT-LM .litertlm bundle) the VLM engine loads.
 *
 * PRODUCT DECISION (owner, 2026-10-16): **MiniCPM-V-4.7-1B is the only
 * production brain** — no second model lives on the phone. There is NO
 * automatic Gemma fallback: a missing MiniCPM file fails LOUDLY with the
 * not-found error naming the file the fleet needs. Gemma 4 E2B is retained
 * ONLY as a debug-corridor A/B entry — it is never loaded unless a debug
 * build forces it AND the file was deliberately sideloaded for the test.
 *
 * Per-brain capabilities (from the litert-community HF card):
 *
 * | | MiniCPM-V-4.7 | gemma-4-E2B |
 * |---|---|---|
 * | file | MiniCPM-V-4.7-1B-VL_int8vis.litertlm (1.70 GB) | gemma-4-E2B-it.litertlm (2.59 GB) |
 * | image contract | fixed 448x448, 64 tokens (fast_vlm) | dynamic AR, tier budget |
 * | audio input | NONE (no audio tower in bundle) | yes (audio tower) |
 * | backend | CPU-only v1 (GPU decoder fails to build on 8 GB Mali) | GPU→CPU chain per tier |
 * | thinking mode | off (Instruct — no reasoning scaffold to strip) | n/a |
 *
 * NOT a value the UI surfaces: the brain is an internal asset swap — the
 * user-facing voice and persona are identical ("Vyze") either way.
 */
enum class BrainModel(
    /** Exact sideload filename (Downloads dir or app-scoped external files). */
    val fileName: String,
    /** Short log/tag-friendly name. */
    val displayName: String,
    /** Whether the bundle contains an audio tower (Content.AudioBytes usable). */
    val supportsAudioInput: Boolean,
    /** True → engine loads ONLY on Backend.CPU (for every device tier). */
    val cpuOnly: Boolean,
    /**
     * Minimum long-edge (px) the controller should FEED so the bundle's own
     * resize does not upscale a lossy small frame. Ignored by Gemma feeding.
     */
    val minFeedDimension: Int,
    /**
     * Engine context-window (maxNumTokens) ceiling from the bundle's KV
     * budget. Engine tier values already sit at or below this.
     */
    val contextTokenCap: Int,
    /** Stable debug-selector slug (BrainToggleReceiver --es brain …). */
    val slug: String
) {
    MINICPM_V47_1B(
        fileName = "MiniCPM-V-4.7-1B-VL_int8vis.litertlm",
        displayName = "MiniCPM-V-4.7-1B (int8vis)",
        supportsAudioInput = false,
        // DEVICE-ADAPTIVE (owner decision 2026-10-17): the v1 global CPU-only
        // flag is retired. The brain now runs the adaptive backend chain —
        // GPU attempted first on capable hardware, the verdict (either
        // direction) persisted per device, and stale after 24 h so a lucky
        // GPU run can never latch forever. Devices with known-bad GPU
        // drivers (Mali classes) are pre-filtered via
        // VyzeAdaptiveBackend.deviceLooksCpuOnly in backendPlanFor.
        cpuOnly = false,
        minFeedDimension = 448,
        contextTokenCap = 4096,
        slug = "minicpm"
    ),
    GEMMA_4E2B(
        fileName = "gemma-4-E2B-it.litertlm",
        displayName = "gemma-4-E2B-it",
        supportsAudioInput = true,
        cpuOnly = false,
        minFeedDimension = 0,   // no floor — Gemma handles dynamic AR natively
        contextTokenCap = 4096,
        slug = "gemma"
    );
    // NOTE: GEMMA_4E2B is NOT production-reachable. It exists for the
    // debug-corridor A/B selector (BrainToggleReceiver in src/debug) and for
    // the JVM turn-format unit tests. No production code path resolves it.

    companion object {
        /**
         * The production brain (owner decision above) — the ONLY brain
         * production auto-resolution ever loads. A missing file is a loud
         * not-found error, never a silent swap to another bundle.
         */
        val PROD_DEFAULT = MINICPM_V47_1B

        /** Parse a debug-selector slug; unknown values are ignored by callers. */
        fun fromSlug(value: String?): BrainModel? =
            entries.firstOrNull { it.slug.equals(value?.trim(), ignoreCase = true) }
    }
}
