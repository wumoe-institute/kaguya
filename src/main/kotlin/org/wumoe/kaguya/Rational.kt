package org.wumoe.kaguya

import org.wumoe.kaguya.lock.Memoized
import java.math.BigDecimal

data class Rational(
    // TODO: `BigDecimal` does not fit into our requirement. implement a new one.
    val inner: BigDecimal
) : SelfEvalObject {
    override suspend fun getTag() = Companion

    companion object : PrimitiveTagWithConversion<Rational>() {
        override suspend fun convert(callCtx: Context, arg: LazyObject) = defaultConversion(arg)

        override val name = "rational"
    }

    private val str = Memoized<LazyObject>()

    override suspend fun toStrLazy() = str.getOrInit { inner.toPlainString().toStr().lazy() }
}

fun tryParseNum(s: String) =
    try {
        val num = s.replace("_", "")
        // fixme: the default parsing implementation of `BigDecimal` cannot handle radix specifiers like `0x`, `0o`, `0b`.
        Rational(BigDecimal(num))
    } catch (e: NumberFormatException) {
        null
    }