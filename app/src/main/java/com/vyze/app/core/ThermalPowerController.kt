package com.vyze.app.core

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.vyze.app.util.CrashLogFile

/**
 * Immutable thermal policy snapshot. Consumers read this ONCE at trigger
 * time (fragment at capture start, controller at inference start) and act
 * on it wholesale — a single capture/inference is never governed by two
 * different policies. Fields are plain vals; [ThermalPowerController.policy]
 * is the volatile holder.
 */
data class ThermalPolicy(
    /** Numeric tier — comparable for escalation checks. */
    val tier: Int,
    /** Human-readable name for logs and spoken notices. */
    val label: String,
    /** Image dimension the VLM should downscale scene frames to (px). */
    val sceneImageDimension: Int,
    /** Cap on scene-query generation tokens (0 = no override). */
    val sceneTokenCap: Int,
    /** True when continuous auto-snapshot must not run. */
    val continuousModeAllowed: Boolean,
    /** True when VLM inference is refused (OCR/text-only tasks still work). */
    val vlmInferenceAllowed: Boolean
) {
    companion object {
        const val TIER_NORMAL = 0
        const val TIER_MODERATE = 1
        const val TIER_SEVERE = 2
        const val TIER_CRITICAL = 3

        /** NONE / LIGHT — everything at full quality. */
        val NORMAL = ThermalPolicy(
            tier = TIER_NORMAL,
            label = "NORMAL",
            sceneImageDimension = 0,          // 0 = no override, use query-type routing
            sceneTokenCap = 0,                // 0 = no override
            continuousModeAllowed = true,
            vlmInferenceAllowed = true
        )

        /** MODERATE — shrink scene frames and cap generation tokens. */
        val MODERATE = ThermalPolicy(
            tier = TIER_MODERATE,
            label = "MODERATE",
            sceneImageDimension = 256,
            sceneTokenCap = 64,
            continuousModeAllowed = true,
            vlmInferenceAllowed = true
        )

        /** SEVERE — continuous mode paused; manual taps/voice only. */
        val SEVERE = ThermalPolicy(
            tier = TIER_SEVERE,
            label = "SEVERE",
            sceneImageDimension = 256,
            sceneTokenCap = 64,
            continuousModeAllowed = false,
            vlmInferenceAllowed = true
        )

        /** CRITICAL / EMERGENCY — VLM halted; local OCR/text tasks only. */
        val CRITICAL = ThermalPolicy(
            tier = TIER_CRITICAL,
            label = "CRITICAL",
            sceneImageDimension = 256,
            sceneTokenCap = 64,
            continuousModeAllowed = false,
            vlmInferenceAllowed = false
        )
    }
}

/**
 * App-scoped thermal & power governor for Vyze.
 *
 * ## Event-driven, zero polling
 * Uses the platform's [PowerManager.OnThermalStatusChangedListener] — the
 * OS pushes thermal transitions to us; this class runs NO periodic thermal
 * sampling (the whitepaper's previous "periodic thermal check" claim had no
 * backing code — this class is the real implementation). The single
 * callback is rare and tiny and is processed synchronously.
 *
 * ## Policy ladder
 * Maps OS thermal status → a single [ThermalPolicy] snapshot:
 *
 * | OS status            | Policy                                            |
 * |----------------------|---------------------------------------------------|
 * | NONE / LIGHT         | [ThermalPolicy.NORMAL] — full quality             |
 * | MODERATE             | [ThermalPolicy.MODERATE] — 256px scenes, 64 tokens|
 * | SEVERE               | [ThermalPolicy.SEVERE] — continuous paused        |
 * | CRITICAL / EMERGENCY | [ThermalPolicy.CRITICAL] — VLM halted, OCR only   |
 *
 * ## Hysteresis
 * Escalation is IMMEDIATE (heat damage is now); de-escalation requires the
 * status to hold lower for [DOWNGRADE_HOLD_MS] — prevents policy flapping
 * at a thermal boundary (burst workloads, charge cycles). During the hold,
 * [policy] keeps the higher tier; a new escalation mid-hold cancels it.
 *
 * ## Lifecycle
 * Owned by [com.vyze.app.VyzeApplication] (app-scoped, process-lifetime):
 * [register] on app create, [unregister] on app terminate. Registration is
 * idempotent and guarded against races, so repeated calls are harmless.
 */
class ThermalPowerController(context: Context) {

    private val TAG = "ThermalPowerController"

    private val appContext = context.applicationContext
    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /** Current effective policy — volatile snapshot, read at trigger time. */
    @Volatile
    var policy: ThermalPolicy = ThermalPolicy.NORMAL
        private set

    /** Thread-safe flag: registration is idempotent. */
    private val registered = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Fired ONLY on tier escalation (never de-escalation) — spoken notice hook. */
    @Volatile
    var onEscalation: ((ThermalPolicy) -> Unit)? = null

    /** Fired when SEVERE+ begins — the camera layer pauses continuous mode. */
    @Volatile
    var onContinuousPauseRequired: (() -> Unit)? = null

    // ── Hysteresis state ─────────────────────────────────────────────

