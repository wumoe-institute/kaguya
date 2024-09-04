package org.wumoe.kaguya

/**
 * Unrecoverable error in Kaguya programs.
 *
 * @param msg The panic message.
 * @param stack The position stack unwound, indicates where the program panicked.
 */
open class Panic(val msg: String, val stack: MutableList<Position> = mutableListOf()) :
    Throwable("Program panicked: $msg") {
    /**
     * Pushes another [Position] in [stack].
     */
    fun unwind(pos: Position) {
        if (pos.isBuiltin()) return
        if (stack.lastOrNull() == pos) return
        stack.add(pos)
    }

    constructor(msg: String, pos: Position): this(msg, mutableListOf(pos))

    fun toStringRaw(): String {
        val builder = StringBuilder(msg)
        for (pos in stack) {
            builder.append("\nat $pos")
        }
        return builder.toString()
    }
}


suspend fun typeError(expected: Tag, found: Object): Panic =
    Panic("Expecting type of $expected, found ${found.getTag().toStrLazy().expect(Str).collect()}.")

fun syntaxError(msg: String) = Panic("Syntax error: $msg")

fun undefinedSymbol(symbol: Symbol) = Panic("Undefined symbol: '$symbol'.")

fun redefiningSymbol(symbol: Symbol) = Panic("Multiple definitions of symbol '$symbol'.")

fun tooLessArguments(expected: Int, given: Int) = Panic("Expecting at least $expected arguments, $given given.")

fun tooMuchArguments(expected: Int) = Panic("Expecting at most $expected arguments, too much given.")

suspend fun noConversion(tag: PrimitiveTag<*>, obj: Object) =
    Panic("Cannot convert ${obj.getTag().toStrLazy().expect(Str).collect()} to $tag.")