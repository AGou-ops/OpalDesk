package io.github.lengsukq.opaldesk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.nio.charset.StandardCharsets

/**
 * Pure-Kotlin ISO/IEC 18004 QR Code encoder (Byte mode, ECC Level L, Versions 1–10)
 * with zero external dependencies.
 */
object QrCodeMatrix {
    data class Matrix(val version: Int, val size: Int, val modules: BooleanArray) {
        fun get(x: Int, y: Int): Boolean = modules[y * size + x]
    }

    private data class VersionSpec(
        val version: Int,
        val totalDataCodewords: Int,
        val ecPerBlock: Int,
        val group1Blocks: Int,
        val group1DataCw: Int,
        val group2Blocks: Int,
        val group2DataCw: Int,
        val alignCenters: IntArray,
    )

    // ISO/IEC 18004 Table 9 — Error Correction Level L (Versions 1..10)
    private val SPECS_L = arrayOf(
        VersionSpec(1, 19, 7, 1, 19, 0, 0, intArrayOf()),
        VersionSpec(2, 34, 10, 1, 34, 0, 0, intArrayOf(6, 18)),
        VersionSpec(3, 55, 15, 1, 55, 0, 0, intArrayOf(6, 22)),
        VersionSpec(4, 80, 20, 1, 80, 0, 0, intArrayOf(6, 26)),
        VersionSpec(5, 108, 26, 1, 108, 0, 0, intArrayOf(6, 30)),
        VersionSpec(6, 136, 18, 2, 68, 0, 0, intArrayOf(6, 34)),
        VersionSpec(7, 156, 20, 2, 78, 0, 0, intArrayOf(6, 22, 38)),
        VersionSpec(8, 194, 24, 2, 97, 0, 0, intArrayOf(6, 24, 42)),
        VersionSpec(9, 232, 30, 2, 116, 0, 0, intArrayOf(6, 26, 46)),
        VersionSpec(10, 274, 18, 2, 68, 2, 69, intArrayOf(6, 28, 50)),
    )

