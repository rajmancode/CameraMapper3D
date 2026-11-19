package com.cameramapper3d.app

import android.annotation.SuppressLint
import android.content.Intent
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.cameramapper3d.app.databinding.ActivityViewerBinding
import com.cameramapper3d.app.databinding.DialogStraightenBinding
import com.cameramapper3d.app.gl.MarkerRenderer
import com.cameramapper3d.app.gl.MeshRenderer
import com.cameramapper3d.app.gl.ShadeMode
import com.cameramapper3d.core.MeshData
import com.cameramapper3d.core.MeshRaycaster
import com.cameramapper3d.core.WallStraightener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.sqrt

/** Orbit viewer for a saved scan, with tap-to-measure and export. */
class ViewerActivity : AppCompatActivity(), GLSurfaceView.Renderer {
    private lateinit var binding: ActivityViewerBinding
    private lateinit var file: File // changes when the scan is renamed
    private val worker = Executors.newSingleThreadExecutor()
    private val camera = OrbitCamera()
    private val meshRenderer = MeshRenderer()
    private val markerRenderer = MarkerRenderer()
    private val vp = FloatArray(16)
    private val light = floatArrayOf(0.25f, 0.9f, 0.35f).let { v ->
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }

    @Volatile private var mesh: MeshData? = null
    @Volatile private var raycaster: MeshRaycaster? = null
    @Volatile private var shadeMode = ShadeMode.COLOR
    @Volatile private var markers = FloatArray(0)
    private var pointSizePx = 30f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.applyBars(top = binding.toolbar, bottom = binding.bottomBar)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        file = File(requireNotNull(intent.getStringExtra(EXTRA_PATH)))
        title = ScanStore.displayName(file)
        pointSizePx = 12f * resources.displayMetrics.density

        binding.surfaceView.apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            setRenderer(this@ViewerActivity)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        setupGestures()

        binding.viewModeButton.setOnClickListener {
            shadeMode = if (shadeMode == ShadeMode.COLOR) ShadeMode.SHADED else ShadeMode.COLOR
            binding.viewModeButton.setText(if (shadeMode == ShadeMode.COLOR) R.string.view_shaded else R.string.view_color)
            binding.surfaceView.requestRender()
        }
        binding.clearButton.setOnClickListener { clearMeasurement() }
        binding.plyButton.setOnClickListener { share(ScanStore.Format.PLY) }
        binding.objButton.setOnClickListener { share(ScanStore.Format.OBJ) }
        setExportEnabled(false)

