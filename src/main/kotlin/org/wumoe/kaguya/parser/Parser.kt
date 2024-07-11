package org.wumoe.kaguya.parser

import org.wumoe.kaguya.*

suspend fun parseSingle(tokenStream: TokenStream, fileNum: Int): Positioned<Object> {
    check(tokenStream.hasNext()) { "calling parseSingle on an empty tokenStream." }

    val (token, pos) = tokenStream.next()
    return when (token) {
        Token.Bracket.LEFT -> parseList(tokenStream, fileNum, pos)
        Token.Bracket.RIGHT -> throw ParseError("Unexpected ')'.", pos)
        Token.Dot -> throw ParseError("Unexpected dot in this position.", pos)
        is Token.Ident -> Symbol(token.inner).withPos(pos)
        is Token.Num -> token.inner.withPos(pos)
        is Token.Prefix -> {
            if (!tokenStream.hasNext()) throw ParseError("Unexpected EOF after a prefix.", pos)
            val prefixed = parseSingle(tokenStream, fileNum)
            val fullPos = pos until prefixed.pos
            fromList(mutableListOf(token.name().withPos(pos), prefixed), fullPos, isProper=true).withPos(fullPos)
        }
        is Token.Str -> {
            Str.fromString(token.inner).withPos(pos)
        }
    }
}

private enum class DotStatus {
    NoDot,
    JustMetADot,
    OneElementAfterDot,
}

private suspend fun parseList(tokenStream: TokenStream, fileNum: Int, leftBracketPos: Position): Positioned<Object> {
    var dotStatus = DotStatus.NoDot
    val buffer = mutableListOf<Positioned<Object>>()
    loop {
        if (!tokenStream.hasNext()) throw ParseError("Unexpected EOF. This '(' was never closed.", leftBracketPos)
        val (token, pos) = tokenStream.peek()
        when (token) {
            Token.Bracket.RIGHT -> {
                tokenStream.next()
                val isProper = when (dotStatus) {
                    DotStatus.NoDot -> true
                    DotStatus.JustMetADot -> throw ParseError("Unexpected ')'. Expecting one element after a dot.", pos)
                    DotStatus.OneElementAfterDot -> false
                }
                val fullPos = leftBracketPos until pos
                return fromList(buffer, fullPos, isProper).withPos(fullPos)
            }
            Token.Dot -> {
                dotStatus = DotStatus.JustMetADot
                tokenStream.next()
            }
            else -> {
                when (dotStatus) {
                    DotStatus.NoDot -> { /* do nothing */ }
                    DotStatus.JustMetADot -> dotStatus = DotStatus.OneElementAfterDot
                    DotStatus.OneElementAfterDot -> throw ParseError("Expecting ')'. Only one element is allowed after a dot.", pos)
                }
                buffer.add(parseSingle(tokenStream, fileNum))
            }
        }
    }
}

private fun fromList(list: MutableList<Positioned<Object>>, pos: Position, isProper: Boolean): Object =
    if (isProper) {
        if (list.isEmpty())
            Nil
        else
            PairOfList(list.apply { add(Nil.withPos(pos)) }.map { it.lazy() })
    } else if (list.size >= 2) {
        PairOfList(list.map { it.lazy() })
    } else {
        error("Creating an improper-list with size ${list.size}")
    }

class ParseError(override val message: String, val pos: Position) : Throwable()