package com.project.agenticreliabilitylab.testspec.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These tests are about what an expression cannot do, not what it can.
 *
 * The specifications they guard are written by a model, so the environment's job is to make an unsafe or misspelled
 * expression fail at compile time rather than at run time - after the Target has already been changed.
 */
class SpecExpressionEnvironmentTests {
    private val environment = SpecExpressionEnvironment()

    @Test
    fun `evaluates a comparison against observed values`() {
        val compiled = environment.compile("dbStock >= 0", setOf("dbStock"))

        assertTrue(compiled.evaluateBoolean(mapOf("dbStock" to 3L)))
        assertFalse(compiled.evaluateBoolean(mapOf("dbStock" to -3L)))
    }

    @Test
    fun `evaluates arithmetic between two observations`() {
        val compiled = environment.compile(
            "dbStock == initialStock - successQuantity",
            setOf("dbStock", "initialStock", "successQuantity"),
        )

        assertTrue(compiled.evaluateBoolean(mapOf("dbStock" to 7L, "initialStock" to 10L, "successQuantity" to 3L)))
        assertFalse(compiled.evaluateBoolean(mapOf("dbStock" to 6L, "initialStock" to 10L, "successQuantity" to 3L)))
    }

    @Test
    fun `rejects an identifier the run will not provide`() {
        val failure = assertFailsWith<SpecExpressionException> {
            environment.compile("dbStock >= 0", setOf("redisHold"))
        }

        assertTrue(failure.message.orEmpty().contains("dbStock"))
    }

    @Test
    fun `rejects a macro so an approval screen stays readable`() {
        assertFailsWith<SpecExpressionException> {
            environment.compile("responses.all(r, r.status == 200)", setOf("responses"))
        }
    }

    @Test
    fun `rejects an expression that does not produce a boolean where one is required`() {
        val compiled = environment.compile("dbStock", setOf("dbStock"))

        assertFailsWith<SpecExpressionException> { compiled.evaluateBoolean(mapOf("dbStock" to 3L)) }
    }

    @Test
    fun `supports membership over a declared list`() {
        val compiled = environment.compile("orderStatus in ['FAILED', 'CANCELLED']", setOf("orderStatus"))

        assertTrue(compiled.evaluateBoolean(mapOf("orderStatus" to "CANCELLED")))
        assertFalse(compiled.evaluateBoolean(mapOf("orderStatus" to "PAID")))
    }

    @Test
    fun `evaluates a non-boolean expression for observation values`() {
        val compiled = environment.compile("initialStock - dbStock", setOf("initialStock", "dbStock"))

        assertEquals(3L, compiled.evaluate(mapOf("initialStock" to 10L, "dbStock" to 7L)))
    }
}
