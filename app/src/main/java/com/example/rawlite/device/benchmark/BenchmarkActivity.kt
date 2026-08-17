package com.example.rawlite.device.benchmark

import android.app.Activity
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/** Engineering-only entry point. It is exported only by the debug manifest. */
class BenchmarkActivity : Activity() {
    private lateinit var status: TextView
    private val cancelled = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
            textSize = 17f
            setPadding(48, 48, 48, 48)
            text = "Preparando diagnóstico del dispositivo…"
        }
        setContentView(status)
        Thread({ executeBenchmark() }, "RawLiteDeviceBenchmark").start()
    }

    private fun executeBenchmark() {
        try {
            val request = requestFromIntent()
            val runner = DeviceBenchmarkRunner(
                context = this,
                cameraManager = getSystemService(CameraManager::class.java),
                cancelled = cancelled::get
            )
            val report = runner.run(request, ::updateStatus)
            val reportFile = BenchmarkReportStore(this).save(report)
            Log.i(TAG, "RL_BENCHMARK_REPORT=${reportFile.absolutePath}")
            val summary = report.modes.joinToString("\n") { mode ->
                "${mode.id}: ${mode.recordingTier.name}"
            }
            updateStatus(
                "Diagnóstico terminado\n\n$summary\n\n" +
                    "Temporal ${"%.1f".format(report.temperatureBeforeC)} → " +
                    "${"%.1f".format(report.temperatureAfterC)} °C"
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Benchmark failed", error)
            updateStatus("El diagnóstico falló:\n${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun requestFromIntent(): BenchmarkRequest {
        val stageText = intent.getStringExtra(EXTRA_STAGES).orEmpty()
        val stages = if (stageText.isBlank() || stageText.equals("all", ignoreCase = true)) {
            BenchmarkStage.entries.toSet()
        } else {
            stageText.split(',').mapNotNull { value ->
                BenchmarkStage.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
            }.toSet().ifEmpty { setOf(BenchmarkStage.HAL) }
        }
        val frameRates = intent.getStringExtra(EXTRA_FPS)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.distinct()
            ?.sorted()
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(24, 30)
        @Suppress("DEPRECATION")
        val durationValue = intent.extras?.get(EXTRA_DURATION_SECONDS)
        val duration = when (durationValue) {
            is Number -> durationValue.toDouble()
            is String -> durationValue.toDoubleOrNull() ?: 3.0
            else -> 3.0
        }.coerceIn(1.0, 30.0)
        return BenchmarkRequest(stages, frameRates, duration)
    }

    private fun updateStatus(message: String) {
        runOnUiThread { status.text = message }
        Log.i(TAG, message.replace('\n', ' '))
    }

    override fun onDestroy() {
        cancelled.set(true)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STAGES = "stages"
        const val EXTRA_FPS = "fps"
        const val EXTRA_DURATION_SECONDS = "duration_seconds"
        private const val TAG = "RawLiteBenchmark"
    }
}
