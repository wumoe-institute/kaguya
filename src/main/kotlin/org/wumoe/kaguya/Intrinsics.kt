package org.wumoe.kaguya

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.onSuccess
import kotlinx.coroutines.flow.*
import org.wumoe.kaguya.parser.*

const val unlimited = Channel.BUFFERED

fun LazyObject.asFlow(
    min: Int,
    max: Int = min,
    onLast: suspend (Object) -> Unit = { if (it !is Nil) throw typeError(Nil, it) }
) = flow {
    require(max >= min)
    var next = this@asFlow
    if (min == unlimited) {
        require(max == unlimited)
        while (true) {
            val required = next.require()
            if (required !is Pair) {
                onLast(required)
                break
            }
            emit(required.car)
            next = required.cdr
        }
    } else {
        for (i in 0 until min) {
            val required = next.require()
            if (required !is Pair) throw tooLessArguments(min, i)
            emit(required.car)
            next = required.cdr
        }
        if (max == unlimited) {
            while (true) {
                val required = next.require()
                if (required !is Pair) {
                    onLast(required)
                    break
                }
                emit(required.car)
                next = required.cdr
            }
        } else {
            for (i in min until max) {
                val required = next.require()
                if (required !is Pair) {
                    onLast(required)
                    break
                }
                emit(required.car)
                next = required.cdr
            }
            val required = next.require()
            if (required is Pair) throw tooMuchArguments(max)
            onLast(required)
        }
    }
}.buffer(max)

inline fun fixedParamNumFunction(min: Int, max: Int = min, crossinline inner: suspend CoroutineScope.(ReceiveChannel<LazyObject>, Context) -> Object) =
    Function { callCtx, args -> coroutineScope { inner(args.asFlow(min, max).produceIn(this), callCtx) } }

object Intrinsics : Context {
    private val symbols = mutableMapOf<Symbol, LazyObject>()

    private fun def(
        name: String,
        obj: Object
    ) {
        symbols[Symbol(name)] = obj.lazy()
    }


    private inline fun def(
        name: String,
        min: Int,
        max: Int = min,
        crossinline inner: suspend CoroutineScope.(ReceiveChannel<LazyObject>) -> Object
    ) {
        symbols[Symbol(name)] = fixedParamNumFunction(min, max) { args, _ -> inner(args) }.lazy()
    }

    private inline fun defNumeric(
        name: String,
        crossinline inner: (Rational, Rational) -> Object
    ) {
        symbols[Symbol(name)] = fixedParamNumFunction(2) { args, _ ->
            val lhs = args.receive().let { async { it.expect(Rational) } }
            val rhs = args.receive().let { async { it.expect(Rational) } }
            inner(lhs.await(), rhs.await())
        }.lazy()
    }

