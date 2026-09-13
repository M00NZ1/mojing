package com.mojing.app.domain.billing

import java.math.BigDecimal

object BillingMath {
    fun estimate(price: ModelPricing, input: Int, output: Int, cachedInput: Int = 0): Double {
        val cached = cachedInput.coerceIn(0, input.coerceAtLeast(0))
        fun amount(tokens: Int, rate: Double) = BigDecimal.valueOf(tokens.coerceAtLeast(0).toLong())
            .multiply(BigDecimal.valueOf(rate)).movePointLeft(6)
        return amount(input.coerceAtLeast(0) - cached, price.inputPerMillion)
            .add(amount(cached, price.cachedInputPerMillion ?: price.inputPerMillion))
            .add(amount(output, price.outputPerMillion)).toDouble()
    }
}
