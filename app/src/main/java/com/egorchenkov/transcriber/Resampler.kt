package com.egorchenkov.transcriber

/** Потоковый ресэмплер: усреднение окна при понижении частоты, линейная интерполяция при повышении. */
class Resampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private var carry = FloatArray(0)
    private var pos = 0.0

    fun process(x: FloatArray): FloatArray {
        if (inRate == outRate) return x
        val buf = if (carry.isEmpty()) x else carry + x
        val out = FloatArray((buf.size / step).toInt() + 2)
        var n = 0
        if (step >= 1.0) {
            while (pos + step <= buf.size) {
                val a = pos.toInt()
                val b = maxOf((pos + step).toInt(), a + 1)
                var s = 0f
                for (i in a until b) s += buf[i]
                out[n++] = s / (b - a)
                pos += step
            }
        } else {
            while (pos + 1 < buf.size) {
                val i = pos.toInt()
                val f = (pos - i).toFloat()
                out[n++] = buf[i] * (1 - f) + buf[i + 1] * f
                pos += step
            }
        }
        val drop = minOf(pos.toInt(), buf.size)
        carry = buf.copyOfRange(drop, buf.size)
        pos -= drop
        return out.copyOf(n)
    }
}