    fun encode(text: String): Matrix {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val spec = SPECS_L.firstOrNull { s ->
            val countBits = if (s.version <= 9) 8 else 16
            val neededBits = 4 + countBits + bytes.size * 8
            neededBits <= s.totalDataCodewords * 8
        } ?: throw IllegalArgumentException("QR payload too long (${bytes.size} bytes, max 271)")

        val dataCodewords = buildDataCodewords(bytes, spec)
        val finalStream = buildInterleavedCodewords(dataCodewords, spec)
        val size = spec.version * 4 + 17
        val isFunc = BooleanArray(size * size)
        val baseModules = BooleanArray(size * size)

        drawFunctionPatterns(baseModules, isFunc, spec, size)
        placeCodewords(baseModules, isFunc, size, finalStream)

        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        var bestModules = baseModules
        for (mask in 0..7) {
            val candidate = baseModules.copyOf()
            applyMask(candidate, isFunc, size, mask)
            drawFormatBits(candidate, size, mask)
            val penalty = computePenalty(candidate, size)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
                bestModules = candidate
            }
        }
        // Ensure format bits match chosen bestMask (already drawn on bestModules)
        drawFormatBits(bestModules, size, bestMask)
        return Matrix(spec.version, size, bestModules)
    }

    private fun buildDataCodewords(bytes: ByteArray, spec: VersionSpec): ByteArray {
        val totalBits = spec.totalDataCodewords * 8
        val bits = ArrayList<Int>(totalBits)
        fun appendBits(value: Int, count: Int) {
            for (i in (count - 1) downTo 0) bits.add((value ushr i) and 1)
        }
        // Mode indicator: Byte (0100)
        appendBits(0b0100, 4)
        // Character count indicator
        appendBits(bytes.size, if (spec.version <= 9) 8 else 16)
        for (b in bytes) appendBits(b.toInt() and 0xFF, 8)
        // Terminator (up to 4 zero bits)
        repeat(minOf(4, totalBits - bits.size)) { bits.add(0) }
        // Pad to byte boundary
        while (bits.size % 8 != 0) bits.add(0)

        val out = ByteArray(spec.totalDataCodewords)
        var cwIdx = 0
        var bitIdx = 0
        while (bitIdx < bits.size) {
            var v = 0
            for (k in 0 until 8) v = (v shl 1) or bits[bitIdx++]
            out[cwIdx++] = v.toByte()
        }
        var padToggle = false
        while (cwIdx < out.size) {
            out[cwIdx++] = (if (padToggle) 0x11 else 0xEC).toByte()
            padToggle = !padToggle
        }
        return out
    }

    private fun buildInterleavedCodewords(data: ByteArray, spec: VersionSpec): ByteArray {
        val rsDivisor = reedSolomonDivisor(spec.ecPerBlock)
        val totalBlocks = spec.group1Blocks + spec.group2Blocks
        val dataBlocks = ArrayList<ByteArray>(totalBlocks)
        val ecBlocks = ArrayList<ByteArray>(totalBlocks)

        var offset = 0
        repeat(spec.group1Blocks) {
            val blk = data.copyOfRange(offset, offset + spec.group1DataCw)
            offset += spec.group1DataCw
            dataBlocks.add(blk)
            ecBlocks.add(reedSolomonRemainder(blk, rsDivisor))
        }
        repeat(spec.group2Blocks) {
            val blk = data.copyOfRange(offset, offset + spec.group2DataCw)
            offset += spec.group2DataCw
            dataBlocks.add(blk)
            ecBlocks.add(reedSolomonRemainder(blk, rsDivisor))
        }

        val maxDataLen = maxOf(spec.group1DataCw, spec.group2DataCw)
        val totalLen = data.size + totalBlocks * spec.ecPerBlock
        val interleaved = ByteArray(totalLen)
        var pos = 0
        for (i in 0 until maxDataLen) {
            for (blk in dataBlocks) {
                if (i < blk.size) interleaved[pos++] = blk[i]
            }
        }
        for (i in 0 until spec.ecPerBlock) {
            for (ec in ecBlocks) {
                interleaved[pos++] = ec[i]
            }
        }
        return interleaved
    }

    private fun reedSolomonDivisor(degree: Int): ByteArray {
        val result = ByteArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = gfMultiply(result[j].toInt() and 0xFF, root).toByte()
                if (j + 1 < degree) {
                    result[j] = (result[j].toInt() xor (result[j + 1].toInt() and 0xFF)).toByte()
                }
            }
            root = gfMultiply(root, 0x02)
        }
        return result
    }

    private fun reedSolomonRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
        val rem = ByteArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() xor rem[0].toInt()) and 0xFF
            System.arraycopy(rem, 1, rem, 0, rem.size - 1)
            rem[rem.size - 1] = 0
            for (i in divisor.indices) {
                rem[i] = (rem[i].toInt() xor gfMultiply(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
        }
        return rem
    }

    private fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private fun drawFunctionPatterns(modules: BooleanArray, isFunc: BooleanArray, spec: VersionSpec, size: Int) {
        fun setFunc(x: Int, y: Int, dark: Boolean) {
            if (x in 0 until size && y in 0 until size) {
                val idx = y * size + x
                modules[idx] = dark
                isFunc[idx] = true
            }
        }

        // Timing patterns
        for (i in 0 until size) {
            setFunc(6, i, i % 2 == 0)
            setFunc(i, 6, i % 2 == 0)
        }

        // 3 Finder patterns + separators
        fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    val dark = dist != 2 && dist != 4
                    setFunc(cx + dx, cy + dy, dark)
                }
            }
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)

        // Alignment patterns
        val centers = spec.alignCenters
        for (i in centers.indices) {
            for (j in centers.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == centers.lastIndex) || (i == centers.lastIndex && j == 0)) {
                    continue
                }
                val cx = centers[i]
                val cy = centers[j]
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        setFunc(cx + dx, cy + dy, dist != 1)
                    }
                }
            }
        }

        // Reserve format info areas + Dark Module
        drawFormatBitsInternal(modules, isFunc, size, 0)

        // Version info blocks (Version 7..10)
        if (spec.version >= 7) {
            var rem = spec.version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (spec.version shl 12) or rem
            for (i in 0 until 18) {
                val dark = ((bits ushr i) and 1) != 0
                val a = size - 11 + (i % 3)
                val b = i / 3
                setFunc(b, a, dark)
                setFunc(a, b, dark)
            }
        }
    }

    private fun drawFormatBits(modules: BooleanArray, size: Int, mask: Int) {
        drawFormatBitsInternal(modules, null, size, mask)
    }

    private fun drawFormatBitsInternal(modules: BooleanArray, isFunc: BooleanArray?, size: Int, mask: Int) {
        // ECC Level L = 01
        val data = (0b01 shl 3) or (mask and 0x07)
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = ((data shl 10) or rem) xor 0x5412

        fun put(x: Int, y: Int, dark: Boolean) {
            val idx = y * size + x
            modules[idx] = dark
            if (isFunc != null) isFunc[idx] = true
        }

        // First copy around Top-Left finder
        for (i in 0..5) put(8, i, ((bits ushr i) and 1) != 0)
        put(8, 7, ((bits ushr 6) and 1) != 0)
        put(8, 8, ((bits ushr 7) and 1) != 0)
        put(7, 8, ((bits ushr 8) and 1) != 0)
        for (i in 9..14) put(14 - i, 8, ((bits ushr i) and 1) != 0)

        // Second copy along Top-Right & Bottom-Left
        for (i in 0..7) put(size - 1 - i, 8, ((bits ushr i) and 1) != 0)
        for (i in 8..14) put(8, size - 15 + i, ((bits ushr i) and 1) != 0)
        // Always-dark module
        put(8, size - 8, true)
    }

    private fun placeCodewords(modules: BooleanArray, isFunc: BooleanArray, size: Int, data: ByteArray) {
        var bitIndex = 0
        val totalBits = data.size * 8
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                val upward = ((right + 1) and 2) == 0
                val y = if (upward) size - 1 - vert else vert
                for (j in 0..1) {
                    val x = right - j
                    val idx = y * size + x
                    if (!isFunc[idx]) {
                        val dark = if (bitIndex < totalBits) {
                            ((data[bitIndex ushr 3].toInt() ushr (7 - (bitIndex and 7))) and 1) != 0
                        } else {
                            false
                        }
                        modules[idx] = dark
                        bitIndex++
                    }
                }
            }
            right -= 2
        }
    }

    private fun applyMask(modules: BooleanArray, isFunc: BooleanArray, size: Int, mask: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                val idx = y * size + x
                if (isFunc[idx]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> (x * y) % 2 + (x * y) % 3 == 0
                    6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
                    7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
                    else -> false
                }
                if (invert) modules[idx] = !modules[idx]
            }
        }
    }

    private fun computePenalty(modules: BooleanArray, size: Int): Int {
        var penalty = 0
        // Rule 1: adjacent runs >= 5
        for (y in 0 until size) {
            var runColor = modules[y * size]
            var runLen = 1
            for (x in 1 until size) {
                val c = modules[y * size + x]
                if (c == runColor) {
                    runLen++
                } else {
                    if (runLen >= 5) penalty += 3 + (runLen - 5)
                    runColor = c
                    runLen = 1
                }
            }
            if (runLen >= 5) penalty += 3 + (runLen - 5)
        }
        for (x in 0 until size) {
            var runColor = modules[x]
            var runLen = 1
            for (y in 1 until size) {
                val c = modules[y * size + x]
                if (c == runColor) {
                    runLen++
                } else {
                    if (runLen >= 5) penalty += 3 + (runLen - 5)
                    runColor = c
                    runLen = 1
                }
            }
            if (runLen >= 5) penalty += 3 + (runLen - 5)
        }
        // Rule 2: 2x2 blocks of same color
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = modules[y * size + x]
                if (c == modules[y * size + x + 1]
                    && c == modules[(y + 1) * size + x]
                    && c == modules[(y + 1) * size + x + 1]
                ) {
                    penalty += 3
                }
            }
        }
        // Rule 4: dark/light balance
        var darkCount = 0
        for (m in modules) if (m) darkCount++
        val total = size * size
        val k = kotlin.math.abs(darkCount * 20 - total * 10) / total
        penalty += k * 10
        return penalty
    }
}

@Composable
fun OpalQrCodeCard(
    content: String,
    modifier: Modifier = Modifier,
    qrSize: Dp = 176.dp,
) {
    val matrix = remember(content) {
        runCatching { QrCodeMatrix.encode(content) }.getOrNull()
    }
    if (matrix == null) return

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .border(1.dp, OpalPalette.BorderSubtle, RoundedCornerShape(18.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(qrSize)) {
            val quietModules = 2
            val totalModules = matrix.size + quietModules * 2
            val cellPx = size.minDimension / totalModules.toFloat()
            val offsetX = (size.width - cellPx * totalModules) / 2f
            val offsetY = (size.height - cellPx * totalModules) / 2f
            val ink = Color(0xFF0F172A)
            for (y in 0 until matrix.size) {
                for (x in 0 until matrix.size) {
                    if (matrix.get(x, y)) {
                        drawRect(
                            color = ink,
                            topLeft = Offset(
                                offsetX + (x + quietModules) * cellPx,
                                offsetY + (y + quietModules) * cellPx,
                            ),
                            size = Size(cellPx + 0.45f, cellPx + 0.45f),
                        )
                    }
                }
            }
        }
    }
}
