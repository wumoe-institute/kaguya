package org.wumoe.kaguya


class Bool(val inner: Boolean) : Object, SelfEvalObject {
    override suspend fun getTag() = Companion

    companion object : PrimitiveTagWithConversion<Bool>() {
        override val name = "bool"

        override suspend fun convert(callCtx: Context, arg: LazyObject) = defaultConversion(arg)
    }

    override suspend fun toStrLazy() = inner.toString().toStr().lazy()
}