    init {
        def("if", 3) { args ->
            val cond = args.receive().expect(Bool)
            if (cond.inner) {
                val result = args.receive()
                args.cancel()
                result
            } else {
                args.receive()
                args.receive()
            }.require()
        }

        symbols[Symbol("fn")] = Def.Fn.wrapper.lazy()

        symbols[Symbol("meta")] = Def.Meta.wrapper.lazy()

        symbols[Symbol("macro")] = Def.Macro.wrapper.lazy()

        symbols[Symbol("macro-fn")] = Def.MacroFn.wrapper.lazy()

        symbols[Symbol("poly")] = Function { _, args ->
            PolyProcedure(args)
        }.lazy()

        symbols[Symbol("scope")] = Meta { parentCtx, args ->
            coroutineScope {
                val ctx = MutableContext(parentCtx)
                var finalForm: Object? = null
                args.asFlow(unlimited, onLast = { finalForm = it }).map {
                    async {
                        val def = it.require().eval(parentCtx)
                        if (def !is Definition) throw Panic("Non-definition passed to 'scope'.")
                        Pair(def.name, def.form.evalLazy(ctx))
                    }
                }.buffer().collect {
                    val (name, form) = it.await()
                    ctx.put(name, form)
                }
                finalForm!!.eval(ctx)
            }
        }.lazy()

        symbols[Symbol("join")] = Function { _, args ->
            JoinIO(args)
        }.lazy()

        def("then", 2) { args ->
            val func = args.receive()
            val former = args.receive()
            ThenIO(func, former)
        }

        symbols[Symbol("def")] = Def.Fn.impl().lazy()

        symbols[Symbol("def-meta")] = Def.Meta.impl().lazy()

        symbols[Symbol("def-macro")] = Def.Macro.impl().lazy()

        symbols[Symbol("def-macro-fn")] = Def.MacroFn.impl().lazy()

        def("eq", 2) { args ->
            val lhs = args.receive()
            val rhs = args.receive()
            Bool(lhs.eq(rhs))
        }

        defNumeric("gt") { lhs, rhs ->
            Bool(lhs.inner > rhs.inner)
        }

        defNumeric("lt") { lhs, rhs ->
            Bool(lhs.inner < rhs.inner)
        }

        defNumeric("add") { lhs, rhs ->
            Rational(lhs.inner + rhs.inner)
        }

        defNumeric("sub") { lhs, rhs ->
            Rational(lhs.inner - rhs.inner)
        }

        defNumeric("mul") { lhs, rhs ->
            Rational(lhs.inner * rhs.inner)
        }

        defNumeric("div") { lhs, rhs ->
            Rational(lhs.inner / rhs.inner)
        }

        defNumeric("div-floor") { lhs, rhs ->
            Rational(lhs.inner.divideToIntegralValue(rhs.inner))
        }

        defNumeric("rem") { lhs, rhs ->
            Rational(lhs.inner % rhs.inner)
        }

        def("is-int", 1) { args ->
            Bool(args.receive().expect(Rational).inner.stripTrailingZeros().scale() >= 0)
        }

        def("and", unlimited) { args ->
            val failed = object : Throwable() {}
            try {
                args.consumeAsFlow().map {
                    async {
                        if (!it.expect(Bool).eq(Bool(true))) {
                            throw failed
                        }
                    }
                }.buffer().collect { it.await() }
                Bool(true)
            } catch (e: Throwable) {
                if (e === failed) {
                    Bool(false)
                } else {
                    throw e
                }
            }
        }

        def("not", 1) { args ->
            Bool(!args.receive().expect(Bool).inner)
        }

        def("@panic", 1) { args ->
            throw Panic(args.receive().expect(Str).collect())
        }

        def("tag-of", 1) { args ->
            args.receive().require().getTag()
        }

        def("tagged", 2) { args ->
            val tag = args.receive()
            val inner = args.receive()
            TaggedObject(tag, inner)
        }

        def("unique-tag", 0, 1) { args ->
            args.receiveCatching().onSuccess {
                return@def GeneratedTag(it.expect(Symbol).toString())
            }
            GeneratedTag()
        }

        def("unwrap", 1) { args ->
            when (val arg = args.receive().require()) {
                is TaggedObject -> arg.inner.require()
                else -> arg
            }
        }

        def("rational", Rational)

        def("bool", Bool)

        def("procedure", Procedure)

        def("pair", Pair)

        def("str", Str)

        def("io", IO)

        def("true", Bool(true))

        def("false", Bool(false))

        def("concat-str", Str.Companion.ConcatImpl)

        def("parse", 1) { args ->
            val str = args.receive().expect(Str)
            try {
                val tokenFlow = Token.tokenFlow(str.asCharFlow().withIndex().streamIn(this), null)
                val tokenChannel = tokenFlow.buffer().produceIn(this)
                val result = parseSingle(tokenChannel.asStream(), null).inner
                tokenChannel.receiveCatching().onSuccess {
                    throw Panic("Unexpected excessive token while parsing.", it.pos)
                }
                result
            } catch (e: ParseError) {
                throw Panic(e.message, e.pos)
            }
        }

        def("import", 1) { args ->
            Import(args.receive())
        }

        def("@println", IO { world ->
            world.println()
            Nil
        })

        def("@print-str", 1) { args ->
            IO { world ->
                world.print(args.receive().expect(Str))
                Nil
            }
        }
    }

    override val findSymbol = DeepRecursiveFunction {
        symbols[it] ?: throw undefinedSymbol(it)
    }
}

private enum class Def(val wrapper: Procedure) {
    Fn(Meta { ctx, args ->
        val (pat, form) = args.expect(Pair)
        FunctionLambda(pat, form, ctx)
    }),
    Meta(Meta { ctx, args ->
        val (pat, form) = args.expect(Pair)
        MetaLambda(pat, form, ctx)
    }),
    Macro(Meta { ctx, args ->
        val (pat, form) = args.expect(Pair)
        MacroLambda(pat, form, ctx)
    }),
    MacroFn(Meta { ctx, args ->
        val (pat, form) = args.expect(Pair)
        MacroFunctionLambda(pat, form, ctx)
    })
}

private fun Def.impl() = Meta { ctx, args ->
    var (referer, referee) = args.expect(Pair)
    loop {
        when (val requiredReferer = referer.require()) {
            is Symbol -> return@Meta Definition(requiredReferer, referee.evalLazy(ctx))
            is Pair -> {
                referer = requiredReferer.car
                val pat = requiredReferer.cdr
                referee = wrapper.applyMetaLazy(Pair(pat, referee).lazy(referee.pos), ctx)
            }
            else -> throw typeError(Symbol, requiredReferer)
        }
    }
}