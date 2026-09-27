package com.ledgernoir.tracker.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsFilterTest {

    private val filter = SmsFilter()

    // --- normalizeSender ---

    @Test fun `bare AXISBK is normalized unchanged`() {
        assertEquals("AXISBK", filter.normalizeSender("AXISBK"))
    }

    @Test fun `AX-AXISBK operator prefix is stripped`() {
        assertEquals("AXISBK", filter.normalizeSender("AX-AXISBK"))
    }

    @Test fun `VM-IDFCFB is normalized to IDFCFB`() {
        assertEquals("IDFCFB", filter.normalizeSender("VM-IDFCFB"))
    }

    @Test fun `VK-BOBTXN is normalized to BOBTXN`() {
        assertEquals("BOBTXN", filter.normalizeSender("VK-BOBTXN"))
    }

    @Test fun `three-char prefix with dash is stripped`() {
        assertEquals("AXISBK", filter.normalizeSender("AD2-AXISBK"))
    }

    @Test fun `lowercase sender is uppercased`() {
        assertEquals("AXISBK", filter.normalizeSender("ax-axisbk"))
    }

    // --- isWhitelisted ---

    @Test fun `AX-AXISBK is whitelisted`() {
        assertTrue(filter.isWhitelisted("AX-AXISBK"))
    }

    @Test fun `VM-IDFCFB is whitelisted`() {
        assertTrue(filter.isWhitelisted("VM-IDFCFB"))
    }

    @Test fun `VK-BOBTXN is whitelisted (exact match)`() {
        assertTrue(filter.isWhitelisted("VK-BOBTXN"))
    }

    @Test fun `sender that merely starts with a bank id is rejected`() {
        assertFalse(filter.isWhitelisted("VK-BOBSPAM"))
        assertFalse(filter.isWhitelisted("AX-AXISBKX"))
    }

    @Test fun `bare BOB is no longer whitelisted`() {
        assertFalse(filter.isWhitelisted("VK-BOB"))
    }

    @Test fun `bare AXISBK is whitelisted`() {
        assertTrue(filter.isWhitelisted("AXISBK"))
    }

    @Test fun `bare IDFCFB is whitelisted`() {
        assertTrue(filter.isWhitelisted("IDFCFB"))
    }

    @Test fun `HDFC is not whitelisted`() {
        assertFalse(filter.isWhitelisted("VD-HDFCBK"))
    }

    @Test fun `empty string is not whitelisted`() {
        assertFalse(filter.isWhitelisted(""))
    }

    @Test fun `unknown bank prefix is not whitelisted`() {
        assertFalse(filter.isWhitelisted("XX-RANDOM"))
    }

    @Test fun `numeric-only sender is not whitelisted`() {
        assertFalse(filter.isWhitelisted("1234567890"))
    }

    @Test fun `3-part DLT AX-IDFCFB-S normalizes to IDFCFB`() {
        assertEquals("IDFCFB", filter.normalizeSender("AX-IDFCFB-S"))
    }

    @Test fun `3-part DLT AD-IDFCFB-T is whitelisted`() {
        assertTrue(filter.isWhitelisted("AD-IDFCFB-T"))
    }

    @Test fun `3-part DLT JD-AXISBK-P is whitelisted`() {
        assertTrue(filter.isWhitelisted("JD-AXISBK-P"))
    }

    @Test fun `3-part DLT VK-BOBTXN-S is whitelisted`() {
        assertTrue(filter.isWhitelisted("VK-BOBTXN-S"))
    }

    @Test fun `3-part non-bank entity rejected`() {
        assertFalse(filter.isWhitelisted("JR-JIOPAY-S"))
    }
}
