package org.wumoe.kaguya

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.runBlocking

object Nil : PrimitiveTag<Nil>(), Object {
    override suspend fun getTag() = this

    override suspend fun eval(ctx: Context) = throw Panic("Attempt to evaluate nil.")

    override suspend fun hc() = -1

    override val name = "nil"

    override val str = Str(Latin1("()")).lazy()

    override fun hashCode() = -1
}

sealed class Pair : Object {
    override suspend fun eval(ctx: Context) =
        car.requirePositioned().eval(ctx).expect(Procedure).applyMeta(ctx, cdr)

    override suspend fun getTag() = Pair

    companion object : PrimitiveTagWithConversion<Pair>() {
        override suspend fun convert(callCtx: Context, arg: LazyObject) = defaultConversion(arg)

        override val name = "pair"
    }

    abstract val car: LazyObject
    abstract val cdr: LazyObject

    operator fun component1() = car
    operator fun component2() = cdr

    override suspend fun eq(other: Object) =
        this === other || other is Pair && listOf(car, cdr).eq(listOf(other.car, other.cdr))

    override suspend fun hc() = coroutineScope {
        val carHc = async { car.hc() }
        val cdrHc = async { cdr.hc() }
        carHc.await() + cdrHc.await() * 13
    }
    
    private object ToStrImpl : Meta {
        override suspend fun apply(callCtx: Context, args: LazyObject) =
            when (val arg = args.require()) {
                is Pair -> Str.concat(
                    Str(Latin1(" ")).lazy(),
                    arg.car.toStrLazy(),
                    ToStrImpl.applyMetaLazy(arg.cdr)
                ).require()
                is Nil -> Str(Latin1(")"))
                else -> Str.concat(
                    Str(Latin1(" . ")).lazy(),
                    arg.str,
                    Str(Latin1(")")).lazy(),
                ).require()
            }
    }

    override val str by lazy {
        Str.concat(
            Str(Latin1("(")).lazy(),
            car.toStrLazy(),
            ToStrImpl.applyMetaLazy(cdr)
        )
    }
}

fun Pair(car: LazyObject, cdr: LazyObject) = NormalPair(car, cdr)

class NormalPair(override val car: LazyObject, override val cdr: LazyObject) : Pair() {
    override fun equals(other: Any?) = runBlocking { this === other || other is Object && eq(other) }
    override fun hashCode() = runBlocking { hc() }
}

class PairOfList(val list: List<LazyObject>) : Pair() {
    override val car get() = list[0]
    override val cdr get() =
        if (list.size != 2)
            PairOfList(list.subList(1, list.size)).lazy()
        else
            list[1]

    override suspend fun eq(other: Object) =
        if (other is PairOfList)
            list.eq(other.list)
        else super.eq(other)

    override suspend fun hc() = list.hc()

    override fun equals(other: Any?) = runBlocking { this === other || other is Object && eq(other) }
    override fun hashCode() = runBlocking { hc() }
}