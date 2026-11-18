package com.cameramapper3d.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.cameramapper3d.app.databinding.ActivityScanBinding
import com.cameramapper3d.app.gl.BackgroundRenderer
import com.cameramapper3d.app.gl.MeshRenderer
import com.cameramapper3d.app.gl.ShadeMode
import com.cameramapper3d.core.IntegrationParams
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * Live scanning: shows the camera feed with the growing 3D mesh overlaid, and streams depth
 * frames from ARCore into the [FusionEngine].
 */
class ScanActivity : AppCompatActivity(), GLSurfaceView.Renderer {
    private lateinit var binding: ActivityScanBinding
    private lateinit var engine: FusionEngine
    private var voxelSize = 0.02f

    private var session: Session? = null
    private var installRequested = false
    private var cameraTextureSet = false

    private val background = BackgroundRenderer()
    private val meshRenderer = MeshRenderer()
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val light = normalize(floatArrayOf(0.3f, 1f, 0.5f))

    @Volatile private var scanning = false
    @Volatile private var useRawDepth = true
    @Volatile private var shadeMode = ShadeMode.COLOR
    @Volatile private var displayRotation = Surface.ROTATION_0
    @Volatile private var finishing = false
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var viewportChanged = false
    private var lastDepthTimestamp = 0L

    // Recent camera poses by timestamp, so late-arriving depth is placed where it was captured.
    private val poseHistory = ArrayDeque<Pair<Long, FloatArray>>()

    // Depth diagnostics (GL thread), shown in the status line.
    private var lastUsableFraction = -1f
    private var lastCaptureError: String? = null
    private var lastGoodDepthAt = 0L
    @Volatile private var rawFallbackPending = false

    // User-tunable accuracy settings (remembered between scans).
    @Volatile private var minConfidencePercent = DEFAULT_CONFIDENCE_PERCENT
    @Volatile private var maxDepthM = 4f
    private var lastStatusAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.applyBars(top = binding.topBar, bottom = binding.bottomBar)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        voxelSize = intent.getFloatExtra(EXTRA_VOXEL_SIZE, 0.02f)
        engine = FusionEngine(voxelSize)

