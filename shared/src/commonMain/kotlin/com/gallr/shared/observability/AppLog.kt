package com.gallr.shared.observability

import co.touchlab.kermit.Logger

/**
 * Application logging boundary.
 *
 * Operations and component names must be stable identifiers, never user or request data. Failure
 * logs intentionally retain only the exception type so URLs, tokens, and personal data contained in
 * exception messages cannot reach platform logs.
 */
object AppLog {
    fun tagged(component: String): TaggedAppLogger = TaggedAppLogger(Logger.withTag(component))
}

class TaggedAppLogger internal constructor(
    private val logger: Logger,
) {
    fun debug(
        operation: String,
        outcome: String = "skipped",
    ) {
        logger.d { structuredLogMessage(operation = operation, outcome = outcome) }
    }

    fun info(operation: String) {
        logger.i { structuredLogMessage(operation = operation, outcome = "received") }
    }

    fun warn(
        operation: String,
        cause: Throwable? = null,
    ) {
        logger.w { structuredLogMessage(operation = operation, outcome = "failure", cause = cause) }
    }

    fun error(
        operation: String,
        cause: Throwable? = null,
    ) {
        logger.e { structuredLogMessage(operation = operation, outcome = "failure", cause = cause) }
    }
}

internal fun structuredLogMessage(
    operation: String,
    outcome: String,
    cause: Throwable? = null,
): String =
    buildString {
        append("operation=")
        append(operation)
        append(" outcome=")
        append(outcome)
        cause?.let {
            append(" cause=")
            append(it::class.simpleName ?: "Throwable")
        }
    }
