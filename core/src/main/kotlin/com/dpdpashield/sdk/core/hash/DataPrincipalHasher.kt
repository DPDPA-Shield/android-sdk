package com.dpdpashield.sdk.core.hash

import java.security.MessageDigest

/**
 * Byte-exact Kotlin port of `hashDataPrincipal()` in apps/api/src/lib/hash.ts:
 *
 *     createHash('sha256').update(identifier.trim().toLowerCase()).digest('hex')
 *
 * This MUST stay byte-for-byte identical to the backend function. If it drifts,
 * a hash computed on-device will never match a hash computed server-side for
 * the same identifier, silently fragmenting one data principal's consent
 * history across two different `dataPrincipalHash` values - undetectable
 * without cross-referencing raw identifiers, which is exactly what hashing
 * exists to avoid needing.
 *
 * `String.trim()` in Kotlin trims Unicode whitespace (same class of characters
 * JS's `String.prototype.trim()` strips) and `.lowercase()` is locale-
 * independent (unlike `.toLowerCase()` with no explicit Locale, which is
 * locale-sensitive on the JVM and would mis-lower e.g. Turkish 'I' on a
 * device set to a Turkish locale) - use `.lowercase()`, never
 * `.toLowerCase()`, for exactly that reason.
 */
object DataPrincipalHasher {
    fun hash(identifier: String): String {
        val normalised = identifier.trim().lowercase()
        val digest = MessageDigest.getInstance("SHA-256").digest(normalised.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
