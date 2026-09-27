package com.ledgernoir.tracker.data.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightSummaryResponseTest {
    // Mirrors the production Moshi setup in ExpenseRepository, which uses the reflection
    // adapter factory (the app does not rely on Moshi codegen at runtime).
    private val adapter = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        .adapter(InsightSummaryResponse::class.java)

    @Test
    fun parses_enabled_response() {
        val json = """{"period":"2026-06","enabled":true,"available":true,"model":"q7b",
            "generated_at":"2026-06-14T01:12:30+05:30","spending_patterns":"A","how_to_save":"B","money_radar":"C"}"""
        val r = adapter.fromJson(json)!!
        assertEquals("2026-06", r.period)
        assertTrue(r.enabled); assertTrue(r.available)
        assertEquals("A", r.spendingPatterns)
        assertEquals("B", r.howToSave)
        assertEquals("C", r.moneyRadar)
        assertEquals("2026-06-14T01:12:30+05:30", r.generatedAt)
    }

    @Test
    fun parses_disabled_response_with_null_generated_at() {
        val json = """{"period":"2026-06","enabled":false,"available":false,"model":"q7b",
            "generated_at":null,"spending_patterns":"","how_to_save":"","money_radar":""}"""
        val r = adapter.fromJson(json)!!
        assertFalse(r.enabled); assertFalse(r.available)
        assertNull(r.generatedAt)
        assertEquals("", r.spendingPatterns)
    }
}
