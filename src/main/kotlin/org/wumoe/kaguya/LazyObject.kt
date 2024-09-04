package org.wumoe.kaguya

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.wumoe.kaguya.lock.Memoized
import org.wumoe.kaguya.lock.OnceLock

class LazyObject private constructor(
    private var inner: LazyObjectInner,
    private val lock: OnceLock,
    val pos: Position
) {
    companion object {
        /**
         * Create a [LazyObject] which wraps the evaluation result of [form].
         *
         * Note that this doesn't actually evaluate [form], the actual evaluation
         * is delayed until [require]d.
         */
        fun eval(form: Positioned<Object>, ctx: Context): LazyObject {
            return LazyObject(Thunk(form.inner, ctx), OnceLock(), form.pos)
        }
    }

    constructor(value: Positioned<Object>) : this(
        Evaluated(Result.success(value.inner)), OnceLock(fused = true), value.pos
    )

    suspend fun require(): Object {
        lock.runOnce {
            val (form, ctx) = inner as Thunk
            inner = Evaluated(try {
                val result = form.eval(ctx)
                Result.success(result)
            } catch (panic: Panic) {
                panic.unwind(pos)
                Result.failure(panic)
            })
        }
        return (inner as Evaluated).result.getOrThrow()
    }

    fun immediatelyOrNull() =
        if (lock.isFused()) {
            (inner as Evaluated).result.getOrThrow()
        } else {
            null
        }

    suspend fun requirePositioned() = Positioned(require(), pos)

    suspend inline fun <reified T : Object> expect(
        orElse: (Object) -> T
    ) = require().expect(orElse)

    suspend inline fun <reified T: Object> expect(tag: PrimitiveTag<T>) = require().expect(tag)

    suspend fun eq(other: LazyObject) = coroutineScope {
        val lhs = async { require() }
        val rhs = async { other.require() }
        lhs.await() == rhs.await()
    }

    override fun equals(other: Any?) = runBlocking { other === this || other is LazyObject && eq(other) }

    suspend fun hc() = require().hc()

    override fun hashCode() = runBlocking { hc() }

    private val str = Memoized<LazyObject>()

    suspend fun toStrLazy() = str.getOrInit {
        eval(Pair(Meta { _, arg ->
            arg.require().toStrLazy().require()
        }.lazy(), this).withPos(Position.builtin), NoContext)
    }

    fun evalLazy(ctx: Context) = eval(Pair(Meta { _, arg ->
        arg.requirePositioned().eval(ctx)
    }.lazy(), this).withPos(pos), NoContext)
}

private sealed interface LazyObjectInner

private data class Thunk(val form: Object, val ctx: Context) : LazyObjectInner

private data class Evaluated(val result: Result<Object>) : LazyObjectInner

/**
 * Another way of writing `LazyObject(this)`
 */
fun <T : Object> Positioned<T>.lazy() = LazyObject(this)

/**
 * Another way of writing `LazyObject(this.withPos(pos))`
 */
fun <T : Object> T.lazy(pos: Position = Position.builtin) = withPos(pos).lazy()


internal suspend fun List<LazyObject>.eq(other: List<LazyObject>) = coroutineScope {
    if (size == other.size) {
        val failed = object : Throwable() {}
        try {
            val tasks = List(size) { i ->
                async {
                    if (!get(i).eq(other[i])) {
                        throw failed
                    }
                }
            }
            tasks.forEach { it.await() }
            true
        } catch (e: Throwable) {
            if (e === failed) {
                false
            } else {
                throw e
            }
        }
    } else {
        false
    }
}

internal suspend fun List<LazyObject>.hc() = coroutineScope {
    val hcs = map { async { it.hc() } }
    if (hcs.isEmpty()) {
        -1
    } else {
        hcs.fold(0) { acc, elem -> acc * 13 + elem.await() }
    }
}