package com.princeniithompson.rapcha.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeModeGateTest {

    private var now = 0L
    private val gate = SafeModeGate(now = { now })

    @Test
    fun `alternating values inside one second never publish`() {
        assertNull(gate.consider(true))
        now = 100
        assertNull(gate.consider(false))
        now = 200
        assertNull(gate.consider(true))
        now = 900
        assertNull(gate.consider(false))
        assertNull(gate.published)
    }

    @Test
    fun `a stable result publishes once it has held for 500ms`() {
        assertNull(gate.consider(true))
        now = 499
        assertNull(gate.consider(true))
        now = 500
        assertEquals(true, gate.consider(true))
        assertNull(gate.consider(true))
    }

    @Test
    fun `a second flip inside one second is held`() {
        assertNull(gate.consider(true))
        now = 500
        assertEquals(true, gate.consider(true))
        now = 600
        assertNull(gate.consider(false))
        now = 1100
        assertNull(gate.consider(false))
        now = 1600
        assertEquals(false, gate.consider(false))
    }

    @Test
    fun `the clear baseline does not delay the first engage`() {
        assertNull(gate.consider(false))
        assertEquals(false, gate.published)
        now = 10
        assertNull(gate.consider(true))
        now = 510
        assertEquals(true, gate.consider(true))
    }
}
