package com.example.rawlite

internal object VideoOrientation {
    fun textureRotationDegrees(
        metadataRotationDegrees: Int,
        encodedWidth: Int,
        encodedHeight: Int,
        reportedWidth: Int,
        reportedHeight: Int
    ): Int {
        val rotation = normalize(metadataRotationDegrees)
        if (rotation % 180 == 0) return rotation
        if (dimensionsLookRotated(
                sourceWidth = encodedWidth,
                sourceHeight = encodedHeight,
                outputWidth = reportedWidth,
                outputHeight = reportedHeight
            )
        ) {
            return 0
        }
        return rotation
    }

    fun bitmapRotationDegrees(
        metadataRotationDegrees: Int,
        encodedWidth: Int,
        encodedHeight: Int,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Int {
        val rotation = normalize(metadataRotationDegrees)
        if (rotation % 180 == 0) return rotation
        if (dimensionsLookRotated(
                sourceWidth = encodedWidth,
                sourceHeight = encodedHeight,
                outputWidth = bitmapWidth,
                outputHeight = bitmapHeight
            )
        ) {
            return 0
        }
        return rotation
    }

    private fun dimensionsLookRotated(
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int
    ): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) {
            return false
        }
        val sourceLandscape = sourceWidth > sourceHeight
        val outputLandscape = outputWidth > outputHeight
        return sourceLandscape != outputLandscape
    }

    private fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360
}
