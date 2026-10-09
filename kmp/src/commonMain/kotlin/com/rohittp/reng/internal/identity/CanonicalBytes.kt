package com.rohittp.reng.internal.identity

internal class CanonicalBytes private constructor(bytes: ByteArray, adopt: Boolean) {
    constructor(bytes: ByteArray) : this(bytes, adopt = false)

    private val snapshot: ByteArray = if (adopt) bytes else bytes.copyOf()

    internal companion object {
        /**
         * Wraps an array nothing will ever write again, without the copy the constructor takes.
         *
         * The caller promises one of two things about [unwritten]: it is an array the caller has just
         * allocated and filled, and drops on return -- every encoder in [CanonicalBinary] -- or it is the
         * private snapshot of another immutable holder, such as `StoredRawResource.byteSnapshot`, read
         * only to be digested. Either way no reference that could write to it survives, so the copy
         * defended nothing, and the bytes, equality and hash are those the copying constructor gives.
         *
         * **Why.** Each level of a canonical encoding allocated its output, filled it, and then copied it
         * again in this constructor; a frame plan is nested several levels deep and carries its
         * geometries' vertices, so it was copied that many extra times on every `prepare`. Hashing a
         * stored resource copied the whole tile or DEM to read it once. Simpleperf on an OPPO CPH2629
         * exporting a 28 s 720p24 project put `CanonicalFieldWriter.encode` and `CanonicalBinary.list`
         * among the memcpy-heavy frames, with 8.3% of the app's CPU in the garbage collector.
         */
        internal fun adopting(unwritten: ByteArray): CanonicalBytes = CanonicalBytes(unwritten, adopt = true)
    }

    internal val bytes: ByteArray
        get() = snapshot.copyOf()

    /**
     * The construction snapshot, read without a further copy by RenG-internal code only — the same
     * accessor `StoredRawResource.byteSnapshot` documents, and for the same reason.
     *
     * This array is never handed to a caller, never mutated, and never retained past the reading
     * call; [bytes] remains the only externally visible read and still returns a fresh copy.
     *
     * It exists because a platform digest runs at gigabytes a second (ADR 0058), and copying the
     * input twice to reach it would have been most of the work.
     */
    internal val byteSnapshot: ByteArray
        get() = snapshot

    internal val size: Int
        get() = snapshot.size

    internal fun byteAt(index: Int): Byte = snapshot[index]

    internal fun copyInto(destination: ByteArray, destinationOffset: Int) {
        snapshot.copyInto(destination, destinationOffset)
    }

    internal fun copyRangeInto(
        destination: ByteArray,
        destinationOffset: Int,
        startIndex: Int,
        endIndex: Int,
    ) {
        snapshot.copyInto(destination, destinationOffset, startIndex, endIndex)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is CanonicalBytes && snapshot.contentEquals(other.snapshot))

    override fun hashCode(): Int = snapshot.contentHashCode()

    override fun toString(): String = "CanonicalBytes(<redacted>)"
}

internal class Sha256Digest private constructor(bytes: ByteArray, adopt: Boolean) {
    internal constructor(bytes: ByteArray) : this(bytes, adopt = false)

    private val snapshot: ByteArray = if (adopt) bytes else bytes.copyOf()

    init {
        require(snapshot.size == SHA256_DIGEST_BYTES) { "SHA-256 digest must contain 32 bytes" }
    }

    internal companion object {
        /**
         * Wraps a digest array a hash function has just returned and nothing else holds, without the
         * copy the constructor takes -- [CanonicalBytes.adopting]'s contract, for the 32 bytes a
         * `MessageDigest` or [PureKotlinSha256] hands back. Every frame plan and every external
         * reference is hashed on every `prepare`, so the copy was paid tens of times a frame for
         * nothing.
         */
        internal fun adopting(fresh: ByteArray): Sha256Digest = Sha256Digest(fresh, adopt = true)
    }

    internal val bytes: ByteArray
        get() = snapshot.copyOf()

    /**
     * Built on first read: a resource key needs it for its stable id, but a frame identity is only
     * compared and registered by its bytes, and was spelling out 64 characters on every `prepare` for
     * no reader.
     */
    internal val lowercaseHex: String by lazy {
        buildString(SHA256_DIGEST_BYTES * 2) {
            snapshot.forEach { byte ->
                append(LOWERCASE_HEX[(byte.toInt() ushr 4) and 0x0f])
                append(LOWERCASE_HEX[byte.toInt() and 0x0f])
            }
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
