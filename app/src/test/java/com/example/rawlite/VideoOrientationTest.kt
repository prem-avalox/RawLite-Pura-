package com.example.rawlite

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoOrientationTest {
    @Test
    fun rawSequenceFallsBackToMetadataRotationForLegacyClips() {
        assertEquals(
            180,
            VideoOrientation.rawSequenceRotationDegrees(
                metadataRotationDegrees = 180,
                sensorOrientationDegrees = 90,
                uiContentRotationDegrees = null,
                frontFacing = false
            )
        )
    }

    @Test
    fun rawSequenceKeepsPortraitSensorRotationWhenUiWasPortrait() {
        assertEquals(
            90,
            VideoOrientation.rawSequenceRotationDegrees(
                metadataRotationDegrees = 90,
                sensorOrientationDegrees = 90,
                uiContentRotationDegrees = 0,
                frontFacing = false
            )
        )
    }

    @Test
    fun rawSequenceDoesNotInvertRightLandscapeUiPreview() {
        assertEquals(
            0,
            VideoOrientation.rawSequenceRotationDegrees(
                metadataRotationDegrees = 180,
                sensorOrientationDegrees = 90,
                uiContentRotationDegrees = 90,
                frontFacing = false
            )
        )
    }

    @Test
    fun rawSequenceFlipsOppositeLandscapeUiPreview() {
        assertEquals(
            180,
            VideoOrientation.rawSequenceRotationDegrees(
                metadataRotationDegrees = 0,
                sensorOrientationDegrees = 90,
                uiContentRotationDegrees = 270,
                frontFacing = false
            )
        )
    }

    @Test
    fun textureKeepsRotationWhenCodecReportsEncodedDimensions() {
        assertEquals(
            90,
            VideoOrientation.textureRotationDegrees(
                metadataRotationDegrees = 90,
                encodedWidth = 2032,
                encodedHeight = 1536,
                reportedWidth = 2032,
                reportedHeight = 1536
            )
        )
    }

    @Test
    fun textureSkipsRotationWhenCodecReportsDisplayDimensions() {
        assertEquals(
            0,
            VideoOrientation.textureRotationDegrees(
                metadataRotationDegrees = 90,
                encodedWidth = 2032,
                encodedHeight = 1536,
                reportedWidth = 1536,
                reportedHeight = 2032
            )
        )
    }

    @Test
    fun thumbnailSkipsRotationWhenRetrieverReturnsDisplayBitmap() {
        assertEquals(
            0,
            VideoOrientation.bitmapRotationDegrees(
                metadataRotationDegrees = 90,
                encodedWidth = 2032,
                encodedHeight = 1536,
                bitmapWidth = 1536,
                bitmapHeight = 2032
            )
        )
    }

    @Test
    fun thumbnailKeepsRotationWhenRetrieverReturnsEncodedBitmap() {
        assertEquals(
            90,
            VideoOrientation.bitmapRotationDegrees(
                metadataRotationDegrees = 90,
                encodedWidth = 2032,
                encodedHeight = 1536,
                bitmapWidth = 2032,
                bitmapHeight = 1536
            )
        )
    }
}
