package com.dpdpashield.sdk.core.tcf

/**
 * MSB-first bit accumulator for the IAB TCF v2 Core String binary layout
 * (see https://github.com/InteractiveAdvertisingBureau/GDPR-Transparency-and-Consent-Framework/
 * "TCF v2 In-App consent details"). Bits are appended as a String of '0'/'1'
 * characters rather than packed into a ByteArray as they arrive - the whole
 * Core String is well under 300 bits, so the readability of "the string IS
 * the bit sequence, in the order the spec lists the fields" is worth far
 * more here than the (immeasurable, at this size) cost of not packing bytes
 * incrementally. [pack] does the actual byte-packing once, at the end.
 */
class BitWriter {
    private val bits = StringBuilder()

    val bitCount: Int get() = bits.length

    fun writeBits(value: Long, numBits: Int): BitWriter {
        require(numBits in 1..63) { "numBits must be 1..63, got $numBits" }
        require(value >= 0) { "TCF fields are unsigned; got negative value $value" }
        require(value < (1L shl numBits)) { "value $value does not fit in $numBits bits" }
        for (i in numBits - 1 downTo 0) {
            bits.append(if ((value shr i) and 1L == 1L) '1' else '0')
        }
        return this
    }

    fun writeBoolean(value: Boolean): BitWriter = writeBits(if (value) 1L else 0L, 1)

    /** Encodes a single character as a 6-bit value: 'A'->0 .. 'Z'->25, per the
     *  spec's "6-bit N-bit AsciiString" language field encoding. */
    fun writeChar6(c: Char): BitWriter {
        val upper = c.uppercaseChar()
        require(upper in 'A'..'Z') { "TCF language/country chars must be A-Z, got '$c'" }
        return writeBits((upper - 'A').toLong(), 6)
    }

    /** Encodes a 2-letter code (language or country) as two 6-bit chars = 12 bits total. */
    fun writeChar6Pair(code: String): BitWriter {
        val normalised = code.trim().uppercase()
        require(normalised.length == 2) { "expected a 2-letter code, got '$code'" }
        writeChar6(normalised[0])
        writeChar6(normalised[1])
        return this
    }

    /** A [size]-bit field where bit i (0-indexed) means "item i+1 is set". */
    fun writeBitfield(size: Int, isSet: (itemNumber: Int) -> Boolean): BitWriter {
        for (i in 0 until size) writeBoolean(isSet(i + 1))
        return this
    }

    fun toBitString(): String = bits.toString()

    /** Packs the accumulated bits MSB-first into bytes, zero-padding the
     *  final byte on the right exactly as the spec requires. */
    fun pack(): ByteArray {
        val s = bits.toString()
        val byteCount = (s.length + 7) / 8
        val out = ByteArray(byteCount)
        for (i in s.indices) {
            if (s[i] == '1') {
                val byteIndex = i / 8
                val bitInByte = 7 - (i % 8)
                out[byteIndex] = (out[byteIndex].toInt() or (1 shl bitInByte)).toByte()
            }
        }
        return out
    }
}

/** Reads the same MSB-first bit layout back out, used only by [TcfCoreStringDecoder]
 *  for round-trip verification - no production code path needs to decode a
 *  TCF string, since the SDK is always the one producing it. */
class BitReader(private val bytes: ByteArray) {
    private var cursor = 0

    val bitsRemaining: Int get() = bytes.size * 8 - cursor

    fun readBits(numBits: Int): Long {
        require(numBits in 1..63)
        require(numBits <= bitsRemaining) { "requested $numBits bits, only $bitsRemaining remain" }
        var result = 0L
        repeat(numBits) {
            val byteIndex = cursor / 8
            val bitInByte = 7 - (cursor % 8)
            val bit = (bytes[byteIndex].toInt() shr bitInByte) and 1
            result = (result shl 1) or bit.toLong()
            cursor++
        }
        return result
    }

    fun readBoolean(): Boolean = readBits(1) == 1L

    fun readChar6(): Char = ('A' + readBits(6).toInt())

    fun readChar6Pair(): String = "${readChar6()}${readChar6()}"

    fun readBitfieldAsSet(size: Int): Set<Int> {
        val result = mutableSetOf<Int>()
        for (i in 0 until size) if (readBoolean()) result.add(i + 1)
        return result
    }
}

/** RFC 4648 base64url, no padding - written by hand rather than pulling in
 *  java.util.Base64 (API 26+ only; some Android minSdk targets are lower)
 *  or android.util.Base64 (would make this "pure JVM" module depend on the
 *  Android framework, which is the one thing this module exists to avoid). */
object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0

            sb.append(ALPHABET[(b0 shr 2) and 0x3F])
            sb.append(ALPHABET[((b0 shl 4) or (b1 shr 4)) and 0x3F])
            if (i + 1 < data.size) sb.append(ALPHABET[((b1 shl 2) or (b2 shr 6)) and 0x3F])
            if (i + 2 < data.size) sb.append(ALPHABET[b2 and 0x3F])
            i += 3
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = text.trim()
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bitsInBuffer = 0
        for (c in clean) {
            val value = ALPHABET.indexOf(c)
            require(value >= 0) { "invalid base64url character '$c'" }
            buffer = (buffer shl 6) or value
            bitsInBuffer += 6
            if (bitsInBuffer >= 8) {
                bitsInBuffer -= 8
                out.write((buffer shr bitsInBuffer) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
