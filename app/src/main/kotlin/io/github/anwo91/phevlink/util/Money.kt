package io.github.anwo91.phevlink.util

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Amounts in the owner's currency, written the way the app's language writes them ("112 kr", "€ 4.20"). */
object Money {
    /** [amount] in [currency] (ISO code; null = just the number). Whole units from 100 up, else two decimals. */
    fun format(amount: Double, currency: String?, locale: Locale, decimals: Int? = null): String {
        val digits = decimals ?: if (kotlin.math.abs(amount) >= 100) 0 else 2
        val cur = currency?.let { runCatching { Currency.getInstance(it) }.getOrNull() }
        if (cur == null) return String.format(locale, "%.${digits}f", amount)
        // Nordic crowns read "kr" in every language the app speaks.
        if (cur.currencyCode in setOf("NOK", "SEK", "DKK")) return String.format(locale, "%,.${digits}f kr", amount)
        val f = NumberFormat.getCurrencyInstance(locale)
        f.currency = cur
        f.minimumFractionDigits = digits
        f.maximumFractionDigits = digits
        return f.format(amount)
    }

    /** A price per kWh: always two decimals ("1.31 kr/kWh"). */
    fun perKwh(amount: Double, currency: String?, locale: Locale): String = format(amount, currency, locale, decimals = 2) + "/kWh"
}
