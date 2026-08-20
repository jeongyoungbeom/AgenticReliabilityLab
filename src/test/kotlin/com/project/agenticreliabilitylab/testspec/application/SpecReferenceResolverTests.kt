package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.CleanupTiming
import com.project.agenticreliabilitylab.testspec.domain.ExecutionPolicy
import com.project.agenticreliabilitylab.testspec.domain.SetupStep
import com.project.agenticreliabilitylab.testspec.domain.SpecCategory
import com.project.agenticreliabilitylab.testspec.domain.SpecExecutionException
import com.project.agenticreliabilitylab.testspec.domain.SpecHttpCall
import com.project.agenticreliabilitylab.testspec.domain.SpecRisk
import com.project.agenticreliabilitylab.testspec.domain.SpecSource
import com.project.agenticreliabilitylab.testspec.domain.TestSpecification
import com.project.agenticreliabilitylab.testspec.domain.TrialAggregation
import com.project.agenticreliabilitylab.testspec.domain.TrialStopPolicy
import com.project.agenticreliabilitylab.testspec.domain.WorkloadStep
import com.project.agenticreliabilitylab.testspec.domain.WorkloadStepKind
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpecReferenceResolverTests {
    private val resolver = SpecReferenceResolver(ObjectMapper())

    @Test
    fun `substitutes every reference it is given`() {
        val text = """{"items":[{"productId":"{{setup.product.productId}}","quantity":1}]}"""
        val resolved = resolver.resolve(text, mapOf("setup.product.productId" to "p-77"))

        assertEquals("""{"items":[{"productId":"p-77","quantity":1}]}""", resolved)
    }

    @Test
    fun `refuses to send a request with an unresolved reference`() {
        val failure = assertFailsWith<SpecExecutionException> {
            resolver.resolve("/products/{{setup.product.productId}}", emptyMap())
        }

        assertTrue(failure.message!!.contains("setup.product.productId"))
    }

    @Test
    fun `reports which references a text cannot fill`() {
        val unresolved = resolver.unresolved(
            "{{runId}}-{{trialNumber}}-{{requestNumber}}",
            mapOf("runId" to "r-1"),
        )

        assertEquals(listOf("trialNumber", "requestNumber"), unresolved)
    }

    @Test
    fun `knows workload settings and setup literals before anything runs`() {
        val bindings = resolver.staticBindings(specification())

        assertEquals("10", bindings["workload.orders.requestCount"])
        assertEquals("10", bindings["workload.orders.concurrency"])
        assertEquals("10", bindings["setup.product.stock"])
        assertEquals("20", bindings["policy.trials"])
    }

    @Test
    fun `leaves out setup values that are only known while the run happens`() {
        val bindings = resolver.staticBindings(specification())

        assertTrue("setup.product.name" !in bindings)
    }

    @Test
    fun `flattens only the scalar fields of a body`() {
        val fields = resolver.bodyFields(
            "setup.product",
            """{"name":"arl","stock":10,"tags":["a"],"seller":{"id":1},"note":null}""",
        )

        assertEquals(mapOf("setup.product.name" to "arl", "setup.product.stock" to "10"), fields)
    }

    private fun specification() = TestSpecification(
        id = UUID.randomUUID(),
        specKey = "stock-oversell-concurrent",
        version = 1,
        title = "Concurrent orders oversell stock",
        category = SpecCategory.CONCURRENCY,
        risk = SpecRisk.MODERATE,
        source = SpecSource.RULE_GENERATED,
        targetSystemId = "sideproject",
        profileVersionId = UUID.randomUUID(),
        evidence = emptyList(),
        setup = listOf(
            SetupStep(
                name = "product",
                call = SpecHttpCall(
                    method = "POST",
                    path = "/products",
                    authProfile = "seller",
                    headers = emptyMap(),
                    bodyJson = """{"name":"arl-{{runId}}","stock":10,"price":1000}""",
                ),
                captures = mapOf("productId" to "response.body.id"),
            ),
        ),
        workload = listOf(
            WorkloadStep(
                kind = WorkloadStepKind.CALL,
                name = "orders",
                call = SpecHttpCall("POST", "/orders", "buyer", emptyMap(), """{"quantity":1}"""),
                requestCount = 10,
                concurrency = 10,
                captureAs = "responses",
            ),
        ),
        observations = emptyList(),
        invariants = emptyList(),
        policy = ExecutionPolicy(
            trials = 20,
            aggregation = TrialAggregation.ANY_VIOLATION_FAILS,
            stopPolicy = TrialStopPolicy.STOP_ON_FIRST_VIOLATION,
            cleanupTiming = CleanupTiming.AFTER_ALL,
            trialInterval = Duration.ZERO,
        ),
        cleanup = CleanupMethod.ENVIRONMENT_RESET,
    )
}