        binding.surfaceView.apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@ScanActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setWillNotDraw(false)
        }

        binding.scanButton.setOnClickListener {
            scanning = !scanning
            lastGoodDepthAt = SystemClock.elapsedRealtime()
            binding.scanButton.setText(if (scanning) R.string.pause else R.string.start)
        }
        binding.resetButton.setOnClickListener {
            engine.reset()
            lastDepthTimestamp = 0L
        }
        binding.finishButton.setOnClickListener { finishScan() }
        binding.rawDepthSwitch.setOnCheckedChangeListener { _, checked ->
            useRawDepth = checked
            lastGoodDepthAt = SystemClock.elapsedRealtime()
            lastUsableFraction = -1f
        }
        setupTuning()
        binding.viewModeButton.setOnClickListener {
            shadeMode = if (shadeMode == ShadeMode.COLOR) ShadeMode.SHADED else ShadeMode.COLOR
            binding.viewModeButton.setText(if (shadeMode == ShadeMode.COLOR) R.string.view_shaded else R.string.view_color)
        }
    }

    override fun onResume() {
        super.onResume()
        displayRotation = currentRotation()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        if (session == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true
                    return
                }
                val s = Session(this)
                val config = s.config
                if (!s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    s.close()
                    fail(getString(R.string.depth_not_supported))
                    return
                }
                config.depthMode = Config.DepthMode.AUTOMATIC
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                config.focusMode = Config.FocusMode.FIXED
                config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                config.lightEstimationMode = Config.LightEstimationMode.DISABLED
                s.configure(config)
                session = s
                cameraTextureSet = false
            } catch (e: UnavailableException) {
                fail(getString(R.string.arcore_failed, e.javaClass.simpleName))
                return
            } catch (e: Exception) {
                fail(getString(R.string.arcore_failed, e.message ?: e.javaClass.simpleName))
                return
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            session = null
            fail(getString(R.string.arcore_failed, "camera not available"))
            return
        }
        binding.surfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.surfaceView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        engine.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            fail(getString(R.string.camera_permission_needed))
        }
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation ?: Surface.ROTATION_0
        else windowManager.defaultDisplay.rotation

    private fun finishScan() {
        if (finishing) return
        finishing = true
        scanning = false
        binding.scanButton.setText(R.string.start)
        setControlsEnabled(false)
        binding.progress.visibility = View.VISIBLE
        binding.statusText.setText(R.string.saving)
        engine.finish { mesh ->
            if (mesh.isEmpty) {
                runOnUiThread {
                    finishing = false
                    setControlsEnabled(true)
                    binding.progress.visibility = View.GONE
                    Toast.makeText(this, R.string.nothing_scanned, Toast.LENGTH_SHORT).show()
                }
                return@finish
            }
            val file = ScanStore.save(this, mesh, voxelSize)
            runOnUiThread {
                startActivity(Intent(this, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, file.absolutePath))
                finish()
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.scanButton.isEnabled = enabled
        binding.resetButton.isEnabled = enabled
        binding.finishButton.isEnabled = enabled
    }

    // --- GLSurfaceView.Renderer (GL thread) ---

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        meshRenderer.createOnGlThread()
        cameraTextureSet = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (!cameraTextureSet) {
            s.setCameraTextureName(background.textureId)
            cameraTextureSet = true
        }
        if (viewportChanged) {
            s.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
            viewportChanged = false
        }
        val frame = try {
            s.update()
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "Camera not available", e)
            return
        } catch (e: Exception) {
            Log.w(TAG, "session.update failed", e)
            return
        }
        background.draw(frame)

        val camera = frame.camera
        if (camera.trackingState == TrackingState.TRACKING) {
            rememberPose(frame.timestamp, camera)
            if (scanning && !finishing && !engine.isBusy) {
                captureDepth(frame)
                checkRawDepthFallback()
            }
            camera.getProjectionMatrix(proj, 0, 0.05f, 100f)
            camera.getViewMatrix(view, 0)
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
            meshRenderer.sync(engine.blockMeshes, maxUploads = 200)
            meshRenderer.draw(mvp, shadeMode, alpha = 0.8f, lightDir = light)
        }
        updateStatus(camera)
    }

    private fun integrationParams() = IntegrationParams(
        maxDepth = maxDepthM,
        minConfidence = (minConfidencePercent * 255 + 50) / 100,
    )

    private fun setupTuning() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        minConfidencePercent = prefs.getInt("min_confidence_pct", DEFAULT_CONFIDENCE_PERCENT).coerceIn(0, 95)
        maxDepthM = prefs.getFloat("max_depth_m_$voxelSize", if (voxelSize >= 0.04f) 6f else 4f).coerceIn(1f, 8f)

        binding.confidenceSlider.value = (minConfidencePercent / 5 * 5).toFloat()
        binding.rangeSlider.value = (Math.round(maxDepthM * 2) / 2f)
        binding.confidenceLabel.text = getString(R.string.confidence_value, minConfidencePercent)
        binding.rangeLabel.text = getString(R.string.range_value, maxDepthM)

        binding.confidenceSlider.addOnChangeListener { _, value, _ ->
            minConfidencePercent = value.toInt()
            binding.confidenceLabel.text = getString(R.string.confidence_value, minConfidencePercent)
            prefs.edit { putInt("min_confidence_pct", minConfidencePercent) }
            lastGoodDepthAt = SystemClock.elapsedRealtime() // give the new setting a chance
        }
        binding.rangeSlider.addOnChangeListener { _, value, _ ->
            maxDepthM = value
            binding.rangeLabel.text = getString(R.string.range_value, maxDepthM)
            prefs.edit { putFloat("max_depth_m_$voxelSize", maxDepthM) }
        }
        binding.tuneButton.setOnClickListener {
            binding.tunePanel.visibility = if (binding.tunePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    private fun rememberPose(timestamp: Long, camera: Camera) {
        val m = FloatArray(16)
        camera.pose.toMatrix(m, 0)
        poseHistory.addLast(timestamp to m)
        while (poseHistory.size > POSE_HISTORY) poseHistory.removeFirst()
    }

    private fun poseAt(timestamp: Long): FloatArray? = poseHistory.lastOrNull { it.first == timestamp }?.second

    private fun captureDepth(frame: com.google.ar.core.Frame) {
        val params = integrationParams()
        try {
            val captured = ArFrameConverter.capture(frame, useRawDepth, lastDepthTimestamp, params, ::poseAt) ?: return
            lastDepthTimestamp = captured.depthTimestamp
            lastUsableFraction = captured.usableFraction
            lastCaptureError = null
            if (captured.usableFraction < MIN_USABLE_FRACTION) return
            if (engine.trySubmit(captured.frame, params)) lastGoodDepthAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            Log.w(TAG, "Depth capture failed", e)
            lastCaptureError = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        }
    }

    /** If accurate (raw) depth yields nothing usable for a while, switch to smoothed depth. */
    private fun checkRawDepthFallback() {
        if (!useRawDepth || rawFallbackPending) return
        if (SystemClock.elapsedRealtime() - lastGoodDepthAt < RAW_FALLBACK_MS) return
        rawFallbackPending = true
        useRawDepth = false
        runOnUiThread {
            binding.rawDepthSwitch.isChecked = false
            rawFallbackPending = false
            Toast.makeText(this, R.string.raw_depth_fallback, Toast.LENGTH_LONG).show()
        }
    }

    private fun depthStatus(): String = when {
        lastCaptureError != null -> "Depth error: $lastCaptureError"
        lastUsableFraction < 0f -> "Waiting for depth…"
        else -> "${if (useRawDepth) "Accurate" else "Smoothed"} depth: ${(lastUsableFraction * 100).toInt()}% usable"
    }

    private fun updateStatus(camera: Camera) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatusAt < 250 || finishing) return
        lastStatusAt = now
        val stats = engine.stats
        if (stats.memoryLimitReached && scanning) scanning = false
        val text = when {
            camera.trackingState != TrackingState.TRACKING -> when (camera.trackingFailureReason) {
                TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark – turn on more lights."
                TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast – slow down."
                TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point at a surface with more texture or detail."
                TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable."
                else -> "Move the phone slowly to start tracking…"
            }
            stats.memoryLimitReached -> getString(R.string.memory_limit)
            stats.frames == 0 && !scanning -> getString(R.string.hint_start)
            else -> {
                val state = if (scanning) "Scanning" else "Paused"
                "$state · ${stats.frames} frames · ${formatCount(meshRenderer.triangleCount)} triangles\n" +
                    depthStatus() + "\n" +
                    "Voxel ${(voxelSize * 100).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }} cm · " +
                    "fuse ${stats.fuseMs} ms · mesh ${stats.meshMs} ms"
            }
        }
        val stopped = stats.memoryLimitReached && !scanning
        runOnUiThread {
            if (finishing) return@runOnUiThread
            binding.statusText.text = text
            if (stopped) binding.scanButton.setText(R.string.start)
        }
    }

    private fun formatCount(n: Int): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1e6)
        n >= 1_000 -> "%.0fk".format(n / 1e3)
        else -> n.toString()
    }

    companion object {
        const val EXTRA_VOXEL_SIZE = "voxel_size"
        private const val REQUEST_CAMERA = 1
        private const val TAG = "ScanActivity"
        private const val POSE_HISTORY = 60
        /** ~70/255: phones without a ToF sensor report mostly mid-range confidence. */
        private const val DEFAULT_CONFIDENCE_PERCENT = 25
        private const val MIN_USABLE_FRACTION = 0.01f
        private const val RAW_FALLBACK_MS = 5_000L

        private fun normalize(v: FloatArray): FloatArray {
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }
    }
}