    /** Handler on the main looper for the de-escalation hold. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Runnable applying the pending lower-tier policy after the hold. */
    private var downgradeRunnable: Runnable? = null

    /** The status we last processed (for hysteresis comparisons). */
    @Volatile
    private var currentStatus: Int = PowerManager.THERMAL_STATUS_NONE

    /** The platform listener — OS pushes transitions; no polling anywhere. */
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        try {
            applyStatus(status, downgrade = status < currentStatus)
            Log.i(TAG, "Thermal status change → $status (${tierName(status)})")
        } catch (e: Throwable) {
            Log.w(TAG, "Thermal callback error: ${e.message}")
        }
    }

    /**
     * Register the OS thermal listener. Idempotent — safe to call again.
     * On API < 29 the listener API does not exist: the controller stays in
     * NORMAL policy (older devices rely on the OS's own throttling).
     */
    fun register() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.i(TAG, "Thermal listener API requires API 29+ — controller passive (policy=NORMAL)")
            return
        }
        if (!registered.compareAndSet(false, true)) {
            return
        }
        try {
            val pm = powerManager ?: run {
                registered.set(false)
                return
            }
            // Read the sticky current status FIRST so a launch into an
            // already-hot device starts at the right tier without waiting
            // for a change event.
            val initial = pm.currentThermalStatus
            currentStatus = initial
            policy = tierOf(initial)
            pm.addThermalStatusListener(thermalListener)
            Log.i(TAG, "Thermal listener registered — initial status=$initial (${tierName(initial)})")
            CrashLogFile.log(TAG, "Thermal governor active — status=$initial, policy=${policy.label}")
        } catch (e: Throwable) {
            registered.set(false)
            Log.w(TAG, "Thermal listener registration failed: ${e.message}")
        }
    }

    /**
     * Unregister the OS thermal listener. Idempotent — safe to call again.
     * Also cancels any pending de-escalation hold.
     */
    fun unregister() {
        if (!registered.compareAndSet(true, false)) {
            return
        }
        try {
            powerManager?.removeThermalStatusListener(thermalListener)
        } catch (e: Throwable) {
            Log.w(TAG, "Thermal listener unregistration failed: ${e.message}")
        }
        cancelDowngradeHold()
        Log.i(TAG, "Thermal listener unregistered")
    }

    /**
     * Core transition handler. Escalations apply immediately; de-escalations
     * wait out [DOWNGRADE_HOLD_MS] while the status stays lower.
     */
    private fun applyStatus(status: Int, downgrade: Boolean) {
        val newTier = tierOf(status)
        val oldTier = policy
        currentStatus = status

        when {
            // ── ESCALATION: apply immediately ────────────────────────
            newTier.tier > oldTier.tier -> {
                cancelDowngradeHold()
                policy = newTier
                CrashLogFile.log(TAG, "Thermal ESCALATION: ${oldTier.label} → ${newTier.label} (status=$status)")
                try {
                    onEscalation?.invoke(newTier)
                    if (newTier.tier >= ThermalPolicy.TIER_SEVERE) {
                        // Fragment pauses the continuous loop itself — the
                        // controller never reaches into camera internals.
                        onContinuousPauseRequired?.invoke()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Escalation listener error: ${e.message}")
                }
            }

            // ── DE-ESCALATION: hold the higher tier for the window ───
            newTier.tier < oldTier.tier -> {
                if (!downgrade) {
                    // Direct application (sticky read at register time or
                    // first callback) — no hold needed.
                    policy = newTier
                    return
                }
                downgradeRunnable?.let { mainHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    downgradeRunnable = null
                    // Re-check: another escalation may have arrived mid-hold
                    // (applyStatus would have replaced policy and cancelled
                    // this runnable — belt-and-braces guard).
                    if (tierOf(currentStatus).tier >= policy.tier) return@Runnable
                    policy = newTier
                    CrashLogFile.log(TAG, "Thermal downgrade hold expired — applying ${newTier.label}")
                }
                downgradeRunnable = runnable
                mainHandler.postDelayed(runnable, DOWNGRADE_HOLD_MS)
            }
            // Same tier — nothing to do (status granularity within a tier).
        }
    }

    private fun cancelDowngradeHold() {
        downgradeRunnable?.let { mainHandler.removeCallbacks(it) }
        downgradeRunnable = null
    }

    /** Map an OS thermal status to the policy tier. */
    private fun tierOf(status: Int): ThermalPolicy = companionPolicyFor(status)

    private fun tierName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN($status)"
    }

    companion object {
        /**
         * De-escalation hold: the OS status must stay lower for this long
         * before the policy follows it down. Asymmetric vs the immediate
         * escalation by design — prevents flap at a boundary.
         */
        private const val DOWNGRADE_HOLD_MS = 60_000L

        /**
         * Map an OS thermal status to the policy tier. Pure — JVM-pinned
         * (ThermalPowerControllerTest); the OS integers are compile-time
         * constants, so no device is needed.
         */
        internal fun companionPolicyFor(status: Int): ThermalPolicy = when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalPolicy.NORMAL
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalPolicy.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalPolicy.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalPolicy.CRITICAL
            else -> ThermalPolicy.NORMAL   // unknown future status — stay permissive
        }
    }
}
