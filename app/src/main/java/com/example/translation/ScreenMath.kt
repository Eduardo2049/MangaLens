package com.example.translation

/** Small screen/image calculations kept in plain Kotlin so they are unit-tested on the JVM. */
object ScreenMath {

    /**
     * Top-left position that keeps a floating window of [width] x [height] entirely on a [screenWidth] x
     * [screenHeight] screen. A window dragged (or rotated) off the edge could otherwise not be reached again.
     */
    fun clampToScreen(x: Int, y: Int, width: Int, height: Int, screenWidth: Int, screenHeight: Int): Pair<Int, Int> =
        x.coerceIn(0, (screenWidth - width).coerceAtLeast(0)) to y.coerceIn(0, (screenHeight - height).coerceAtLeast(0))

    /**
     * Power-of-two `inSampleSize` so a decoded image has at most [maxPixels] pixels and no side above [maxSide].
     * A long webtoon strip or a 50 MP photo decoded at full size runs out of memory or is too large to draw.
     */
    fun sampleSizeFor(width: Int, height: Int, maxPixels: Long, maxSide: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while ((width / sample).toLong() * (height / sample) > maxPixels || maxOf(width, height) / sample > maxSide) {
            sample *= 2
        }
        return sample
    }
}
