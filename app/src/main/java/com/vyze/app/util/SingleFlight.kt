package com.vyze.app.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single-flight gate: concurrent [run] callers join the one in-flight
 * execution instead of each running [block].
 *
 * Exists for engine init (device evidence 2026-11-12, X6891): the 60 s
 * LoadingFragment safety timeout navigates away while init still builds,
 * and the recreated fragment starts a SECOND initialize() — two overlapping
 * inits, two backend plans, double builds and double program-cache churn.
 * Joiners await the first flight's result; a caller arriving after the
 * flight completes starts a fresh one (so init retries still work).
 *
 * Cancellation: awaiting never cancels the shared flight (it is a child
 * of [scope], not of any caller) — a cancelled joiner just stops waiting.
 */
internal class SingleFlight<T>(private val scope: CoroutineScope) {

    private val mutex = Mutex()
    private var ongoing: Deferred<T>? = null

    suspend fun run(block: suspend () -> T): T {
        val flight = mutex.withLock {
            ongoing?.takeIf { it.isActive } ?: scope.async { block() }.also { ongoing = it }
        }
        try {
            return flight.await()
        } finally {
            mutex.withLock {
                if (ongoing === flight && flight.isCompleted) ongoing = null
            }
        }
    }
}
