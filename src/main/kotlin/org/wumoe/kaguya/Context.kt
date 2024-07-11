package org.wumoe.kaguya

interface Context {
    val findSymbol: DeepRecursiveFunction<Symbol, LazyObject>
}

class ChildContext(private val symbols: Map<Symbol, LazyObject>, private val parent: Context) : Context {
    override val findSymbol = DeepRecursiveFunction {
        val result = symbols[it]
        if (result !== null) {
            result
        } else {
            parent.findSymbol.callRecursive(it)
        }
    }
}

object NoContext : Context {
    override val findSymbol = DeepRecursiveFunction<Symbol, LazyObject> { throw undefinedSymbol(it) }
}