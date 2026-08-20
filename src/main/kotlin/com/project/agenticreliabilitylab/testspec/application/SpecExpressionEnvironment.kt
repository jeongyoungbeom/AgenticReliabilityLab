package com.project.agenticreliabilitylab.testspec.application

import dev.cel.common.CelValidationException
import dev.cel.common.types.SimpleType
import dev.cel.compiler.CelCompilerFactory
import dev.cel.runtime.CelRuntime
import dev.cel.runtime.CelRuntimeFactory
import org.springframework.stereotype.Component

/**
 * Compiles and evaluates the expressions inside a specification.
 *
 * Expressions come from a model, so the property that matters is not "does it work" but "what can it do".
 * CEL is not Turing complete, so an expression cannot loop forever, and only the variables declared here exist at
 * all - an expression naming anything else fails to compile rather than failing after the Target was changed.
 *
 * Standard macros (`all`, `exists`, `map`, `filter`) are left off. CEL disables them unless they are enabled, and
 * they stay disabled here: they would let a model write conditions a reviewer cannot read on an approval screen,
 * and a human approval nobody can read is not an approval.
 */
@Component
class SpecExpressionEnvironment {
    /**
     * Compiles one expression against the identifiers a run will provide.
     *
     * Compiling during validation means a specification that misspells an observation is rejected before anything
     * is dispatched, rather than after the Target has already been changed.
     */
    fun compile(expression: String, identifiers: Set<String>): CompiledExpression {
        val builder = CelCompilerFactory.standardCelCompilerBuilder()
        identifiers.forEach { name -> builder.addVar(name, SimpleType.DYN) }
        return try {
            val ast = builder.build().compile(expression).ast
            CompiledExpression(expression, RUNTIME.createProgram(ast))
        } catch (exception: CelValidationException) {
            throw SpecExpressionException(
                "Expression '$expression' is not valid: ${exception.message}",
                exception,
            )
        }
    }

    private companion object {
        val RUNTIME: CelRuntime = CelRuntimeFactory.standardCelRuntimeBuilder().build()
    }
}

/** A compiled expression, ready to evaluate against one run's observed values. */
class CompiledExpression(
    private val expression: String,
    private val program: CelRuntime.Program,
) {
    /**
     * Evaluates against the values a run observed.
     *
     * A missing binding is not silently treated as null: the caller decides whether an unobserved value makes the
     * invariant unjudgeable, and that decision must not be hidden inside expression evaluation.
     */
    @Suppress("TooGenericExceptionCaught") // Any evaluation failure means the same thing: this cannot be judged.
    fun evaluate(bindings: Map<String, Any>): Any = try {
        program.eval(bindings)
    } catch (exception: Exception) {
        throw SpecExpressionException(
            "Expression '$expression' could not be evaluated: ${exception.javaClass.simpleName}",
            exception,
        )
    }

    fun evaluateBoolean(bindings: Map<String, Any>): Boolean {
        val result = evaluate(bindings)
        return result as? Boolean ?: throw SpecExpressionException(
            "Expression '$expression' must produce a boolean but produced ${result.javaClass.simpleName}",
        )
    }
}

class SpecExpressionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
