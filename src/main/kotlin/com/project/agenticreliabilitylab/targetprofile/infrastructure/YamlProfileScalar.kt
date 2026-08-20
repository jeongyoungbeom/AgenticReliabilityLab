package com.project.agenticreliabilitylab.targetprofile.infrastructure

import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDocumentException
import java.time.Duration

internal fun Map<String, Any?>.requiredStringList(key: String): List<String> =
    (get(key) as? List<*>)?.mapIndexed { index, value ->
        value as? String ?: throw TargetProfileDocumentException("'$key[$index]' must be a string")
    } ?: throw TargetProfileDocumentException("'$key' must be an array")

internal fun Map<String, Any?>.optionalStringList(key: String): List<String>? =
    optionalList(key)?.mapIndexed { index, value ->
        value as? String ?: throw TargetProfileDocumentException("'$key[$index]' must be a string")
    }

internal fun Map<String, Any?>.optionalIntList(key: String): List<Int>? =
    optionalList(key)?.mapIndexed { index, value -> value.yamlInt("$key[$index]") }

internal fun Map<String, Any?>.optionalInt(key: String): Int? = get(key)?.yamlInt(key)

internal fun Any?.yamlInt(label: String): Int = when (this) {
    is Int -> this
    is Long -> toInt().takeIf { it.toLong() == this }
        ?: throw TargetProfileDocumentException("'$label' must fit in an integer")
    else -> throw TargetProfileDocumentException("'$label' must be an integer")
}

internal fun Map<String, Any?>.optionalBoolean(key: String): Boolean? = when (val value = get(key)) {
    null -> null
    is Boolean -> value
    else -> throw TargetProfileDocumentException("'$key' must be a boolean")
}

internal fun Map<String, Any?>.optionalDuration(key: String): Duration? =
    optionalString(key)?.yamlDuration(key)

internal fun String.yamlDuration(label: String): Duration = try {
    when {
        matches(Regex("[0-9]+ms")) -> Duration.ofMillis(dropLast(2).toLong())
        matches(Regex("[0-9]+s")) -> Duration.ofSeconds(dropLast(1).toLong())
        matches(Regex("[0-9]+m")) -> Duration.ofMinutes(dropLast(1).toLong())
        else -> Duration.parse(this)
    }
} catch (exception: IllegalArgumentException) {
    throw TargetProfileDocumentException("'$label' must be a duration", exception)
}

internal inline fun <reified T : Enum<T>> Map<String, Any?>.enumValue(key: String): T = try {
    enumValueOf(requiredString(key))
} catch (exception: IllegalArgumentException) {
    throw TargetProfileDocumentException("'$key' has an unsupported value", exception)
}
