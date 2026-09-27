package com.ledgernoir.tracker.ui.insights

import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure month-navigation helpers for the AI Insights screen. The summary endpoint is
 *  keyed per calendar month and cannot show future months. */
object MonthStepper {
    private val LABEL = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

    fun current(): YearMonth = YearMonth.now()
    fun prev(month: YearMonth): YearMonth = month.minusMonths(1)
    fun next(month: YearMonth, now: YearMonth = current()): YearMonth =
        if (month < now) month.plusMonths(1) else month
    fun canGoForward(month: YearMonth, now: YearMonth = current()): Boolean = month < now
    fun label(month: YearMonth): String = month.format(LABEL)
    fun apiParam(month: YearMonth): String = month.toString()  // ISO "YYYY-MM"
}
