package com.project.agenticreliabilitylab.testspec.infrastructure

import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.target.domain.TargetReadTransport
import com.project.agenticreliabilitylab.testspec.application.DeclaredObservationSource
import com.project.agenticreliabilitylab.testspec.application.DeclaredObservationSourceKind
import com.project.agenticreliabilitylab.testspec.application.SpecRequestPolicy
import com.project.agenticreliabilitylab.testspec.application.port.DeclaredObservationRead
import com.project.agenticreliabilitylab.testspec.application.port.DeclaredObservationRequest
import com.project.agenticreliabilitylab.testspec.application.port.DeclaredObservationSourceClient
import com.project.agenticreliabilitylab.testspec.application.port.SpecAuthProvider
import com.project.agenticreliabilitylab.testspec.application.port.SpecExecutionSettings
import com.project.agenticreliabilitylab.testspec.domain.ObservationWindow
import com.project.agenticreliabilitylab.testspec.domain.TraceReadLimits
import com.project.agenticreliabilitylab.testspec.domain.TraceScope
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration

/** HTTP reader for the declared observation contracts. It never decides whether an invariant passed. */
@Component
@Suppress("TooManyFunctions") // Contract parsing stays explicit at this external observation trust boundary.
class HttpDeclaredObservationSourceClient(
    private val transport: TargetReadTransport,
    private val authProvider: SpecAuthProvider,
    private val settings: SpecExecutionSettings,
    private val objectMapper: ObjectMapper,
    private val spanParser: TempoSpanParser,
) : DeclaredObservationSourceClient {
    @Suppress("TooGenericExceptionCaught") // Every external read failure is localized to the requested fields.
    override fun read(request: DeclaredObservationRequest): Map<String, DeclaredObservationRead> {
        val fields = request.fields
        if (fields.isEmpty()) return emptyMap()
        val source = request.source
        return when (source.kind) {
            DeclaredObservationSourceKind.HARNESS_STATE -> try {
                readHarnessState(request.target, source, fields, request.runId, request.timeout)
            } catch (exception: Exception) {
                missingAll(fields, failureMessage(source, exception))
            }
            DeclaredObservationSourceKind.PROMETHEUS -> readPrometheusFields(
                request.target, source, fields, request.runId, request.timeout,
            )
            DeclaredObservationSourceKind.TRACE -> readTraceFields(request)
        }
    }

    /**
     * Reads one trace query per field.
     *
     * An empty result is a real answer, not a failure: a workload that produced no matching span genuinely
     * produced none. Whether that silence is acceptable is a judging question, and the time-axis functions refuse
     * to judge an empty timeline rather than calling it a pass.
     */
    @Suppress("TooGenericExceptionCaught") // One failed trace query must not hide the other requested fields.
    private fun readTraceFields(request: DeclaredObservationRequest): Map<String, DeclaredObservationRead> {
        val window = request.window
            ?: return missingAll(request.fields, "Trace source '${request.source.name}' has no observation window")
        val deadline = System.nanoTime() + request.timeout.toNanos()
        return request.fields.associateWith { field ->
            val remaining = remaining(deadline)
            if (remaining == null) {
                DeclaredObservationRead.missing(
                    "Trace source '${request.source.name}' exceeded the observation deadline",
                )
            } else {
                try {
                    readTrace(request, field, window, remaining)
                } catch (exception: Exception) {
                    DeclaredObservationRead.missing(failureMessage(request.source, exception))
                }
            }
        }
    }

    @Suppress("ReturnCount") // Trace contract defects remain distinct missing-observation reasons.
    private fun readTrace(
        request: DeclaredObservationRequest,
        field: String,
        window: ObservationWindow,
        timeout: Duration,
    ): DeclaredObservationRead {
        val source = request.source
        val declared = source.queries[field]
            ?: return DeclaredObservationRead.missing("Trace source '${source.name}' has no query for '$field'")
        if (!TraceScope.isQuotable(request.trialScope)) {
            return DeclaredObservationRead.missing("Trace source '${source.name}' cannot be scoped to this trial")
        }
        // The Profile owns the query; the engine only fills the scope it alone knows. A specification never
        // reaches this string, so this substitution cannot widen what the Profile allowed.
        val query = declared.replace(TraceScope.PLACEHOLDER, request.trialScope)
        val uri = URI(
            "${source.endpoint.trimEnd('/')}$TRACE_SEARCH_PATH" +
                "?q=${URLEncoder.encode(query, StandardCharsets.UTF_8)}" +
                "&start=${window.start.epochSecond}" +
                "&end=${window.end.epochSecond + 1}" +
                "&limit=${TRACE_LIMITS.maxTraces}" +
                "&spss=${TRACE_LIMITS.maxSpansPerSet}",
        )
        val sourceTarget = request.target.copy(baseUri = URI(source.endpoint), allowedOrigin = URI(origin(uri)))
        val response = get(sourceTarget, uri, source, request.runId, timeout)
        if (response.statusCode !in SUCCESS_STATUS) {
            return DeclaredObservationRead.missing(
                "Trace source '${source.name}' returned HTTP ${response.statusCode}",
            )
        }
        val root = readObject(response.body)
            ?: return DeclaredObservationRead.missing("Trace source '${source.name}' returned unreadable JSON")
        return spanParser.parse(source.name, field, root, window, TRACE_LIMITS)
    }

    @Suppress("ReturnCount") // Each malformed contract field is localized with its own operator-facing reason.
    private fun readHarnessState(
        target: RegisteredTarget,
        source: DeclaredObservationSource,
        requestedFields: Set<String>,
        runId: String,
        timeout: Duration,
    ): Map<String, DeclaredObservationRead> {
        val uri = target.baseUri.resolve(source.endpoint)
        if (origin(uri) != origin(target.allowedOrigin)) {
            return missingAll(
                requestedFields,
                "HARNESS_STATE source '${source.name}' escaped the Target origin",
            )
        }
        val response = get(target, uri, source, runId, timeout)
        if (response.statusCode !in SUCCESS_STATUS) {
            return missingAll(
                requestedFields,
                "HARNESS_STATE source '${source.name}' returned HTTP ${response.statusCode}",
            )
        }
        val root = readObject(response.body)
            ?: return missingAll(
                requestedFields,
                "HARNESS_STATE source '${source.name}' returned unreadable JSON",
            )
        if (root[CONTRACT_VERSION] != HARNESS_STATE_CONTRACT) {
            return missingAll(
                requestedFields,
                "HARNESS_STATE source '${source.name}' did not negotiate $HARNESS_STATE_CONTRACT",
            )
        }
        val rawFields = root[FIELDS] as? List<*>
            ?: return missingAll(
                requestedFields,
                "HARNESS_STATE source '${source.name}' returned no fields array",
            )
        if (rawFields.any { field -> field !is String }) {
            return missingAll(
                requestedFields,
                "HARNESS_STATE source '${source.name}' returned a malformed fields array",
            )
        }
        val providedFields = rawFields.filterIsInstance<String>().toSet()
        val state = root[STATE] as? Map<*, *>
            ?: return missingAll(
                requestedFields,
                "HARNESS_STATE source '${source.name}' returned no state object",
            )
        return requestedFields.associateWith { field -> harnessField(source, field, providedFields, state) }
    }

    @Suppress("TooGenericExceptionCaught") // One failed Prometheus query must not hide the other requested fields.
    private fun readPrometheusFields(
        target: RegisteredTarget,
        source: DeclaredObservationSource,
        fields: Set<String>,
        runId: String,
        timeout: Duration,
    ): Map<String, DeclaredObservationRead> {
        val deadline = System.nanoTime() + timeout.toNanos()
        return fields.associateWith { field ->
            val remaining = remaining(deadline)
            if (remaining == null) {
                DeclaredObservationRead.missing("Prometheus source '${source.name}' exceeded the observation deadline")
            } else {
                try {
                    readPrometheus(target, source, field, runId, remaining)
                } catch (exception: Exception) {
                    DeclaredObservationRead.missing(failureMessage(source, exception))
                }
            }
        }
    }

    @Suppress("ReturnCount") // Prometheus contract defects remain distinct missing-observation reasons.
    private fun readPrometheus(
        target: RegisteredTarget,
        source: DeclaredObservationSource,
        field: String,
        runId: String,
        timeout: Duration,
    ): DeclaredObservationRead {
        val query = source.queries[field]
            ?: return DeclaredObservationRead.missing("Prometheus source '${source.name}' has no query for '$field'")
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val uri = URI("${source.endpoint.trimEnd('/')}/api/v1/query?query=$encoded")
        val sourceTarget = target.copy(baseUri = URI(source.endpoint), allowedOrigin = URI(origin(uri)))
        val response = get(sourceTarget, uri, source, runId, timeout)
        if (response.statusCode !in SUCCESS_STATUS) {
            return DeclaredObservationRead.missing(
                "Prometheus source '${source.name}' returned HTTP ${response.statusCode}",
            )
        }
        val root = readObject(response.body)
            ?: return DeclaredObservationRead.missing("Prometheus source '${source.name}' returned unreadable JSON")
        if (root[STATUS] != SUCCESS) {
            return DeclaredObservationRead.missing("Prometheus source '${source.name}' did not return success")
        }
        val data = root[DATA] as? Map<*, *>
            ?: return DeclaredObservationRead.missing("Prometheus source '${source.name}' returned no data")
        return prometheusValue(source.name, data)
    }

    @Suppress("ReturnCount") // Result shapes fail independently so no missing metric is replaced with a default.
    private fun prometheusValue(sourceName: String, data: Map<*, *>): DeclaredObservationRead {
        val raw = when (data[RESULT_TYPE]) {
            SCALAR -> (data[RESULT] as? List<*>)?.getOrNull(VALUE_INDEX)
            VECTOR -> {
                val result = data[RESULT] as? List<*>
                if (result?.size != 1) {
                    return DeclaredObservationRead.missing(
                        "Prometheus source '$sourceName' must return exactly one series",
                    )
                }
                ((result.single() as? Map<*, *>)?.get(VALUE) as? List<*>)?.getOrNull(VALUE_INDEX)
            }
            else -> null
        }?.toString()
            ?: return DeclaredObservationRead.missing("Prometheus source '$sourceName' returned no scalar value")
        val number = raw.toBigDecimalOrNull()
            ?: return DeclaredObservationRead.missing("Prometheus source '$sourceName' returned a non-numeric value")
        val value = normalise(number)
            ?: return DeclaredObservationRead.missing("Prometheus source '$sourceName' returned a non-finite value")
        return DeclaredObservationRead.observed(value)
    }

    private fun get(
        target: RegisteredTarget,
        uri: URI,
        source: DeclaredObservationSource,
        runId: String,
        timeout: Duration,
    ) = transport.send(
        target = target,
        uri = uri,
        method = "GET",
        headers = headers(target, source, runId),
        body = ByteArray(0),
        timeout = minOf(timeout, settings.requestTimeout),
    )

    private fun headers(
        target: RegisteredTarget,
        source: DeclaredObservationSource,
        runId: String,
    ): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put(RUN_HEADER, runId)
        source.authProfile?.let { profile ->
            val authHeaders = authProvider.headersFor(target.id, profile)
            authHeaders.keys.forEach { name ->
                SpecRequestPolicy.authHeaderViolation(name)?.let { violation -> error(violation) }
            }
            putAll(authHeaders)
        }
    }

    @Suppress("UNCHECKED_CAST", "TooGenericExceptionCaught")
    private fun readObject(body: ByteArray): Map<String, Any?>? = try {
        objectMapper.readValue(String(body, StandardCharsets.UTF_8), Map::class.java) as? Map<String, Any?>
    } catch (_: Exception) {
        null
    }

    @Suppress("ReturnCount") // Capability absence, state absence and invalid scalar are different field failures.
    private fun harnessField(
        source: DeclaredObservationSource,
        field: String,
        providedFields: Set<String>,
        state: Map<*, *>,
    ): DeclaredObservationRead {
        if (field !in providedFields) {
            return DeclaredObservationRead.missing("HARNESS_STATE source '${source.name}' does not provide '$field'")
        }
        val value = state[field]
            ?: return DeclaredObservationRead.missing("HARNESS_STATE source '${source.name}' omitted '$field'")
        return scalar(value)?.let(DeclaredObservationRead::observed)
            ?: DeclaredObservationRead.missing("HARNESS_STATE field '$field' is not a finite scalar value")
    }

    private fun scalar(value: Any): Any? = when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Float -> value.toDouble().takeIf(Double::isFinite)
        is Double -> value.takeIf(Double::isFinite)
        is Long, is String, is Boolean -> value
        else -> null
    }

    private fun normalise(value: BigDecimal): Any? = try {
        value.stripTrailingZeros().longValueExact()
    } catch (_: ArithmeticException) {
        value.toDouble().takeIf(Double::isFinite)
    }

    private fun origin(uri: URI): String {
        val port = when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", true) -> HTTPS_PORT
            else -> HTTP_PORT
        }
        val host = uri.host.lowercase()
        val renderedHost = if (':' in host && !host.startsWith('[')) "[$host]" else host
        return "${uri.scheme.lowercase()}://$renderedHost:$port"
    }

    private fun remaining(deadline: Long): Duration? = (deadline - System.nanoTime())
        .takeIf { nanos -> nanos > 0 }
        ?.let(Duration::ofNanos)

    private fun missingAll(fields: Set<String>, reason: String): Map<String, DeclaredObservationRead> =
        fields.associateWith { DeclaredObservationRead.missing(reason) }

    private fun failureMessage(source: DeclaredObservationSource, exception: Exception): String =
        "${source.kind} source '${source.name}' could not be read: ${exception.javaClass.simpleName}"

    private companion object {
        const val HARNESS_STATE_CONTRACT = "HARNESS_STATE_V1"
        const val CONTRACT_VERSION = "contractVersion"
        const val FIELDS = "fields"
        const val STATE = "state"
        const val STATUS = "status"
        const val SUCCESS = "success"
        const val DATA = "data"
        const val RESULT_TYPE = "resultType"
        const val RESULT = "result"
        const val VALUE = "value"
        const val SCALAR = "scalar"
        const val VECTOR = "vector"
        const val VALUE_INDEX = 1
        const val RUN_HEADER = "X-ARL-Run-Id"
        const val HTTPS_PORT = 443
        const val HTTP_PORT = 80
        const val TRACE_SEARCH_PATH = "/api/search"

        /**
         * What one trace read may return before the answer is treated as truncated.
         *
         * `spss` is sent explicitly because Tempo's default is small enough to trim a trace's spans silently, and
         * a trimmed span set makes the earliest start of a trace unknowable - which is the value every time-axis
         * judgement is built on. Asking for a known number is what makes reaching it mean something.
         */
        val TRACE_LIMITS = TraceReadLimits(maxTraces = 200, maxSpansPerSet = 100, maxSpans = 1_000)
        val SUCCESS_STATUS = 200..299
    }
}
