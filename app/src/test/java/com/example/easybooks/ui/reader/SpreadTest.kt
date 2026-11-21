package com.example.easybooks.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** Lesen wie in einem aufgeschlagenen Buch: rechte Seiten werden umgeblättert, linke gleiten weiter. */
class SpreadTest {

    @Test
    fun `with spreads only right pages are turned`() {
        // Seite 1 (Index 0) liegt rechts: umblättern. Seite 2 liegt links: weitergleiten zu Seite 3.
        assertEquals(
            listOf(true, false, true, false, true, false),
            (0..5).map { curlsAfter(it, spread = true) },
        )
    }

    @Test
    fun `without spreads every page is turned`() {
        assertEquals(List(6) { true }, (0..5).map { curlsAfter(it, spread = false) })
    }
}
