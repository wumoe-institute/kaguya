package org.wumoe.kaguya

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.io.path.Path

fun interface IO : SelfEvalObject {
    suspend fun unwrap(world: RealWorld): Object

    companion object : PrimitiveTagWithConversion<IO>() {
        override suspend fun convert(callCtx: Context, arg: LazyObject) =
            PureIO(arg)

        override val name = "io"
    }

    override suspend fun getTag() = Companion

    override suspend fun toStrLazy() = "<IO#${hashCode()}>".toStr().lazy()
}

class Import(val path: LazyObject) : IO {
    override suspend fun unwrap(world: RealWorld) = withContext(Dispatchers.IO) {
        val fullPath = Path(path.expect(Str).collect()).toRealPath()
        world.importFile(fullPath.toString())
        Nil
    }
}

class Definition(val name: Symbol, val form: LazyObject) : IO {
    override suspend fun unwrap(world: RealWorld) = Nil.also { world.define(name, form) }
}

class PureIO(val inner: LazyObject) : IO {
    override suspend fun unwrap(world: RealWorld) = inner.require()
}

class JoinIO(val ios: LazyObject) : IO {
    override suspend fun unwrap(world: RealWorld) = coroutineScope {
        PairOfList(ios.asFlow(unlimited).map {
            async { it.expect(IO).unwrap(world) }
        }.buffer().map {
            it.await().lazy()
        }.toList())
    }
}

class ThenIO(val func: LazyObject, val former: LazyObject) : IO {
    override suspend fun unwrap(world: RealWorld) = coroutineScope {
        val requiredFormer = async { former.expect(IO) }
        val requiredFunc = func.expect(Procedure)
        if (requiredFunc is Function) {
            requiredFunc.applyEvaluated(NoContext, Pair(requiredFormer.await().unwrap(world).lazy(), Nil.lazy()).lazy())
        } else {
            requiredFunc.applyMeta(NoContext, Pair(requiredFormer.await().unwrap(world).lazy(), Nil.lazy()).lazy())
        }.expect(IO).unwrap(world)
    }
}