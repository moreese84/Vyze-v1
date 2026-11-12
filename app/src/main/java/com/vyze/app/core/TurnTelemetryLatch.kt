package com.vyze.app.core

/**
 * One-shot telemetry arming latch for a single engine turn (audit #64).
 *
 * The image/text/expansion lanes arm the latch when a turn commits to run;
 * exactly one completion path consumes it (engine callback, watchdog,
 * fast exit, or user cancel). The consume is atomic, so a main-thread
 * watchdog/cancel racing an engine-callback-thread completion still emits
 * exactly one row — the two-loose-fields version could double-emit.
 *
 * Pure Kotlin, no Android dependencies — unit-testable on the JVM.
 */
internal class TurnTelemetryLatch {

    /** Armed state: the telemetry task bucket plus the turn's start clock. */
    data class Snapshot(val taskType: String, val startMs: Long)

    private val lock = Any()
    private var snapshot: Snapshot? = null

    /** Arm for a new turn, replacing any pending arm. */
    fun arm(taskType: String, startMs: Long) = synchronized(lock) {
        snapshot = Snapshot(taskType, startMs)
    }

    /**
     * Consume the arm exactly once — returns the snapshot on the first
     * call, null on every later call until the next arm.
     */
    fun consume(): Snapshot? = synchronized(lock) {
        val pending = snapshot
        snapshot = null
        pending
    }

    /** Release a pending arm with no row (a turn that never started). */
    fun disarm() = synchronized(lock) {
        snapshot = null
    }

    /** Re-bucket a pending arm, keeping its start clock (fast-path exits). */
    fun retag(taskType: String) = synchronized(lock) {
        snapshot = snapshot?.copy(taskType = taskType)
    }

    internal fun isArmed(): Boolean = synchronized(lock) { snapshot != null }
}