        worker.execute {
            try {
                val m = ScanStore.load(file)
                val rc = MeshRaycaster(m)
                camera.fit(m.bounds())
                mesh = m
                raycaster = rc
                runOnUiThread {
                    binding.progress.visibility = View.GONE
                    binding.statsText.text = describe(m)
                    setExportEnabled(true)
                    binding.surfaceView.requestRender()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.load_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.surfaceView.onResume()
    }

    override fun onPause() {
        binding.surfaceView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_straighten -> {
            showStraightenDialog()
            true
        }
        R.id.action_rename -> {
            RenameDialog.show(this, file) { renamed ->
                file = renamed
                title = ScanStore.displayName(renamed)
            }
            true
        }
        R.id.action_delete -> {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.delete_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ -> ScanStore.delete(file); finish() }
                .show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showStraightenDialog() {
        val m = mesh ?: return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val content = DialogStraightenBinding.inflate(layoutInflater)
        content.squareSwitch.isChecked = prefs.getBoolean("straighten_square", true)
        content.snapSlider.value = prefs.getInt("straighten_snap_cm", 4).coerceIn(1, 10).toFloat()
        fun label() { content.snapLabel.text = getString(R.string.straighten_snap_value, content.snapSlider.value.toInt()) }
        label()
        content.snapSlider.addOnChangeListener { _, _, _ -> label() }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.straighten_title)
            .setView(content.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.straighten_apply) { _, _ ->
                val snapCm = content.snapSlider.value.toInt()
                val square = content.squareSwitch.isChecked
                prefs.edit { putBoolean("straighten_square", square); putInt("straighten_snap_cm", snapCm) }
                straighten(m, WallStraightener.Options(snapDistance = snapCm / 100f, squareCorners = square))
            }
            .show()
    }

    /** Runs wall straightening, saves the result as a new scan and opens it. The original is kept. */
    private fun straighten(m: MeshData, options: WallStraightener.Options) {
        setExportEnabled(false)
        binding.progress.visibility = View.VISIBLE
        binding.measureText.setText(R.string.straighten_working)
        worker.execute {
            try {
                val result = WallStraightener.process(m, options)
                if (result.planes.isEmpty()) {
                    runOnUiThread {
                        binding.progress.visibility = View.GONE
                        setExportEnabled(true)
                        binding.measureText.setText(R.string.measure_hint)
                        Toast.makeText(this, R.string.straighten_none, Toast.LENGTH_LONG).show()
                    }
                    return@execute
                }
                val saved = ScanStore.saveDerived(this, result.mesh, file, getString(R.string.straighten_suffix))
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.straighten_done, result.wallCount, result.levelCount, ScanStore.displayName(saved)),
                        Toast.LENGTH_LONG,
                    ).show()
                    startActivity(Intent(this, ViewerActivity::class.java).putExtra(EXTRA_PATH, saved.absolutePath))
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progress.visibility = View.GONE
                    setExportEnabled(true)
                    binding.measureText.setText(R.string.measure_hint)
                    Toast.makeText(this, e.message ?: e.javaClass.simpleName, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setExportEnabled(enabled: Boolean) {
        binding.plyButton.isEnabled = enabled
        binding.objButton.isEnabled = enabled
    }

    private fun share(format: ScanStore.Format) {
        val m = mesh ?: return
        setExportEnabled(false)
        worker.execute {
            try {
                val intent = ScanStore.shareIntent(this, file, m, format)
                runOnUiThread { setExportEnabled(true); startActivity(intent) }
            } catch (e: Exception) {
                runOnUiThread {
                    setExportEnabled(true)
                    Toast.makeText(this, e.message ?: "Export failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun describe(m: MeshData): String {
        val b = m.bounds()
        val loc = Locale.getDefault()
        return String.format(
            loc, "%,d triangles · %.2f × %.2f × %.2f m (W×H×D) · %.1f m² surface",
            m.triangleCount, b[3] - b[0], b[4] - b[1], b[5] - b[2], m.surfaceArea(),
        )
    }

    // --- Measuring ---

    private fun onTap(x: Float, y: Float) {
        val rc = raycaster ?: return
        val ray = camera.rayThrough(x, y)
        worker.execute {
            val hit = rc.cast(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5]) ?: return@execute
            val current = markers
            markers = if (current.size >= 6) floatArrayOf(hit.x, hit.y, hit.z) else current + floatArrayOf(hit.x, hit.y, hit.z)
            val pts = markers
            runOnUiThread {
                binding.measureText.text = if (pts.size == 6) formatMeasurement(pts) else "Tap a second point…"
                binding.surfaceView.requestRender()
            }
        }
    }

    private fun clearMeasurement() {
        markers = FloatArray(0)
        binding.measureText.setText(R.string.measure_hint)
        binding.surfaceView.requestRender()
    }

    private fun formatMeasurement(p: FloatArray): String {
        val dx = p[3] - p[0]; val dy = p[4] - p[1]; val dz = p[5] - p[2]
        val total = sqrt(dx * dx + dy * dy + dz * dz)
        val horizontal = sqrt(dx * dx + dz * dz)
        return "${formatLength(total)}\nhorizontal ${formatLength(horizontal)} · vertical ${formatLength(abs(dy))}"
    }

    private fun formatLength(m: Float): String =
        if (m < 1f) String.format(Locale.getDefault(), "%.1f cm", m * 100f)
        else String.format(Locale.getDefault(), "%.2f m", m)

    // --- Gestures: 1 finger rotates, 2 fingers pan, pinch zooms, tap measures ---

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                camera.zoom(detector.scaleFactor)
                binding.surfaceView.requestRender()
                return true
            }
        })
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTap(e.x, e.y)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e2.pointerCount >= 2) camera.pan(-distanceX, -distanceY) else camera.rotate(-distanceX, -distanceY)
                binding.surfaceView.requestRender()
                return true
            }
        })
        binding.surfaceView.setOnTouchListener { _, event ->
            scale.onTouchEvent(event)
            gestures.onTouchEvent(event)
            true
        }
    }

    // --- GLSurfaceView.Renderer ---

    private var uploaded: MeshData? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.11f, 0.12f, 0.14f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        meshRenderer.createOnGlThread()
        markerRenderer.createOnGlThread()
        uploaded = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        camera.setViewport(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val m = mesh ?: return
        if (uploaded !== m) {
            meshRenderer.clear()
            meshRenderer.sync(mapOf(0L to m))
            uploaded = m
        }
        camera.viewProjection(vp)
        meshRenderer.draw(vp, shadeMode, alpha = 1f, lightDir = light)
        markerRenderer.draw(vp, markers, connect = true, pointSizePx = pointSizePx)
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
