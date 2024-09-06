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
     * A map from the full path of the source file to its content.
     */
    private val sources: MutableMap<String, String> = mutableMapOf(),
    private val globalScope: MutableContext = MutableContext(Intrinsics),
) {
    open suspend fun print(piece: Piece) {
        kotlin.io.print(piece)
    }

    open suspend fun println() {
        kotlin.io.println()
    }

    suspend fun println(piece: Piece) {
        print(piece)
        println()
    }

    suspend fun print(str: Str) {
        str.asPieceFlow().collect {
            print(it)
        }
    }

    suspend fun println(str: Str) {
        print(str)
        println()
    }

    fun define(name: Symbol, obj: LazyObject) {
        globalScope.put(name, obj)
    }

    fun parseFileRaw(fullPath: String, content: String): Flow<Positioned<Object>>? =
        if (sources.putIfAbsent(fullPath, content) !== null) {
            null
        } else {
            flow {
                coroutineScope {
                    val charStream = content.asIterable().asFlow().withIndex().streamIn(this)
                    val tokenStream = Token.tokenFlow(charStream, fullPath).streamIn(this)
                    while (tokenStream.hasNext()) {
                        emit(parseSingle(tokenStream, fullPath))
                    }
                }
            }
        }

    private fun readFile(fullPath: String): String? {
        if (sources.contains(fullPath)) return null
        return try {
            val reader = FileReader(fullPath)
            val result = reader.readText()
            reader.close()
            result
        } catch (e: FileNotFoundException) {
            throw Panic(e.message ?: "")
        }
    }

    fun removeFile(fullPath: String) = sources.remove(fullPath)

    fun parseFile(fullPath: String) =
        readFile(fullPath)?.let { parseFileRaw(fullPath, it) }


    suspend fun importFile(fullPath: String) =
        readFile(fullPath)?.let { importFileRaw(fullPath, it) }

    suspend fun importFileRaw(fullPath: String, reader: String) = withContext(Dispatchers.IO) {
        parseFileRaw(fullPath, reader)?.collect {
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

    suspend fun loadPrelude() {
        if (sources.contains(preludePlaceholderName)) return
        val prelude = checkNotNull(RealWorld::class.java.getResourceAsStream("/prelude.hime")) {
            "'prelude.hime' not found in resources."
        }.reader().readText()
        try {
            importFileRaw(preludePlaceholderName, prelude)
        } catch (e: Panic) {
            println("prelude panicked: ${e.toStringRaw()}")
            throw e
        } catch (e: ParseError) {
            println("parse error in prelude: $e\nat ${e.pos}")
            throw e
        }
    }

    fun getSource(fullPath: String) = sources[fullPath]
}

private const val preludePlaceholderName = "<PRELUDE>"

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