package org.wumoe.kaguya

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.wumoe.kaguya.parser.ParseError
import org.wumoe.kaguya.parser.Token
import org.wumoe.kaguya.parser.parseSingle
import org.wumoe.kaguya.parser.streamIn
import java.io.FileNotFoundException
import java.io.FileReader

open class RealWorld(
    /**
     * A map from `Int` to full paths of the source files.
     */
    private val sourcePaths: MutableList<String> = mutableListOf(),
    private val globalScope: MutableContext = MutableContext(Intrinsics),
) {
    open suspend fun print(piece: Piece) {
        kotlin.io.print(piece)
    }

    open suspend fun println() {
        kotlin.io.println()
    }

    fun define(name: Symbol, obj: LazyObject) {
        globalScope.put(name, obj)
    }

    fun parseFile(fullPath: String): Flow<Positioned<Object>>? {
        if (sourcePaths.contains(fullPath)) return null
        val reader = try {
            FileReader(fullPath)
        } catch (e: FileNotFoundException) {
            TODO()
        }
        val fileNo = sourcePaths.size
        sourcePaths.add(fullPath)
        return flow {
            coroutineScope {
                val charStream = flow {
                    // I don't know why `forEachLine` is not inline
                    reader.useLines {
                        it.forEach { line ->
                            emitAll(line.asSequence().asFlow())
                            emit('\n')
                        }
                    }
                }.withIndex().streamIn(this)
                val tokenStream = Token.tokenFlow(charStream, fileNo).streamIn(this)
                while (tokenStream.hasNext()) {
                    emit(parseSingle(tokenStream, fileNo))
                }
            }
        }
    }

    suspend fun importFile(fullPath: String) = withContext(Dispatchers.IO) {
        parseFile(fullPath)?.collect {
            try {
                evalAndExecute(it.inner)
            } catch (e: Panic) {
                throw e.apply { unwind(it.pos) }
            }
        }
    }

    suspend fun eval(form: Object) = form.eval(globalScope)

    suspend fun eval(form: Positioned<Object>) =
        try {
            eval(form.inner)
        } catch (e: Panic) {
            e.apply { unwind(form.pos) }
            throw e
        }

    suspend fun evalAndExecute(form: Object) =
        when (val result = eval(form)) {
            is IO -> result.unwrap(this)
            else -> result
        }

    suspend fun evalAndExecute(form: Positioned<Object>) =
        try {
            evalAndExecute(form.inner)
        } catch (e: Panic) {
            e.apply { unwind(form.pos) }
            throw e
        }

    companion object {
        suspend fun withPrelude(): RealWorld {
            val path = checkNotNull(RealWorld::class.java.getResource("/prelude.hime")) {
                "'prelude.hime' not found in resources."
            }.path
            val world = RealWorld()
            try {
                world.importFile(path)
            } catch (e: Panic) {
                println("prelude panicked: ${e.toStringRaw()}")
                throw e
            } catch (e: ParseError) {
                println("parse error in prelude: $e\nat ${e.pos}")
                throw e
            }
            return world
        }
    }
}

class MutableContext(val parent: Context) : Context {
    private data class PolyBuilder(val list: MutableList<LazyObject>)

    private val inner = mutableMapOf<Symbol, PolyBuilder>()

    fun put(name: Symbol, obj: LazyObject) {
        if (name.isIgnore()) return
        val slot = inner[name]
        if (slot !== null) {
            val list = slot.list
            assert(list.size >= 2)
            list[list.size - 1] = obj
            list.add(Nil.lazy())
        } else {
            inner[name] = PolyBuilder(mutableListOf(obj, Nil.lazy()))
        }
    }

    override val findSymbol = DeepRecursiveFunction {
        val slot = inner[it]
        if (slot !== null) {
            val list = slot.list
            assert(list.size >= 2)
            if (list.size == 2) {
                list[0]
            } else {
                PolyProcedure(PairOfList(list).lazy()).lazy()
            }
        } else {
            parent.findSymbol.callRecursive(it)
        }
    }
}