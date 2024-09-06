package org.wumoe.kaguya

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.wumoe.kaguya.parser.ParseError
import org.wumoe.kaguya.parser.streamIn

suspend fun main() {
    val world = RealWorld()
    world.loadPrelude()
    var i = 1
    while (true) {
        // TODO: the current implementation can't handle unfinished expressions
        val expr = withContext(Dispatchers.IO) {
            print(">>")
            readln()
        } + "\n"
        world.catchPanic(expr) {
            coroutineScope {
                val forms = world.parseFileRaw("<FROM_STDIN#$i>", expr)!!.streamIn(this)
                for ((form, pos) in forms) {
                    try {
                        handleSingle(world, form)
                    } catch (e: Panic) {
                        throw e.apply { unwind(pos) }
                    }
                }
            }
        }
        ++i
    }
}

suspend fun handleSingle(world: RealWorld, form: Object) {
    when (val evaluated = world.eval(form)) {
        is IO -> {
            val result = evaluated.unwrap(world)
            if (result !is Nil) {
                world.println(result.toStr())
            }
        }

        else -> {
            world.println(evaluated.toStr())
        }
    }
}

data class LineIndex(val line: Int, val char: Int) {
    override fun toString() = "$line:$char"
}

data class LineIndexed<T>(val value: T, val pos: LineIndex)

fun String.withLineIndex(): List<LineIndexed<Char>> {
    val list = this.toList()
    val result = ArrayList<LineIndexed<Char>>(length)
    var line = 1
    var char = 1
    for (c in list) {
        result.add(LineIndexed(c, LineIndex(line, char)))
        when (c) {
            '\n' -> {
                ++line
                char = 1
            }
            else -> {
                ++char
            }
        }
    }
    return result
}

inline fun RealWorld.catchPanic(src: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Panic) {
        println("Program panicked with following message: ${e.msg}")
        for (pos in e.stack) {
            if (pos.file !== null) {
                val lineIndexed = getSource(pos.file)!!.withLineIndex()
                val begin = lineIndexed[pos.idx].pos
                val end = lineIndexed[pos.endIdx - 1].pos
                println("at ${pos.file} -- $begin..$end;")
            }
        }
    } catch (e: ParseError) {
        val lineIndexed = src.withLineIndex()
        val begin = lineIndexed[e.pos.idx].pos
        val end = lineIndexed[e.pos.endIdx - 1].pos
        println("Parse error: ${e.message}\nat $begin..$end.")
    }
}