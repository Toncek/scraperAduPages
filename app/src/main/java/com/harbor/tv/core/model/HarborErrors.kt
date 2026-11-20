package com.harbor.tv.core.model

/**
 * Canonical Harbor error taxonomy (Master Specification Section 23.4).
 */
enum class HarborErrorCode {
    DEFINITION_INVALID,
    UNSUPPORTED_SCHEMA,
    UNSUPPORTED_CAPABILITY,
    NETWORK_UNAVAILABLE,
    RATE_LIMITED,
    DESTINATION_BLOCKED,
    RESPONSE_TOO_LARGE,
    SELECTOR_INVALID,
    REQUIRED_FIELD_MISSING,
    SOURCE_LAYOUT_CHANGED,
    AUTHENTICATION_REQUIRED,
    PLAYBACK_NOT_FOUND,
    STREAM_EXPIRED,
    FORMAT_UNSUPPORTED,
    CODEC_UNSUPPORTED,
    POLICY_BLOCKED,
    METADATA_INSUFFICIENT
}

/**
 * Base structured exception for Harbor engine, network, validation, and playback failures.
 */
open class HarborException(
    val code: HarborErrorCode,
    override val message: String,
    val sourceId: String? = null,
    val operation: String? = null,
    val isRetryable: Boolean = false,
    override val cause: Throwable? = null
) : Exception(message, cause)
