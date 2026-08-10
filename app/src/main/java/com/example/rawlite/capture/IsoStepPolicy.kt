package com.example.rawlite.capture

import kotlin.math.abs
import kotlin.math.roundToInt

internal object IsoStepPolicy {
    private val thirdStopBase = intArrayOf(100, 125, 160, 200, 250, 320, 400, 500, 640, 800)

    fun steps(minIso: Int, maxIso: Int): List<Int> {
        val lower = minIso.coerceAtLeast(1)
        val upper = maxIso.coerceAtLeast(lower)
        val values = linkedSetOf<Int>()
        values += lower
        var decade = 1
        while (decade <= upper * 10L) {
            for (base in thirdStopBase) {
                val value = base * decade
                if (value in lower..upper) values += value
            }
            if (decade > Int.MAX_VALUE / 10) break
            decade *= 10
        }
        values += upper
        return values.sorted()
    }

    fun nearest(value: Int, minIso: Int, maxIso: Int): Int {
        val options = steps(minIso, maxIso)
        return options.minByOrNull { abs(it - value) } ?: value.coerceIn(minIso, maxIso)
    }

    fun progressFor(value: Int, minIso: Int, maxIso: Int): Int {
        val options = steps(minIso, maxIso)
        if (options.size <= 1) return 0
        val index = options.indices.minByOrNull { abs(options[it] - value) } ?: 0
        return (index * 1000f / (options.size - 1)).toInt().coerceIn(0, 1000)
    }

    fun valueForProgress(progress: Int, minIso: Int, maxIso: Int): Int {
        val options = steps(minIso, maxIso)
        if (options.isEmpty()) return minIso.coerceAtLeast(1)
        if (options.size == 1) return options.first()
        val index = ((progress.coerceIn(0, 1000) / 1000.0) * (options.size - 1))
            .roundToInt()
            .coerceIn(options.indices)
        return options[index]
    }

    fun stepProgress(minIso: Int, maxIso: Int): Int {
        val count = steps(minIso, maxIso).size
        if (count <= 1) return 1000
        return (1000f / (count - 1)).toInt().coerceAtLeast(1)
    }
}
