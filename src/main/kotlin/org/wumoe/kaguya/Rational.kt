package org.wumoe.kaguya

import java.math.BigDecimal

class Rational(
    // TODO: `BigDecimal` does not fit into our requirement. implement a new one.
    val inner: BigDecimal
) : SelfEvalObject {
    override suspend fun getTag() = Companion

    companion object : PrimitiveTagWithConversion<Rational>() {
        override suspend fun convert(callCtx: Context, arg: LazyObject) = defaultConversion(arg)

        override val name = "rational"
    }

    override val str get() = inner.toPlainString().toStr().lazy()
}

fun tryParseNum(s: String) =
    try {
        val num = s.replace("_", "")
        // fixme: the default parsing implementation of `BigDecimal` cannot handle radix specifiers like `0x`, `0o`, `0b`.
        Rational(BigDecimal(num))
    } catch (e: NumberFormatException) {
        null
    }