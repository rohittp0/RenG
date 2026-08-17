package com.rohittp.reng.internal.identity

internal class CanonicalBytes(bytes: ByteArray) {
    private val snapshot: ByteArray = bytes.copyOf()

    internal val bytes: ByteArray
        get() = snapshot.copyOf()

    internal val size: Int
        get() = snapshot.size

    internal fun copyInto(destination: ByteArray, destinationOffset: Int) {
        snapshot.copyInto(destination, destinationOffset)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is CanonicalBytes && snapshot.contentEquals(other.snapshot))

    override fun hashCode(): Int = snapshot.contentHashCode()

    override fun toString(): String = "CanonicalBytes(<redacted>)"
}

internal class Sha256Digest internal constructor(bytes: ByteArray) {
    private val snapshot: ByteArray = bytes.copyOf()

    init {
        require(snapshot.size == SHA256_DIGEST_BYTES) { "SHA-256 digest must contain 32 bytes" }
    }

    internal val bytes: ByteArray
        get() = snapshot.copyOf()

    internal val lowercaseHex: String = buildString(SHA256_DIGEST_BYTES * 2) {
        snapshot.forEach { byte ->
            append(LOWERCASE_HEX[(byte.toInt() ushr 4) and 0x0f])
            append(LOWERCASE_HEX[byte.toInt() and 0x0f])
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Sha256Digest && snapshot.contentEquals(other.snapshot))

    override fun hashCode(): Int = snapshot.contentHashCode()

    override fun toString(): String = "Sha256Digest(<redacted>)"
}

internal fun interface Sha256Function {
    fun digest(bytes: CanonicalBytes): Sha256Digest
}

internal data class HashedCanonicalBytes(
    val digest: Sha256Digest,
    val canonicalBytes: CanonicalBytes,
)

private const val SHA256_DIGEST_BYTES: Int = 32
private const val LOWERCASE_HEX: String = "0123456789abcdef"
