package com.ledgernoir.tracker.ui.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class MonthStepperTest {
    private val now = YearMonth.of(2026, 6)

    @Test fun prev_goes_back_one_month() {
        assertEquals(YearMonth.of(2026, 5), MonthStepper.prev(now))
    }

    @Test fun next_at_current_is_noop() {
        assertEquals(now, MonthStepper.next(now, now))
    }

    @Test fun next_below_current_advances() {
        assertEquals(now, MonthStepper.next(YearMonth.of(2026, 5), now))
    }

    @Test fun can_go_forward_only_below_now() {
        assertFalse(MonthStepper.canGoForward(now, now))
        assertTrue(MonthStepper.canGoForward(YearMonth.of(2026, 5), now))
    }

    @Test fun label_and_api_param() {
        assertEquals("June 2026", MonthStepper.label(now))
        assertEquals("2026-06", MonthStepper.apiParam(now))
    }
}
