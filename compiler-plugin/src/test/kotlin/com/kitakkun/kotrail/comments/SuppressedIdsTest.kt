package com.kitakkun.kotrail.comments

import com.kitakkun.kotrail.KotrailConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class SuppressedIdsTest {
    private val defaults = SuppressedIds(KotrailConfig.DEFAULT_SUPPRESSION_IDS, KotrailConfig.DEFAULT_SUPPRESSION_EXEMPT_IDS)

    @Test
    fun `every id owes a reason by default`() {
        assertTrue(defaults.owesReason(listOf("KOTRAIL_CATCH_TOO_BROAD")))
        assertTrue(defaults.owesReason(listOf("UNCHECKED_CAST")))
        assertTrue(defaults.owesReason(listOf("DEPRECATION")))
    }

    @Test
    fun `an annotation whose ids are all exempt is skipped`() {
        assertFalse(defaults.owesReason(listOf("unused")))
        assertFalse(defaults.owesReason(listOf("UNUSED_PARAMETER")))
        assertFalse(defaults.owesReason(listOf("unused", "UNUSED_VARIABLE")))
        assertFalse(defaults.owesReason(emptyList()))
    }

    @Test
    fun `an annotation mixing exempt and non-exempt ids is checked`() {
        assertTrue(defaults.owesReason(listOf("unused", "DEPRECATION")))
    }

    @Test
    fun `an id outside ids is not checked even when not exempt`() {
        val kotrailOnly = SuppressedIds(listOf("KOTRAIL_*"), KotrailConfig.DEFAULT_SUPPRESSION_EXEMPT_IDS)
        assertFalse(kotrailOnly.owesReason(listOf("UNCHECKED_CAST")))
        assertTrue(kotrailOnly.owesReason(listOf("unused", "KOTRAIL_CATCH_TOO_BROAD")))
    }

    @Test
    fun `an empty exempt list checks every matching id`() {
        assertTrue(SuppressedIds(listOf("*"), emptyList()).owesReason(listOf("unused")))
    }
}
