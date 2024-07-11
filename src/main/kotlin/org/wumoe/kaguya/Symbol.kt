package org.wumoe.kaguya


data class Symbol(private val inner: String) : Object {
    override suspend fun eval(ctx: Context) =
        ctx.findSymbol(this).require()

    override suspend fun getTag() = Companion

    companion object : PrimitiveTagWithConversion<Symbol>() {
        override suspend fun convert(callCtx: Context, arg: LazyObject) = defaultConversion(arg)

        override val name = "symbol"
    }

    override val str = inner.toStr().lazy()

    override fun toString() = inner

    fun isIgnore() = inner == "_"
}