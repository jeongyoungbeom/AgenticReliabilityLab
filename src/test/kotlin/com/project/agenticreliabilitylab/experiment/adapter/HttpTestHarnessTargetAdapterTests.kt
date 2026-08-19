package com.project.agenticreliabilitylab.experiment.adapter

import com.project.agenticreliabilitylab.experiment.domain.ExternalOperationOutcomeUnknownException
import com.project.agenticreliabilitylab.experiment.domain.TargetPreflightFailedException
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyParameters
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyScenarioProfile
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyTargetExecutionRequest
import com.project.agenticreliabilitylab.experiment.domain.TargetExperimentProfile
import com.project.agenticreliabilitylab.target.domain.IdentityVerificationStatus
import com.project.agenticreliabilitylab.target.domain.NetworkCidr
import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.target.domain.TargetCapability
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercises the Harness contract against a stand-in Harness.
 *
 * A real pilot Target is out of scope here: DESIGN2 section 15 requires its environment, invariant, fixture,
 * observation capability, cleanup criterion and credential reference to be agreed first, which belongs to the pilot
 * phase. What is verified here is the ARL side of the contract.
 */
@ActiveProfiles("test")
@SpringBootTest
class HttpTestHarnessTargetAdapterTests {
    @Autowired
    private lateinit var adapter: HttpTestHarnessTargetAdapter

    @Test
    fun `discovers a capability then maps the observations it reports`() {
        capabilitiesBody = declaration(maxStock = 100, maxConcurrency = 50)
        executionBody = executionResult(cleanupStatus = "VERIFIED")

        val result = adapter.executeStockConcurrency(executionRequest())

        assertEquals("COMPLETED", result.executionStatus)
        assertEquals(40, result.successCount)
        assertEquals(0, result.oversellCount)
        assertEquals(0, result.finalDbStock)
        assertTrue(result.cleanupVerified)
        assertEquals(1, result.resources.size)
        assertEquals("arl-harness-test", result.resources.first().namespace)
        assertContains(result.artifactReference, "stock-concurrency-v1")
    }

    @Test
    fun `refuses a run the profile does not allow even when the harness claims a larger bound`() {
        capabilitiesBody = declaration(maxStock = 999_999, maxConcurrency = 999_999)
        executionBody = executionResult(cleanupStatus = "VERIFIED")

        val failure = assertFailsWith<TargetPreflightFailedException> {
            adapter.executeStockConcurrency(executionRequest(stock = 500, concurrency = 400))
        }

        assertContains(failure.message.orEmpty(), "exceeds the effective Test Harness bound")
    }

    @Test
    fun `refuses to run when cleanup verification is not promised`() {
        capabilitiesBody = declaration(maxStock = 100, maxConcurrency = 50, cleanupVerification = "OPTIONAL")

        val failure = assertFailsWith<TargetPreflightFailedException> {
            adapter.executeStockConcurrency(executionRequest())
        }

        assertContains(failure.message.orEmpty(), "must verify cleanup")
    }

    @Test
    fun `refuses a declaration that does not identify exactly one capability for the type`() {
        capabilitiesBody = """{"contractVersion":"TEST_HARNESS_V1","capabilities":[]}"""

        val failure = assertFailsWith<TargetPreflightFailedException> {
            adapter.executeStockConcurrency(executionRequest())
        }

        assertContains(failure.message.orEmpty(), "exactly one")
    }

    @Test
    fun `refuses a declaration published under an unknown contract version`() {
        capabilitiesBody = """{"contractVersion":"SOMETHING_ELSE","capabilities":[]}"""

        val failure = assertFailsWith<TargetPreflightFailedException> {
            adapter.executeStockConcurrency(executionRequest())
        }

        assertContains(failure.message.orEmpty(), "contractVersion")
    }

    @Test
    fun `refuses a profile that selects the harness without publishing a capabilities endpoint`() {
        val request = executionRequest()
        val withoutEndpoint = request.copy(
            profile = request.profile.copy(
                stockConcurrency = request.profile.stockConcurrency.copy(capabilitiesEndpoint = null),
            ),
        )

        val failure = assertFailsWith<TargetPreflightFailedException> {
            adapter.executeStockConcurrency(withoutEndpoint)
        }

        assertContains(failure.message.orEmpty(), "no capabilities endpoint")
    }

