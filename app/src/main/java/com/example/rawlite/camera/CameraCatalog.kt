package com.example.rawlite.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import com.example.rawlite.device.CameraCapability
import com.example.rawlite.device.DeviceCapabilitiesGateway
import com.example.rawlite.device.Dimensions
import java.util.Locale
import kotlin.math.abs

internal data class CameraOption(
    val label: String,
    val openId: String,
    val physicalId: String?,
    val characteristics: CameraCharacteristics,
    val rawSizes: List<Size>,
    val capability: CameraCapability
)

/** Camera2 discovery boundary. It preserves the camera ordering used by RawLite 0.33. */
internal class CameraCatalog(
    private val manager: CameraManager,
    private val capabilities: DeviceCapabilitiesGateway
) {
    fun discover(): List<CameraOption> {
        val options = mutableListOf<CameraOption>()
        for (id in manager.cameraIdList) {
            val logicalChars = manager.getCameraCharacteristics(id)
            val caps = logicalChars.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            ) ?: continue
            if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) continue

            val facing = logicalChars.get(CameraCharacteristics.LENS_FACING)
            val physicalIds = logicalChars.physicalCameraIds
            if (facing == CameraCharacteristics.LENS_FACING_BACK && physicalIds.isNotEmpty()) {
                for (physicalId in physicalIds) {
                    val physicalChars = try {
                        manager.getCameraCharacteristics(physicalId)
                    } catch (error: Exception) {
                        Log.w(TAG, "No characteristics for physical camera $physicalId", error)
                        continue
                    }
                    val capability = capabilities.camera(id, physicalId) ?: continue
                    val sizes = dngCompatibleSizes(physicalChars, capability)
                    if (sizes.isEmpty()) continue
                    options += CameraOption(
                        label = lensLabel(physicalChars, physicalId),
                        openId = id,
                        physicalId = physicalId,
                        characteristics = physicalChars,
                        rawSizes = sizes,
                        capability = capability
                    )
                }
            } else {
                val capability = capabilities.camera(id) ?: continue
                val sizes = dngCompatibleSizes(logicalChars, capability)
                if (sizes.isEmpty()) continue
                val label = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    "Frontal"
                } else {
                    lensLabel(logicalChars, id)
                }
                options += CameraOption(label, id, null, logicalChars, sizes, capability)
            }
        }

        return options.sortedWith(
            compareBy<CameraOption> { if (it.openId == "0") 0 else 1 }
                .thenByDescending {
                    it.characteristics.get(
                        CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                    )?.firstOrNull() ?: 0f
                }
        )
    }

    private fun lensLabel(chars: CameraCharacteristics, id: String): String {
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: 0f
        return when {
            focal >= 5f -> "Principal · ${formatOne(focal)} mm"
            focal > 0f -> "Ultra gran angular · ${formatOne(focal)} mm"
            else -> "Cámara $id"
        }
    }

    private fun dngCompatibleSizes(
        chars: CameraCharacteristics,
        capability: CameraCapability
    ): List<Size> {
        val map = chars.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return emptyList()
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList() ?: return emptyList()
        val selected = RawSizeSelector.select(rawSizes)
        val full = selected.firstOrNull() ?: return emptyList()
        val reducedRaw10 = selected.drop(1).firstOrNull { size ->
            Dimensions(size.width, size.height) in capability.raw10Sizes
        }
        return listOfNotNull(full, reducedRaw10)
    }

    private fun formatOne(value: Float): String = String.format(Locale.US, "%.1f", value)

    companion object {
        private const val TAG = "RawLiteCameraCatalog"
    }
}

internal object RawSizeSelector {
    fun select(rawSizes: List<Size>): List<Size> {
        val fourThree = rawSizes.filter {
            abs(it.width.toDouble() / it.height - 4.0 / 3.0) < 0.02
        }
        if (fourThree.isEmpty()) {
            return rawSizes.sortedByDescending { it.width.toLong() * it.height }
        }

        val full = fourThree.maxBy { it.width.toLong() * it.height }
        val reduced = fourThree
            .filter { it != full && it.width <= full.width / 2 + 16 }
            .maxByOrNull { it.width.toLong() * it.height }
        return listOfNotNull(full, reduced).distinct()
    }
}

/** Selects the smallest RAW10 stream that fully contains the encoded PURE 10 frame. */
internal object Pure10SourceSelector {
    fun select(raw10Sizes: List<Size>, outputSize: Size, fallback: Size): Size {
        return raw10Sizes
            .asSequence()
            .filter { it.width >= outputSize.width && it.height >= outputSize.height }
            .minWithOrNull(
                compareBy<Size> { it.width.toLong() * it.height }
                    .thenBy { kotlin.math.abs(it.width.toDouble() / it.height - 16.0 / 9.0) }
            ) ?: fallback
    }
}
