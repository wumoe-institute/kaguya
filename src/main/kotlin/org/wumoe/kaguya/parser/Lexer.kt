package org.wumoe.kaguya.parser

import kotlinx.coroutines.flow.*
import org.wumoe.kaguya.*
import org.wumoe.kaguya.parser.Token.Prefix
import kotlin.Pair

/**
 * Kaguya token.
 */
sealed interface Token {

    enum class Bracket : Token {
        LEFT, RIGHT
    }

    enum class Prefix : Token {
        QUOTE,
        QUASIQUOTE,
        UNQUOTE,
        UNQUOTE_SPLICING,
    }

    /**
     * Dots in dot notations, such as "(a . b)", "(1 2 3 . 4)", etc.
     */
    data object Dot : Token

    /**
     * Identifier.
     */
    data class Ident(val inner: String) : Token

    /**
     * String literal.
     */
    data class Str(val inner: String) : Token

    /**
     * Number literal.
     */
    data class Num(val inner: Rational) : Token

    companion object {
        fun tokenFlow(stream: CodeStream, fileNum: Int): Flow<Positioned<Token>> = flow {
            // a buffer. it can either be an identifier, or a number, or a dot (arrow).
            val ident = StringBuilder()
            for ((idx, c) in stream) {
                suspend fun emitIdent() {
                    if (ident.isNotEmpty()) {
                        val pos = Position(fileNum, idx - ident.length, ident.length)
                        val tok: Token = if (ident.contentEquals(".") || ident.contentEquals("->")) {
                            Dot
                        } else {
                            val parseResult = tryParseNum(ident.toString())
                            if (parseResult !== null) {
                                Num(parseResult)
                            } else {
                                Ident(ident.toString())
                            }
                        }
                        emit(Positioned(tok, pos))
                        ident.clear()
                    }
                }

                fun currentPos(len: Int = 1) = Position(fileNum, idx, len)
                when (c) {
                    '\'' -> {
                        emit(Prefix.QUOTE.withPos(currentPos()))
                    }

                    '`' -> {
                        emit(Prefix.QUASIQUOTE.withPos(currentPos()))
                    }

                    ',' -> {
                        emitIdent()
                        if (!stream.hasNext()) throw ParseError("Unexpected EOF after a prefix.", currentPos())
                        if (stream.peek().value == '@') {
                            emit(Prefix.UNQUOTE_SPLICING.withPos(currentPos(len=2)))
                        } else {
                            emit(Prefix.UNQUOTE.withPos(currentPos()))
                        }
                    }

                    '(' -> {
                        emitIdent()
                        emit(Positioned(Bracket.LEFT, currentPos()))
                    }

                    ')' -> {
                        emitIdent()
                        emit(Positioned(Bracket.RIGHT, currentPos()))
                    }

                    '"' -> {
                        if (ident.firstOrNull() == 'r' && ident.slice(1 until ident.length).all { it == '#' }) {
                            // all chars in ident are in BMP ("r#*"),
                            // just use ident.length - 1 here as the number of '#'s is ok.
                            val hashCount = ident.length - 1
                            ident.clear()
                            val result = parseRawStrLiteral(stream, hashCount, fileNum, idx)
                            val pos = Position(fileNum, idx - hashCount - 1, result.length + hashCount + 2)
                            emit(Str(result).withPos(pos))
                        } else {
                            emitIdent()
                            val (result, len) = parseStrLiteral(stream, fileNum, idx)
                            emit(Str(result).withPos(Position(fileNum, idx, len + 1)))
                        }
                    }

                    ';' -> {
                        emitIdent()
                        for ((_, comment) in stream) {
                            if (comment == '\n') break
                        }
                    }

                    else -> if (c.isWhitespace()) {
                        emitIdent()
                    } else {
                        ident.append(c)
                    }
                }
            }
        }
    }
}

fun Prefix.name() = Str(
    Latin1(
        when (this) {
            Prefix.QUOTE -> "quote"
            Prefix.QUASIQUOTE -> "quasiquote"
            Prefix.UNQUOTE -> "unquote"
            Prefix.UNQUOTE_SPLICING -> "unquote-splicing"
        }
    )
)

private suspend fun parseRawStrLiteral(
    stream: CodeStream,
    hashCount: Int,
    fileNum: Int,
    beginIdx: Int
): String {
    val builder = StringBuilder()
    var lastIdx = beginIdx
    loop {
        if (!stream.hasNext()) throw ParseError("Unexpected EOF while parsing str literal.", Position(fileNum, lastIdx, 1))
        val (idx, c) = stream.next()
        lastIdx = idx
        when (c) {
            '"' -> {
                var i = 0
                for ((_, maybeHash) in stream) {
                    if (maybeHash == '#') {
                        ++i
                    } else {
                        builder.append('"')
                        builder.append("#".repeat(i))
                        builder.append(maybeHash)
                        break
                    }
                    if (i >= hashCount) {
                        return builder.toString()
                    }
                }
            }
            else -> builder.append(c)
        }
    }
}

private suspend fun parseStrLiteral(
    stream: CodeStream,
    fileNum: Int,
    beginIdx: Int
): Pair<String, Int> {
    val builder = StringBuilder()
    var len = 0
    loop {
        if (!stream.hasNext()) throw ParseError("Unexpected EOF while parsing str literal.", Position(fileNum, beginIdx + len, 1))
        val (idx, c) = stream.next()
        len += 1
        when (c) {
            '"' -> return Pair(builder.toString(), len + 1)
            '\n' -> throw ParseError("Unexpected end of line.", Position(fileNum, idx, 1))
            '\\' -> {
                if (!stream.hasNext()) throw ParseError("Unexpected EOF while parsing str literal.", Position(fileNum, beginIdx + len, 1))
                len += 1
                val (escapeIdx, escape) = stream.next()
                when (escape) {
                    'n' -> builder.append('\n')
                    'r' -> builder.append('\r')
                    't' -> builder.append('\t')
                    '\\' -> builder.append('\\')
                    '"' -> builder.append('"')
                    '\'' -> builder.append('\'')
                    '0' -> builder.append('\u0000')
                    '\r' -> {
                        if (stream.peek().value == '\n') stream.next()
                    }

                    '\n' -> { /* simply do nothing */ }

                    'x' -> {
                        TODO("hex repr escaping")
                    }

                    'u' -> {
                        TODO("unicode escaping")
                    }

                    else -> throw ParseError("Unknown character escape `\\$escape`", Position(fileNum, escapeIdx, 1))
                }
            }
        }
    }
}