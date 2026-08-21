package com.project.agenticreliabilitylab.testspec.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** A minimal stateful Target used only to prove the three Phase 17 specification shapes against one engine. */
@Suppress("TooManyFunctions") // Each HTTP operation stays explicit so the fixture contract is reviewable.
class Phase17FixtureTarget private constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
) {
    private val state = FixtureState()
    private val orderRequests = AtomicInteger()
    private val paymentRequests = AtomicInteger()
    private val transferRequests = AtomicInteger()
    private val harnessStateRequests = AtomicInteger()
    private val prometheusRequests = AtomicInteger()
    private val resetRequests = AtomicInteger()

    val origin: String = "http://127.0.0.1:${server.address.port}"

    fun reset() {
        state.resetEnvironment()
        orderRequests.set(0)
        paymentRequests.set(0)
        transferRequests.set(0)
        harnessStateRequests.set(0)
        prometheusRequests.set(0)
        resetRequests.set(0)
    }

    fun audit(): FixtureAudit = FixtureAudit(
        orderRequests = orderRequests.get(),
        paymentRequests = paymentRequests.get(),
        transferRequests = transferRequests.get(),
        harnessStateRequests = harnessStateRequests.get(),
        prometheusRequests = prometheusRequests.get(),
        resetRequests = resetRequests.get(),
    )

    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    @Suppress("TooGenericExceptionCaught") // A fixture failure must become an HTTP failure the Runner can record.
    private fun handle(exchange: HttpExchange) {
        try {
            exchange.requestBody.use { input -> input.readAllBytes() }
            when ("${exchange.requestMethod.uppercase()} ${exchange.requestURI.path}") {
                "POST /scenario/concurrency" -> configureConcurrency(exchange)
                "POST /scenario/idempotency" -> configureIdempotency(exchange)
                "POST /scenario/consistency" -> configureConsistency(exchange)
                "POST /orders" -> createOrder(exchange)
                "POST /payments" -> createPayment(exchange)
                "POST /transfers" -> createTransfer(exchange)
                "POST /reset" -> resetEnvironment(exchange)
                "GET /state" -> exchange.respond(200, state.json())
                "GET /harness/state" -> missingHarnessState(exchange)
                "GET /api/v1/query" -> prometheusQuery(exchange)
                else -> exchange.respond(404, """{"error":"not-found"}""")
            }
        } catch (_: Exception) {
            exchange.respond(500, """{"error":"fixture-failure"}""")
        }
    }

    private fun configureConcurrency(exchange: HttpExchange) {
        state.configureConcurrency()
        exchange.respond(204, "")
    }

    private fun configureIdempotency(exchange: HttpExchange) {
        state.configureIdempotency()
        exchange.respond(204, "")
    }

    private fun configureConsistency(exchange: HttpExchange) {
        state.configureConsistency()
        exchange.respond(204, "")
    }

    private fun createOrder(exchange: HttpExchange) {
        orderRequests.incrementAndGet()
        val accepted = state.createOrder()
        val status = if (accepted) 201 else 409
        val quantity = if (accepted) 1 else 0
        exchange.respond(status, """{"acceptedQuantity":$quantity}""")
    }

    private fun createPayment(exchange: HttpExchange) {
        paymentRequests.incrementAndGet()
        val key = exchange.requestHeaders.getFirst("Idempotency-Key").orEmpty()
        val created = state.createPayment(key)
        val status = if (created) 201 else 200
        val effect = if (created) 1 else 0
        exchange.respond(status, """{"createdEffect":$effect}""")
    }

    private fun createTransfer(exchange: HttpExchange) {
        transferRequests.incrementAndGet()
        state.createTransfer()
        exchange.respond(201, """{"transferred":30}""")
    }

    private fun resetEnvironment(exchange: HttpExchange) {
        resetRequests.incrementAndGet()
        state.resetEnvironment()
        exchange.respond(204, "")
    }

    private fun prometheusQuery(exchange: HttpExchange) {
        prometheusRequests.incrementAndGet()
        exchange.respond(
            200,
            """{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1,"1"]}]}}""",
        )
    }

    private fun missingHarnessState(exchange: HttpExchange) {
        harnessStateRequests.incrementAndGet()
        exchange.respond(404, """{"error":"not-found"}""")
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { output -> output.write(bytes) }
    }

    companion object {
        fun start(): Phase17FixtureTarget {
            val executor = Executors.newCachedThreadPool()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val fixture = Phase17FixtureTarget(server, executor)
            server.executor = executor
            server.createContext("/") { exchange -> fixture.handle(exchange) }
            server.start()
            return fixture
        }
    }

    private class FixtureState {
        private var clean = true
        private var stock = 0
        private var paymentCount = 0
        private var balanceA = 0
        private var balanceB = 0
        private var ledgerCount = 0
        private val paymentKeys = mutableSetOf<String>()

        @Synchronized
        fun configureConcurrency() {
            resetState()
            stock = 1
            clean = false
        }

        @Synchronized
        fun configureIdempotency() {
            resetState()
            clean = false
        }

        @Synchronized
        fun configureConsistency() {
            resetState()
            balanceA = 100
            balanceB = 100
            clean = false
        }

        @Synchronized
        fun createOrder(): Boolean {
            if (stock < 1) return false
            stock -= 1
            return true
        }

        @Synchronized
        fun createPayment(key: String): Boolean {
            if (!paymentKeys.add(key)) return false
            paymentCount += 1
            return true
        }

        @Synchronized
        fun createTransfer() {
            balanceA -= TRANSFER_AMOUNT
            balanceB += TRANSFER_AMOUNT
            ledgerCount += 1
        }

        @Synchronized
        fun resetEnvironment() {
            resetState()
        }

        @Synchronized
        fun json(): String = """
            {
              "clean":$clean,
              "stock":$stock,
              "paymentCount":$paymentCount,
              "balanceA":$balanceA,
              "balanceB":$balanceB,
              "totalBalance":${balanceA + balanceB},
              "ledgerCount":$ledgerCount
            }
        """.trimIndent()

        private fun resetState() {
            clean = true
            stock = 0
            paymentCount = 0
            balanceA = 0
            balanceB = 0
            ledgerCount = 0
            paymentKeys.clear()
        }

        private companion object {
            const val TRANSFER_AMOUNT = 30
        }
    }
}

data class FixtureAudit(
    val orderRequests: Int,
    val paymentRequests: Int,
    val transferRequests: Int,
    val resetRequests: Int,
    val harnessStateRequests: Int = 0,
    val prometheusRequests: Int = 0,
)
