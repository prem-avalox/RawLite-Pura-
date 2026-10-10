package com.example.rawlite

import android.annotation.TargetApi
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View

internal object TechnicalShaderManager {

    private const val PEAKING_AGSL = """
        uniform shader image;

        half luma(float2 coord) {
            half4 c = image.eval(coord);
            return dot(c.rgb, half3(0.299, 0.587, 0.114));
        }

        half4 main(float2 fragCoord) {
            half4 src = image.eval(fragCoord);
            float step = 1.5;

            half tl = luma(fragCoord + float2(-step, -step));
            half tc = luma(fragCoord + float2(  0.0, -step));
            half tr = luma(fragCoord + float2( step, -step));
            half ml = luma(fragCoord + float2(-step,   0.0));
            half mr = luma(fragCoord + float2( step,   0.0));
            half bl = luma(fragCoord + float2(-step,  step));
            half bc = luma(fragCoord + float2(  0.0,  step));
            half br = luma(fragCoord + float2( step,  step));

            half gx = -tl + tr - 2.0 * ml + 2.0 * mr - bl + br;
            half gy = -tl - 2.0 * tc - tr + bl + 2.0 * bc + br;
            half edge = sqrt(gx * gx + gy * gy);

            if (edge > 0.95) {
                return half4(0.0, 0.94, 0.82, 1.0);
            }
            return src;
        }
    """

    private const val ZEBRA_AGSL = """
        uniform shader image;

        half4 main(float2 fragCoord) {
            half4 src = image.eval(fragCoord);
            half luma = dot(src.rgb, half3(0.299, 0.587, 0.114));

            if (luma >= 0.95) {
                if (mod(fragCoord.x + fragCoord.y, 16.0) < 8.0) {
                    return half4(1.0, 1.0, 1.0, 1.0);
                } else {
                    return half4(0.08, 0.08, 0.08, 1.0);
                }
            }
            return src;
        }
    """

    private const val FALSE_COLOR_AGSL = """
        uniform shader image;

        half4 main(float2 fragCoord) {
            half4 src = image.eval(fragCoord);
            half luma = dot(src.rgb, half3(0.299, 0.587, 0.114));

            if (luma < 0.025) return half4(0.69, 0.00, 1.00, 1.0);
            if (luma < 0.080) return half4(0.12, 0.30, 1.00, 1.0);
            if (luma < 0.160) return half4(0.00, 0.66, 1.00, 1.0);
            if (luma < 0.350) return half4(0.44, 0.44, 0.44, 1.0);
            if (luma < 0.450) return half4(0.09, 0.79, 0.48, 1.0);
            if (luma < 0.580) return half4(1.00, 0.44, 0.66, 1.0);
            if (luma < 0.720) return half4(0.66, 0.66, 0.66, 1.0);
            if (luma < 0.880) return half4(1.00, 0.88, 0.27, 1.0);
            if (luma < 0.970) return half4(1.00, 0.48, 0.09, 1.0);
            return half4(1.00, 0.12, 0.12, 1.0);
        }
    """

    private var peakingEffect: RenderEffect? = null
    private var zebraEffect: RenderEffect? = null
    private var falseColorEffect: RenderEffect? = null

    fun apply(view: View, mode: TechnicalOverlayMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val effect = when (mode) {
            TechnicalOverlayMode.PEAKING -> getOrCreatePeakingEffect()
            TechnicalOverlayMode.ZEBRA -> getOrCreateZebraEffect()
            TechnicalOverlayMode.FALSE_COLOR -> getOrCreateFalseColorEffect()
            else -> null
        }
        view.setRenderEffect(effect)
    }

    fun apply(view: View, overlay: ActiveOverlay) {
        val mode = when (overlay) {
            ActiveOverlay.NONE -> TechnicalOverlayMode.OFF
            ActiveOverlay.FOCUS_PEAKING -> TechnicalOverlayMode.PEAKING
            ActiveOverlay.ZEBRAS -> TechnicalOverlayMode.ZEBRA
            ActiveOverlay.FALSE_COLOR -> TechnicalOverlayMode.FALSE_COLOR
        }
        apply(view, mode)
    }

    fun clear(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun getOrCreatePeakingEffect(): RenderEffect {
        return peakingEffect ?: run {
            val shader = RuntimeShader(PEAKING_AGSL)
            RenderEffect.createRuntimeShaderEffect(shader, "image").also {
                peakingEffect = it
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun getOrCreateZebraEffect(): RenderEffect {
        return zebraEffect ?: run {
            val shader = RuntimeShader(ZEBRA_AGSL)
            RenderEffect.createRuntimeShaderEffect(shader, "image").also {
                zebraEffect = it
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun getOrCreateFalseColorEffect(): RenderEffect {
        return falseColorEffect ?: run {
            val shader = RuntimeShader(FALSE_COLOR_AGSL)
            RenderEffect.createRuntimeShaderEffect(shader, "image").also {
                falseColorEffect = it
            }
        }
    }
}
