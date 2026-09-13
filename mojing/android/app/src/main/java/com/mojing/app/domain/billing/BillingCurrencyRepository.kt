package com.mojing.app.domain.billing

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.time.LocalDate
import okhttp3.OkHttpClient
import okhttp3.Request

data class CurrencyDisplayState(
    val displayCurrency: String = "CNY",
    val usdToCny: Double? = null,
    val rateDate: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

@Singleton
class BillingCurrencyRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val client: OkHttpClient,
) {
    private val preferences = context.getSharedPreferences("mojing_billing_currency", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(readCached())
    val state: StateFlow<CurrencyDisplayState> = _state.asStateFlow()

    private var revision = 0L
    private var activeCall: Call? = null

    fun setDisplayCurrency(currency: String) {
        val normalized = currency.uppercase(Locale.US).takeIf { it == "USD" || it == "CNY" } ?: return
        preferences.edit().putString(KEY_DISPLAY, normalized).apply()
        _state.value = _state.value.copy(displayCurrency = normalized, error = null)
    }

    suspend fun refreshRate() {
        if (_state.value.loading) return
        val token = ++revision
        _state.value = _state.value.copy(loading = true, error = null)
        try {
            val (rate, date) = fetchRate()
            if (token == revision) {
                saveRate(rate, date)
                _state.value = _state.value.copy(usdToCny = rate, rateDate = date, loading = false)
            }
        } catch (cancelled: CancellationException) {
            if (token == revision) _state.value = _state.value.copy(loading = false)
            throw cancelled
        } catch (_: Exception) {
            if (token == revision) _state.value = _state.value.copy(loading = false, error = "汇率获取失败，请重试或手动填写")
        } finally { if (token == revision) activeCall = null }
    }

    fun setManualRate(rate: Double) {
        if (!rate.isFinite() || rate <= 0.0) return
        ++revision
        activeCall?.cancel()
        activeCall = null
        saveRate(rate, "手动")
        _state.value = _state.value.copy(usdToCny = rate, rateDate = "手动", loading = false, error = null)
    }

    private fun saveRate(rate: Double, date: String) {
        preferences.edit().putString(KEY_RATE, rate.toString()).putString(KEY_DATE, date).apply()
    }

    private suspend fun fetchRate(): Pair<Double, String> {
        val call = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(ECB_URL).get().build())
        activeCall = call
        val response = suspendCancellableCoroutine<Response> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(error)
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
        return response.use { body ->
            withContext(Dispatchers.IO) {
                check(body.isSuccessful)
                parseEcbRate(body.body?.string().orEmpty())
            }
        }
    }

    private fun readCached(): CurrencyDisplayState {
        val stored = preferences.all[KEY_RATE]
        val rate = stored?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        val currency = preferences.getString(KEY_DISPLAY, "CNY")?.takeIf { it in setOf("CNY", "USD") } ?: "CNY"
        return CurrencyDisplayState(displayCurrency = currency, usdToCny = rate,
            rateDate = preferences.getString(KEY_DATE, "").orEmpty())
    }

    private companion object {
        const val ECB_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"
        const val KEY_RATE = "usd_to_cny"
        const val KEY_DATE = "rate_date"
        const val KEY_DISPLAY = "display_currency"
    }
}

internal fun parseEcbRate(xml: String): Pair<Double, String> {
    val tags = Regex("<[^>]+>").findAll(xml).map { it.value }.toList()
    fun attribute(tag: String, name: String): String? =
        Regex("\\b$name=['\"]([^'\"]+)['\"]").find(tag)?.groupValues?.get(1)
    val date = tags.firstNotNullOfOrNull { attribute(it, "time") } ?: error("缺少汇率日期")
    LocalDate.parse(date)
    fun rate(code: String): Double = tags.firstNotNullOfOrNull { tag ->
        if (attribute(tag, "currency") == code) attribute(tag, "rate")?.toDoubleOrNull() else null
    }?.takeIf { it.isFinite() && it > 0 } ?: error("缺少有效汇率")
    val result = rate("CNY") / rate("USD")
    require(result.isFinite() && result > 0)
    return result to date
}

fun formatBillingAmount(amount: Double, currency: String, state: CurrencyDisplayState): String {
    if (!amount.isFinite() || amount < 0) return "—"
    val source = currency.uppercase(Locale.US)
    val target = state.displayCurrency.uppercase(Locale.US)
    val rate = state.usdToCny?.takeIf { it.isFinite() && it > 0 }
    val converted = when {
        source == target -> amount
        source == "USD" && target == "CNY" && rate != null -> amount * rate
        source == "CNY" && target == "USD" && rate != null -> amount / rate
        else -> null
    }
    return if (converted == null) formatOriginal(amount, source) else if (target == "CNY") formatPrecise("¥", converted, Locale.CHINA) else formatPrecise("US$", converted, Locale.US)
}

private fun formatOriginal(amount: Double, currency: String): String = when (currency) {
    "CNY" -> formatPrecise("¥", amount, Locale.CHINA)
    "USD" -> formatPrecise("US$", amount, Locale.US)
    else -> String.format(Locale.US, "%.6f %s", amount, currency)
}

private fun formatPrecise(prefix: String, amount: Double, locale: Locale): String =
    if (amount > 0 && amount < 0.000001) "${prefix}<0.000001"
    else String.format(locale, "%s%.6f", prefix, amount)
