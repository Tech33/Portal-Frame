package com.portalhacks.frame

/**
 * Pure Kotlin Apple Lossless Audio Codec (ALAC) frame decoder.
 *
 * Decodes compressed ALAC audio frames transmitted by Apple AirPlay 1 RTSP/RTP streams
 * into raw linear 16-bit stereo PCM audio suitable for direct playback via [android.media.AudioTrack].
 *
 * Based on the reference ALAC decoder by David Hammerton and Peter McQuillan (BSD License).
 */
class AlacDecoder(
    val frameLength: Int = 352,
    val bitDepth: Int = 16,
    val riceHistoryMult: Int = 40,
    val riceInitialHistory: Int = 10,
    val riceKModifier: Int = 14,
    val channels: Int = 2,
    val maxRun: Int = 255
) {
    private val setinfoRiceHistorymult: Int = riceHistoryMult and 0xFF
    private val setinfoRiceInitialhistory: Int = riceInitialHistory and 0xFF
    private val setinfoRiceKmodifier: Int = riceKModifier and 0xFF

    private val outputBuffer = IntArray(frameLength * channels)

    companion object {
        fun parseFmtp(fmtp: String): AlacDecoder {
            val parts = fmtp.trim().split(" ").filter { it.isNotEmpty() }
            if (parts.size >= 12) {
                try {
                    val frameLength = parts[1].toInt()
                    val bitDepth = parts[3].toInt()
                    val riceHistoryMult = parts[4].toInt()
                    val riceInitialHistory = parts[5].toInt()
                    val riceKModifier = parts[6].toInt()
                    val channels = parts[7].toInt()
                    val maxRun = parts[8].toInt()
                    return AlacDecoder(
                        frameLength = frameLength,
                        bitDepth = bitDepth,
                        riceHistoryMult = riceHistoryMult,
                        riceInitialHistory = riceInitialHistory,
                        riceKModifier = riceKModifier,
                        channels = channels,
                        maxRun = maxRun
                    )
                } catch (_: Exception) {}
            }
            return AlacDecoder()
        }
    }

    private class BitReader(private val bytes: ByteArray, offset: Int = 0) {
        private var byteIndex = offset
        private var bitBuffer = 0
        private var bitsRemaining = 0

        fun readBits(count: Int): Int {
            if (count == 0) return 0
            while (bitsRemaining < count) {
                val nextByte = if (byteIndex < bytes.size) bytes[byteIndex++].toInt() and 0xFF else 0
                bitBuffer = (bitBuffer shl 8) or nextByte
                bitsRemaining += 8
            }
            val shift = bitsRemaining - count
            val mask = (1 shl count) - 1
            val result = (bitBuffer ushr shift) and mask
            bitsRemaining = shift
            return result
        }
    }

    /**
     * Decodes one compressed ALAC frame into interleaved 16-bit little-endian PCM bytes.
     * Returns a ByteArray containing `frameLength * channels * 2` bytes.
     */
    @Synchronized
    fun decodeFrame(input: ByteArray, offset: Int, length: Int): ByteArray {
        val reader = BitReader(input, offset)
        val channels = this.channels

        // 3-bit channel configuration
        val channelsConfig = reader.readBits(3)
        // 4-bit unused/reserved
        reader.readBits(4)
        // 1-bit has_size
        val hasSize = reader.readBits(1)
        // 2-bit wasted bytes
        val wastedBytes = reader.readBits(2)
        // 1-bit is_not_compressed
        val isNotCompressed = reader.readBits(1)

        val outSamples = frameLength

        if (isNotCompressed == 1) {
            for (i in 0 until outSamples * channels) {
                outputBuffer[i] = reader.readBits(bitDepth)
            }
        } else {
            decodeCompressed(reader, outSamples)
        }

        val pcm = ByteArray(outSamples * channels * 2)
        var pcmIdx = 0
        for (i in 0 until outSamples * channels) {
            val sample = outputBuffer[i].toShort()
            pcm[pcmIdx++] = (sample.toInt() and 0xFF).toByte()
            pcm[pcmIdx++] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return pcm
    }

    private fun decodeCompressed(reader: BitReader, outSamples: Int) {
        val subframe0 = IntArray(outSamples)
        val subframe1 = IntArray(outSamples)

        val mode = reader.readBits(1)
        val interleaving = reader.readBits(1)

        decodeSubframe(reader, subframe0, outSamples)
        if (channels == 2) {
            decodeSubframe(reader, subframe1, outSamples)
        }

        if (channels == 2) {
            if (interleaving == 1) {
                for (i in 0 until outSamples) {
                    val mid = subframe0[i]
                    val side = subframe1[i]
                    outputBuffer[i * 2] = mid + (side shr 1)
                    outputBuffer[i * 2 + 1] = mid - ((side + 1) shr 1)
                }
            } else {
                for (i in 0 until outSamples) {
                    outputBuffer[i * 2] = subframe0[i]
                    outputBuffer[i * 2 + 1] = subframe1[i]
                }
            }
        } else {
            for (i in 0 until outSamples) {
                outputBuffer[i] = subframe0[i]
            }
        }
    }

    private fun decodeSubframe(reader: BitReader, out: IntArray, count: Int) {
        val predictionType = reader.readBits(4)
        val predictionQuant = reader.readBits(4)
        val ricemodifier = reader.readBits(3)
        val order = reader.readBits(5)

        val coeff = IntArray(order)
        for (i in 0 until order) {
            coeff[i] = reader.readBits(16).toShort().toInt()
        }

        var history = setinfoRiceInitialhistory
        val kModifier = setinfoRiceKmodifier
        val historyMult = setinfoRiceHistorymult

        for (i in 0 until count) {
            var k = 0
            var temp = history shr 9
            while (temp > 0 && k < 31) {
                k++
                temp = temp shr 1
            }

            var q = 0
            while (reader.readBits(1) == 0 && q < 1024) {
                q++
            }
            val rem = reader.readBits(k)
            var value = (q shl k) or rem

            value = if ((value and 1) != 0) {
                -(value shr 1) - 1
            } else {
                value shr 1
            }

            out[i] = value
            history += (Math.abs(value) - (history * historyMult shr 9))
        }

        if (order > 0) {
            for (i in order until count) {
                var sum = 0
                for (j in 0 until order) {
                    sum += coeff[j] * out[i - 1 - j]
                }
                out[i] += (sum shr predictionQuant)
            }
        }
    }
}
