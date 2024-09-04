package org.wumoe.kaguya

interface Object {
    suspend fun getTag(): Object

    suspend fun eval(ctx: Context): Object

    suspend fun eq(other: Object): Boolean = this == other

    suspend fun hc(): Int = hashCode()

    suspend fun toStrLazy(): LazyObject
}

interface SelfEvalObject : Object {
    override suspend fun eval(ctx: Context) = this
}

inline fun <reified T : Object> Object.expect(
    orElse: (Object) -> T
): T =
    if (this is T) {
        this
    } else {
        orElse(this)
    }

suspend inline fun <reified T: Object> Object.expect(tag: PrimitiveTag<T>) =
    expect<T> { throw typeError(tag, this) }

suspend fun Positioned<Object>.eval(ctx: Context) =
    try {
        inner.eval(ctx)
    } catch (e: Panic) {
        throw e.apply { unwind(pos) }
    }