    @Test
    fun `treats a non-terminal harness answer as an undetermined outcome`() {
        capabilitiesBody = declaration(maxStock = 100, maxConcurrency = 50)
        executionBody = """{"status":"RUNNING","operationId":"op-1"}"""

        assertFailsWith<ExternalOperationOutcomeUnknownException> {
            adapter.executeStockConcurrency(executionRequest())
        }
    }

    private fun executionRequest(
        stock: Int = 20,
        concurrency: Int = 10,
    ): StockConcurrencyTargetExecutionRequest = StockConcurrencyTargetExecutionRequest(
        target = target(),
        profile = profile(),
        runId = UUID.randomUUID(),
        actionId = "harness-action-1",
        parameters = StockConcurrencyParameters(
            stock = stock,
            requestCount = 40,
            concurrency = concurrency,
            quantityPerRequest = 1,
        ),
    )

    private fun profile(): TargetExperimentProfile = TargetExperimentProfile(
        targetSystemId = "harness-test-target",
        adapterId = "TEST_HARNESS_V1",
        executionEnabled = true,
        hostResourceGroup = "harness-test-resource",
        stockConcurrency = StockConcurrencyScenarioProfile(
            endpoint = EXECUTION_PATH,
            capabilitiesEndpoint = CAPABILITIES_PATH,
            maxStock = 100,
            maxRequestCount = 1_000,
            maxConcurrency = 50,
            maxQuantityPerRequest = 5,
            executionTimeout = Duration.ofSeconds(5),
        ),
    )

    private fun target(): RegisteredTarget {
        val origin = URI("http://127.0.0.1:${harness.address.port}")
        return RegisteredTarget(
            id = "harness-test-target",
            name = "Harness Test Target",
            adapterType = "HTTP_TARGET",
            environment = TargetEnvironment.TEST,
            baseUri = origin,
            allowedOrigin = origin,
            allowedNetworkCidrs = linkedSetOf(NetworkCidr.parse("127.0.0.0/8")),
            healthPath = "/actuator/health",
            sourceRepository = "harness-test-target",
            identityVerification = IdentityVerificationStatus.CONFIGURATION_ONLY,
            capabilities = setOf(TargetCapability.HEALTH, TargetCapability.STOCK_CONCURRENCY),
            enabled = true,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
    }

    private companion object {
        const val CAPABILITIES_PATH = "/harness/v1/capabilities"
        const val EXECUTION_PATH = "/harness/v1/executions"

        var capabilitiesBody: String = ""
        var executionBody: String = ""

        val harness: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext(CAPABILITIES_PATH) { exchange -> exchange.respond(capabilitiesBody) }
            createContext(EXECUTION_PATH) { exchange -> exchange.respond(executionBody) }
            executor = null
            start()
        }

        fun declaration(
            maxStock: Int,
            maxConcurrency: Int,
            cleanupVerification: String = "REQUIRED",
        ): String = """
            {
              "contractVersion":"TEST_HARNESS_V1",
              "capabilities":[
                {
                  "id":"stock-concurrency-v1",
                  "version":"1",
                  "experimentType":"STOCK_CONCURRENCY",
                  "environments":["LOCAL","TEST"],
                  "testNamespace":"arl-harness-test",
                  "limits":{
                    "maxStock":$maxStock,
                    "maxRequestCount":1000,
                    "maxConcurrency":$maxConcurrency,
                    "maxQuantityPerRequest":5
                  },
                  "invariantIds":["stock-non-negative"],
                  "cleanupVerification":"$cleanupVerification"
                }
              ]
            }
        """.trimIndent()

        fun executionResult(cleanupStatus: String): String = """
            {
              "status":"COMPLETED",
              "operationId":"harness-op-1",
              "message":"done",
              "fixture":{"productId":"test-product-1"},
              "observations":{
                "successCount":40,
                "failureCount":0,
                "oversellCount":0,
                "finalRedisStock":0,
                "finalDbStock":0,
                "durationSeconds":2
              },
              "resources":[{"type":"product","id":"test-product-1","namespace":"arl-harness-test"}],
              "cleanup":{"status":"$cleanupStatus"}
            }
        """.trimIndent()

        private fun HttpExchange.respond(body: String) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(200, bytes.size.toLong())
            responseBody.use { stream -> stream.write(bytes) }
        }

        @JvmStatic
        @AfterAll
        fun stopHarness() {
            harness.stop(0)
        }
    }
}
