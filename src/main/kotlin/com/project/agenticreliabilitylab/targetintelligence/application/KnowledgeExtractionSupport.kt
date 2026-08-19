package com.project.agenticreliabilitylab.targetintelligence.application

import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeCitation
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeSourceType
import com.project.agenticreliabilitylab.targetintelligence.domain.OperationMutability
import com.project.agenticreliabilitylab.targetintelligence.domain.RiskSignal
import com.project.agenticreliabilitylab.targetintelligence.domain.RiskSignalType
import java.security.MessageDigest

/**
 * Deterministic vocabulary and bounding helpers shared by the Phase 11 extractors.
 *
 * Every value here is applied to untrusted document text. Text is only matched, counted and truncated; it is never
 * interpreted as an instruction and never used to reach the Target.
 */
/**
 * Identifies the deterministic extraction rules below.
 *
 * Bump this whenever a change would make the same documents yield different knowledge, so that resubmitting those
 * documents produces a new Snapshot instead of returning the previously extracted one.
 */
internal const val KNOWLEDGE_EXTRACTION_VERSION = "target-knowledge-v1"

internal const val MAX_EXCERPT_LENGTH = 200
internal const val MAX_STATEMENT_LENGTH = 500
internal const val MAX_TITLE_LENGTH = 200
internal const val MAX_OPERATIONS = 200
internal const val MAX_RISK_SIGNALS = 100
internal const val MAX_SIGNALS_PER_TYPE = 3
internal const val MAX_INVARIANTS = 100
internal const val MAX_DOMAIN_HYPOTHESES = 50
internal const val MAX_WORKFLOWS = 50
internal const val MAX_WORKFLOW_STEPS = 20

internal val READ_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE")
internal val WRITE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
internal val HTTP_METHODS = READ_METHODS + WRITE_METHODS

/** Risk keywords are matched case-insensitively against document text in both English and Korean. */
internal val RISK_KEYWORDS: Map<RiskSignalType, List<String>> = mapOf(
    RiskSignalType.RETRY to listOf("retry", "retries", "재시도", "backoff"),
    RiskSignalType.ASYNC to listOf("async", "asynchronous", "비동기", "eventual consistency", "결과적 일관성"),
    RiskSignalType.CACHE to listOf("cache", "caching", "캐시", "redis"),
    RiskSignalType.EVENT to listOf("event", "이벤트", "kafka", "outbox", "saga", "message queue", "메시지 큐"),
    RiskSignalType.SHARED_RESOURCE to listOf("shared", "공유 자원", "concurrent", "동시성", "race condition", "경쟁 상태"),
    RiskSignalType.IDEMPOTENCY_KEY to listOf("idempotency", "idempotent", "멱등"),
    RiskSignalType.LOCKING to listOf("lock", "락", "optimistic locking", "낙관적 락", "pessimistic", "비관적"),
    RiskSignalType.TRANSACTION to listOf("transaction", "트랜잭션", "rollback", "롤백", "commit"),
)

/** Domain concepts are inferred from naming only, so every match is recorded as an ASSUMPTION. */
internal val DOMAIN_CONCEPTS: Map<String, List<String>> = mapOf(
    "order" to listOf("order", "orders", "주문"),
    "stock" to listOf("stock", "inventory", "재고"),
    "payment" to listOf("payment", "payments", "pay", "결제"),
    "cart" to listOf("cart", "carts", "장바구니"),
    "refund" to listOf("refund", "refunds", "환불"),
    "shipping" to listOf("shipping", "delivery", "배송"),
    "settlement" to listOf("settlement", "정산"),
    "coupon" to listOf("coupon", "coupons", "쿠폰"),
    "point" to listOf("point", "points", "포인트"),
    "member" to listOf("user", "users", "member", "members", "회원"),
)

/** Phrases that mark a sentence as an explicit rule. A match is still an ARL inference, never a stated fact. */
internal val INVARIANT_MARKERS = listOf(
    "must not", "must", "never", "always", "at most once", "exactly once", "cannot",
    "반드시", "절대", "않아야", "않는다", "해야 한다", "한 번만", "중복", "음수",
)

internal fun String.toMutability(): OperationMutability = when (uppercase()) {
    in READ_METHODS -> OperationMutability.READ
    in WRITE_METHODS -> OperationMutability.WRITE
    else -> OperationMutability.UNKNOWN
}

internal fun String.toExcerpt(): String = replace(CONTROL_CHARACTERS, " ")
    .trim()
    .take(MAX_EXCERPT_LENGTH)

internal fun String.toStatement(): String = replace(CONTROL_CHARACTERS, " ")
    .trim()
    .take(MAX_STATEMENT_LENGTH)

internal fun String.toBoundedTitle(): String = replace(CONTROL_CHARACTERS, " ")
    .trim()
    .take(MAX_TITLE_LENGTH)

internal fun citation(
    sourceType: KnowledgeSourceType,
    location: String,
    excerpt: String,
): KnowledgeCitation = KnowledgeCitation(sourceType, location.take(MAX_TITLE_LENGTH), excerpt.toExcerpt())

/** Matches every configured risk keyword contained in already-lowercased text. */
internal fun matchedRiskTypes(lowercaseText: String): List<RiskSignalType> = RISK_KEYWORDS
    .filterValues { keywords -> keywords.any(lowercaseText::contains) }
    .keys
    .toList()

/** Matches every domain concept contained in already-lowercased text. */
internal fun matchedDomainConcepts(lowercaseText: String): List<String> = DOMAIN_CONCEPTS
    .filterValues { terms -> terms.any(lowercaseText::contains) }
    .keys
    .toList()

/**
 * Applies one bounding rule to risk signals from every source.
 *
 * A signal is kept per distinct citation location, because a location means something different in each source: an
 * OpenAPI location identifies the operation that carries the risk, while a README location identifies the line that
 * mentioned it. Capping per type then keeps a repetitive document from flooding the Snapshot while still leaving
 * several concrete pieces of evidence for the user to review.
 */
internal fun List<RiskSignal>.boundedRiskSignals(): List<RiskSignal> = distinctBy { signal ->
    signal.type to signal.citation.location
}
    .groupBy { signal -> signal.type }
    .values
    .flatMap { signals -> signals.take(MAX_SIGNALS_PER_TYPE) }
    .take(MAX_RISK_SIGNALS)

internal fun String.looksLikeInvariant(): Boolean {
    val lowercase = lowercase()
    return INVARIANT_MARKERS.any(lowercase::contains)
}

internal fun sha256Hex(value: String): String = MessageDigest.getInstance(SHA_256)
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private const val SHA_256 = "SHA-256"
private val CONTROL_CHARACTERS = Regex("[\\p{Cntrl}]")
