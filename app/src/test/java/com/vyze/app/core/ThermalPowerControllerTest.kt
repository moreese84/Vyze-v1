package com.vyze.app.core

import android.os.PowerManager
import org.junit.Assert.*
import org.junit.Test

/**
 * JVM tests for the thermal governor's policy ladder (whole-app audit
 * P3 #112).
 *
 * The OS status integers are compile-time constants, so the mapping
 * pins without a device. Engine lifecycle (LiteRT init/close/switch)
 * has no JVM seam and stays device-verified; the adaptive-backend
 * policy around it is already pinned separately.
 */
class ThermalPowerControllerTest {

    @Test
    fun `status ladder maps to the documented policies`() {
        assertEquals(
            ThermalPolicy.NORMAL,
            ThermalPowerController.companionPolicyFor(PowerManager.THERMAL_STATUS_NONE)
        )
        assertEquals(
            ThermalPolicy.NORMAL,
            ThermalPowerController.companionPolicyFor(PowerManager.THERMAL_STATUS_LIGHT)
        )
        assertEquals(
            ThermalPolicy.MODERATE,
            ThermalPowerController.companionPolicyFor(PowerManager.THERMAL_STATUS_MODERATE)
        )
        assertEquals(
            ThermalPolicy.SEVERE,
            ThermalPowerController.companionPolicyFor(PowerManager.THERMAL_STATUS_SEVERE)
        )
        assertEquals(
            ThermalPolicy.CRITICAL,
            ThermalPowerController.companionPolicyFor(PowerManager.THERMAL_STATUS_CRITICAL)
        )
        assertEquals(
            ThermalPolicy.CRITICAL,
            ThermalPowerController.companionPolicyFor(PowerManager.THERMAL_STATUS_EMERGENCY)
        )
        assertEquals(
            ThermalPolicy.CRITICAL,
            ThermalPowerController.companionPolicyFor(PowerManager.THERMAL_STATUS_SHUTDOWN)
        )
    }

    @Test
    fun `unknown future status stays permissive`() {
        assertEquals(ThermalPolicy.NORMAL, ThermalPowerController.companionPolicyFor(99))
    }

    @Test
    fun `tiers ascend from normal to critical`() {
        assertEquals(ThermalPolicy.TIER_NORMAL, ThermalPolicy.NORMAL.tier)
        assertEquals(ThermalPolicy.TIER_MODERATE, ThermalPolicy.MODERATE.tier)
        assertEquals(ThermalPolicy.TIER_SEVERE, ThermalPolicy.SEVERE.tier)
        assertEquals(ThermalPolicy.TIER_CRITICAL, ThermalPolicy.CRITICAL.tier)
        assertTrue(ThermalPolicy.TIER_NORMAL < ThermalPolicy.TIER_MODERATE)
        assertTrue(ThermalPolicy.TIER_MODERATE < ThermalPolicy.TIER_SEVERE)
        assertTrue(ThermalPolicy.TIER_SEVERE < ThermalPolicy.TIER_CRITICAL)
    }

    @Test
    fun `normal imposes no overrides`() {
        assertTrue(ThermalPolicy.NORMAL.vlmInferenceAllowed)
        assertTrue(ThermalPolicy.NORMAL.continuousModeAllowed)
        assertEquals(0, ThermalPolicy.NORMAL.sceneImageDimension)
        assertEquals(0, ThermalPolicy.NORMAL.sceneTokenCap)
    }

    @Test
    fun `severe pauses continuous but allows vlm`() {
        assertFalse(ThermalPolicy.SEVERE.continuousModeAllowed)
        assertTrue(ThermalPolicy.SEVERE.vlmInferenceAllowed)
    }

    @Test
    fun `critical halts vlm`() {
        assertFalse(ThermalPolicy.CRITICAL.vlmInferenceAllowed)
        assertFalse(ThermalPolicy.CRITICAL.continuousModeAllowed)
    }
}
