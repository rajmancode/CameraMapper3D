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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.cameramapper3d.app.databinding.ActivityViewerBinding
import com.cameramapper3d.app.databinding.DialogCleanBinding
import com.cameramapper3d.app.databinding.DialogStraightenBinding
import com.cameramapper3d.app.gl.MarkerRenderer
import com.cameramapper3d.app.gl.MeshRenderer
import com.cameramapper3d.app.gl.ShadeMode
import com.cameramapper3d.core.MeshData
import com.cameramapper3d.core.MeshRaycaster
import com.cameramapper3d.core.MeshCleaner
import com.cameramapper3d.core.SurfaceSelector
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
    private val highlightRenderer = MeshRenderer()
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
        binding.selectCancelButton.setOnClickListener { exitSelectMode() }
        binding.selectUndoButton.setOnClickListener {
            if (selections.isNotEmpty()) selections.removeAt(selections.size - 1)
            updateHighlights()
        }
        binding.selectFlattenButton.setOnClickListener { flattenSelected() }
        // Back leaves surface selection first, rather than closing the scan.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selecting) exitSelectMode() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
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
        android.R.id.home -> { if (selecting) exitSelectMode() else finish(); true }
        R.id.action_flatten -> {
            enterSelectMode()
            true
        }
        R.id.action_clean -> {
            showCleanDialog()
            true
        }
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
        processAndOpen(R.string.straighten_working, R.string.straighten_suffix) {
            val result = WallStraightener.process(m, options)
            if (result.planes.isEmpty()) null
            else result.mesh to { name: String -> getString(R.string.straighten_done, result.wallCount, result.levelCount, name) }
        }
    }

    /**
     * Runs [work] in the background. If it returns a mesh, saves it as a new scan named
     * "<this scan> <suffix>", shows the message it builds from that name, and opens the new scan.
     * If it returns null, there was nothing to change.
     */
    private fun processAndOpen(
        workingText: Int,
        suffix: Int,
        nothingText: Int = R.string.straighten_none,
        work: () -> Pair<MeshData, (String) -> String>?,
    ) {
        setExportEnabled(false)
        binding.progress.visibility = View.VISIBLE
        binding.measureText.setText(workingText)
        fun restore() {
            binding.progress.visibility = View.GONE
            setExportEnabled(true)
            binding.measureText.setText(R.string.measure_hint)
        }
        worker.execute {
            try {
                val outcome = work()
                if (outcome == null) {
                    runOnUiThread { restore(); Toast.makeText(this, nothingText, Toast.LENGTH_LONG).show() }
                    return@execute
                }
                val saved = ScanStore.saveDerived(this, outcome.first, file, getString(suffix))
                val message = outcome.second(ScanStore.displayName(saved))
                runOnUiThread {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, ViewerActivity::class.java).putExtra(EXTRA_PATH, saved.absolutePath))
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread { restore(); Toast.makeText(this, e.message ?: e.javaClass.simpleName, Toast.LENGTH_LONG).show() }
            }
        }
    }

    // --- Removing floating pieces ---

    private fun showCleanDialog() {
        val m = mesh ?: return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val content = DialogCleanBinding.inflate(layoutInflater)
        val saved = prefs.getFloat("clean_min_area", 0.2f).coerceIn(0.05f, 2f)
        content.cleanSlider.value = 0.05f + Math.round((saved - 0.05f) / 0.05f) * 0.05f
        fun label() { content.cleanLabel.text = getString(R.string.clean_value, content.cleanSlider.value) }
        label()
        content.cleanSlider.addOnChangeListener { _, _, _ -> label() }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clean_title)
            .setView(content.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clean_apply) { _, _ ->
                val minArea = content.cleanSlider.value
                prefs.edit { putFloat("clean_min_area", minArea) }
                processAndOpen(R.string.clean_working, R.string.clean_suffix, nothingText = R.string.clean_none) {
                    val result = MeshCleaner.removeSmallPieces(m, minArea)
                    if (result.removedPieces == 0) null
                    else result.mesh to { name: String -> getString(R.string.clean_done, result.removedPieces, result.removedArea, name) }
                }
            }
            .show()
    }

    // --- Flattening selected surfaces ---

    private var selecting = false
    private var selector: SurfaceSelector? = null
    private val selections = ArrayList<IntArray>()
    @Volatile private var highlights: Map<Long, MeshData> = emptyMap()
    private val highlightColours = intArrayOf(0xF59E0B, 0x22D3EE, 0xE879F9, 0xA3E635, 0xF87171, 0x818CF8)

    private fun enterSelectMode() {
        if (mesh == null) return
        selecting = true
        selections.clear()
        updateHighlights()
        binding.selectPanel.visibility = View.VISIBLE
        binding.bottomBar.visibility = View.GONE
        binding.measureText.visibility = View.GONE
        clearMeasurement()
    }

    private fun exitSelectMode() {
        selecting = false
        selections.clear()
        updateHighlights()
        binding.selectPanel.visibility = View.GONE
        binding.bottomBar.visibility = View.VISIBLE
        binding.measureText.visibility = View.VISIBLE
    }

    private fun selectAt(x: Float, y: Float) {
        val rc = raycaster ?: return
        val m = mesh ?: return
        val ray = camera.rayThrough(x, y)
        val matchColour = binding.matchColourSwitch.isChecked
        binding.selectStatus.setText(R.string.select_working)
        worker.execute {
            val hit = rc.cast(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5])
            val sel = if (hit == null) null else {
                val s = selector ?: SurfaceSelector(m).also { selector = it }
                s.select(hit.triangle, SurfaceSelector.Options(matchColour = matchColour)) to hit.triangle
            }
            runOnUiThread {
                if (!selecting) return@runOnUiThread
                if (sel != null) {
                    // Tapping an already selected surface unselects it.
                    val existing = selections.indexOfFirst { it.contains(sel.second) }
                    if (existing >= 0) selections.removeAt(existing) else selections += sel.first
                }
                updateHighlights()
            }
        }
    }

    private fun updateHighlights() {
        binding.selectStatus.text =
            if (selections.isEmpty()) getString(R.string.select_hint) else getString(R.string.select_count, selections.size)
        binding.selectFlattenButton.isEnabled = selections.isNotEmpty()
        binding.selectUndoButton.isEnabled = selections.isNotEmpty()
        val s = selector
        val current = selections.toList()
        if (s == null || current.isEmpty()) {
            highlights = emptyMap()
            binding.surfaceView.requestRender()
            return
        }
        worker.execute {
            highlights = current.mapIndexed { i, sel -> i.toLong() to s.highlight(sel, highlightColours[i % highlightColours.size]) }.toMap()
            binding.surfaceView.requestRender()
        }
    }

    private fun flattenSelected() {
        val s = selector ?: return
        val chosen = selections.toList()
        if (chosen.isEmpty()) return
        val square = binding.selectSquareSwitch.isChecked
        exitSelectMode()
        processAndOpen(R.string.flatten_working, R.string.flatten_suffix) {
            val result = s.flatten(chosen, squareCorners = square)
            result.mesh to { name: String -> getString(R.string.flatten_done, result.planes.size, name) }
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
                if (selecting) selectAt(e.x, e.y) else onTap(e.x, e.y)
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
        highlightRenderer.createOnGlThread()
        highlightRenderer.clear()
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
        highlightRenderer.sync(highlights)
        highlightRenderer.draw(vp, ShadeMode.COLOR, alpha = 0.6f, lightDir = light, drawOnTop = true)
        markerRenderer.draw(vp, markers, connect = true, pointSizePx = pointSizePx)
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
