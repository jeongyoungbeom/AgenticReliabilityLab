package com.project.agenticreliabilitylab.target.infrastructure

import com.project.agenticreliabilitylab.target.domain.IdentityVerificationStatus
import com.project.agenticreliabilitylab.target.domain.NetworkCidr
import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.target.domain.TargetCapability
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.target.domain.TargetSystemRepository
import com.project.agenticreliabilitylab.target.infrastructure.sql.TargetSystemSql
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.net.URI
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

@Repository
class JdbcTargetSystemRepository(
    private val jdbcClient: JdbcClient,
    private val dataSource: DataSource,
) : TargetSystemRepository {
    private val isPostgresDatabase = dataSource.connection.use { connection ->
        connection.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)
    }

    override fun findAll(): List<RegisteredTarget> =
        jdbcClient.sql(TargetSystemSql.FIND_ALL)
            .query { resultSet, _ -> resultSet.toTarget() }
            .list()

    override fun findById(id: String): RegisteredTarget? =
        jdbcClient.sql(TargetSystemSql.FIND_BY_ID)
            .param("id", id)
            .query { resultSet, _ -> resultSet.toTarget() }
            .optional()
            .orElse(null)

    override fun upsert(target: RegisteredTarget) {
        if (isPostgresDatabase) {
            jdbcClient.sql(TargetSystemSql.UPSERT_POSTGRES).params(target.parameters()).update()
            return
        }
        if (updateExisting(target) == 0) {
            try {
                jdbcClient.sql(TargetSystemSql.INSERT).params(target.parameters()).update()
            } catch (_: DuplicateKeyException) {
                updateExisting(target)
            }
        }
    }

    override fun lockForProfileActivation(id: String) {
        check(
            jdbcClient.sql(TargetSystemSql.LOCK_FOR_PROFILE_ACTIVATION)
                .param("id", id)
                .query { _, _ -> true }
                .optional()
                .orElse(false),
        ) { "Target system '$id' must exist before Target Profile activation" }
    }

    private fun updateExisting(target: RegisteredTarget): Int =
        jdbcClient.sql(TargetSystemSql.UPDATE).params(target.parameters()).update()

    private fun RegisteredTarget.parameters(): Map<String, Any> = mapOf(
        "id" to id,
        "name" to name,
        "adapterType" to adapterType,
        "environment" to environment.name,
        "baseUrl" to baseUri.toString(),
        "allowedOrigin" to allowedOrigin.toString(),
        "allowedCidrs" to allowedNetworkCidrs.map(NetworkCidr::toString).sorted().joinToString(","),
        "healthPath" to healthPath,
        "sourceRepository" to sourceRepository,
        "identityVerification" to identityVerification.name,
        "capabilities" to capabilities.map { it.name }.sorted().joinToString(","),
        "enabled" to enabled,
        "createdAt" to Timestamp.from(createdAt),
        "updatedAt" to Timestamp.from(updatedAt),
    )

    private fun ResultSet.toTarget(): RegisteredTarget = RegisteredTarget(
        id = getString("id"),
        name = getString("name"),
        adapterType = getString("adapter_type"),
        environment = TargetEnvironment.valueOf(getString("environment")),
        baseUri = URI(getString("base_url")),
        allowedOrigin = URI(getString("allowed_origin")),
        allowedNetworkCidrs = getString("allowed_cidrs").orEmpty()
            .split(',')
            .filter { it.isNotBlank() }
            .mapTo(linkedSetOf(), NetworkCidr::parse),
        healthPath = getString("health_path"),
        sourceRepository = getString("source_repository"),
        identityVerification = IdentityVerificationStatus.valueOf(getString("identity_verification")),
        capabilities = getString("capabilities")
            .split(',')
            .filter { it.isNotBlank() }
            .mapTo(linkedSetOf(), TargetCapability::valueOf),
        enabled = getBoolean("enabled"),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
    )

}
