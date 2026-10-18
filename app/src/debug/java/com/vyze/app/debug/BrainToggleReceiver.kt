package com.vyze.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vyze.app.core.BrainModel
import com.vyze.app.core.VlmEngineManager

/**
 * DEBUG-ONLY receiver (src/debug source set — never compiled into release).
 *
 * Forces which .litertlm BRAIN the VLM engine loads, overriding the
 * production default ([BrainModel.PROD_DEFAULT] = MiniCPM, the ONLY
 * production brain). This is the corridor A/B lever from the
 * brain-swap plan §M0.3: one debug APK runs both brains in one session.
 *
 * A forced brain whose file is missing fails LOUDLY with the standard
 * not-found error at init — forcing is for A/B testing, never fallback.
 *
 * adb trigger (debug build, from a development machine):
 *
 *     adb shell am broadcast -n com.vyze.app/.debug.BrainToggleReceiver --es brain minicpm
 *     adb shell am broadcast -n com.vyze.app/.debug.BrainToggleReceiver --es brain gemma
 *     adb shell am broadcast -n com.vyze.app/.debug.BrainToggleReceiver --es brain auto
 *
 * Takes effect at the NEXT engine init (cold app start, or the app's own
 * VLM reinit path). Persistent in debug builds via [BrainStickyProvider]
 * so OEM process kills between test cycles don't re-darken it. Release
 * builds have no such components and always use production auto-resolution.
 */
class BrainToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val slug = intent.getStringExtra("brain")
        val forced = if (slug == null) {
            // No extra: cycle — auto → forced MiniCPM → forced Gemma → auto.
            when (VlmEngineManager.brainOverride) {
                null -> BrainModel.PROD_DEFAULT
                BrainModel.MINICPM_V47_1B -> BrainModel.GEMMA_4E2B
                else -> null
            }
        } else {
            (if (slug.equals("auto", ignoreCase = true)) null
            else BrainModel.fromSlug(slug))
        }

        // Only reject genuinely unknown slugs — "auto" legitimately resolves
        // to null (clear) and must NOT hit this guard (observed 2026-10-16:
        // the null-treats-unknown guard left the sticky override stuck on
        // gemma after --es brain auto).
        if (slug != null && forced == null && !slug.equals("auto", ignoreCase = true)) {
            Log.w(TAG, "Unknown brain slug '$slug' — ignored (use minicpm | gemma | auto)")
            return
        }

        VlmEngineManager.brainOverride = forced
        // STICKY (debug only): survive OEM process kills during corridor testing.
        BrainStickyProvider.setSticky(context.applicationContext, forced)
        Log.i(
            TAG,
            "Brain override → " + when (forced) {
                null -> "AUTO (production default: MiniCPM only)"
                else -> "FORCED ${forced.displayName}"
            } + " (sticky: survives restarts; debug only; next init applies)"
        )
    }

    private companion object {
        const val TAG = "BrainToggle"
    }
}
