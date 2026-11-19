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
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.cameramapper3d.app.databinding.ActivityViewerBinding
import com.cameramapper3d.app.databinding.DialogOptimiseBinding
import com.cameramapper3d.app.gl.MarkerRenderer
import com.cameramapper3d.app.gl.MeshRenderer
import com.cameramapper3d.app.gl.ShadeMode
import com.cameramapper3d.core.MeshData
import com.cameramapper3d.core.MeshRaycaster
import com.cameramapper3d.core.Ruler
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
    /** Points the user tapped (0, 1 or 2), and what is drawn: the ruler's ends and its tick marks. */
    @Volatile private var tapped = FloatArray(0)
    @Volatile private var markers = FloatArray(0)
    @Volatile private var rulerTicks = FloatArray(0)
    @Volatile private var rulerMode = Ruler.Mode.FREE
    /** Direction along the scan's main wall, 0° for the 15° ruler; null if no wall was found. */
    @Volatile private var wallHeading: Double? = null
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
        binding.rulerGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            rulerMode = when (id) {
                R.id.rulerLevel -> Ruler.Mode.LEVEL
                R.id.rulerVertical -> Ruler.Mode.VERTICAL
                R.id.rulerSnap -> Ruler.Mode.SNAP
                else -> Ruler.Mode.FREE
            }
            updateMeasurement()
        }
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
                wallHeading = WallStraightener.mainWallHeading(m)
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
        R.id.action_optimise -> {
            showOptimiseDialog()
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

    // --- Optimisation: the chosen post-processing steps, run together and saved as one new scan ---

    private data class OptimiseOptions(
        val clean: Boolean,
        val minPieceArea: Float,
        val flattenSelected: Boolean,
        val matchColour: Boolean,
        val straighten: Boolean,
        val snapDistance: Float,
        val squareCorners: Boolean,
    )

    /** Options waiting for the user to finish selecting surfaces. */
    private var pendingOptimise: OptimiseOptions? = null

    private fun showOptimiseDialog() {
        val m = mesh ?: return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val c = DialogOptimiseBinding.inflate(layoutInflater)
        c.cleanCheck.isChecked = prefs.getBoolean("opt_clean", true)
        c.flattenCheck.isChecked = prefs.getBoolean("opt_flatten", false)
        c.straightenCheck.isChecked = prefs.getBoolean("opt_straighten", false)
        c.matchColourOption.isChecked = prefs.getBoolean("opt_match_colour", false)
        c.squareOption.isChecked = prefs.getBoolean("opt_square", true)
        val area = prefs.getFloat("opt_min_area", 0.2f).coerceIn(0.05f, 2f)
        c.cleanSlider.value = 0.05f + Math.round((area - 0.05f) / 0.05f) * 0.05f
        c.snapSlider.value = prefs.getInt("opt_snap_cm", 4).coerceIn(1, 10).toFloat()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.optimise_title)
            .setView(c.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.optimise_run, null) // set below; its label depends on the choices
            .create()

        fun refresh() {
            c.cleanLabel.text = getString(R.string.optimise_clean_value, c.cleanSlider.value)
            c.snapLabel.text = getString(R.string.straighten_snap_value, c.snapSlider.value.toInt())
            setEnabledDeep(c.cleanOptions, c.cleanCheck.isChecked)
            setEnabledDeep(c.flattenOptions, c.flattenCheck.isChecked)
            setEnabledDeep(c.straightenOptions, c.straightenCheck.isChecked)
            c.squareOption.isEnabled = c.flattenCheck.isChecked || c.straightenCheck.isChecked
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                isEnabled = c.cleanCheck.isChecked || c.flattenCheck.isChecked || c.straightenCheck.isChecked
                setText(if (c.flattenCheck.isChecked) R.string.optimise_next else R.string.optimise_run)
            }
        }
        for (box in listOf(c.cleanCheck, c.flattenCheck, c.straightenCheck)) box.setOnCheckedChangeListener { _, _ -> refresh() }
        c.cleanSlider.addOnChangeListener { _, _, _ -> refresh() }
        c.snapSlider.addOnChangeListener { _, _, _ -> refresh() }

        dialog.setOnShowListener {
            refresh()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val o = OptimiseOptions(
                    clean = c.cleanCheck.isChecked,
                    minPieceArea = c.cleanSlider.value,
                    flattenSelected = c.flattenCheck.isChecked,
                    matchColour = c.matchColourOption.isChecked,
                    straighten = c.straightenCheck.isChecked,
                    snapDistance = c.snapSlider.value / 100f,
                    squareCorners = c.squareOption.isChecked,
                )
                prefs.edit {
                    putBoolean("opt_clean", o.clean); putBoolean("opt_flatten", o.flattenSelected)
                    putBoolean("opt_straighten", o.straighten); putBoolean("opt_match_colour", o.matchColour)
                    putBoolean("opt_square", o.squareCorners); putFloat("opt_min_area", o.minPieceArea)
                    putInt("opt_snap_cm", c.snapSlider.value.toInt())
                }
                dialog.dismiss()
                if (o.flattenSelected) {
                    pendingOptimise = o
                    enterSelectMode(o.matchColour)
                } else {
                    runOptimise(m, o, emptyList(), null)
                }
            }
        }
        dialog.show()
    }

    private fun setEnabledDeep(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.4f
        if (view is ViewGroup) for (i in 0 until view.childCount) setEnabledDeep(view.getChildAt(i), enabled)
    }

    /**
     * Runs the chosen steps in order: flatten the selected surfaces (selections refer to this mesh, so
     * this goes first), remove floating pieces, then straighten any remaining walls automatically.
     */
    private fun runOptimise(m: MeshData, o: OptimiseOptions, chosen: List<IntArray>, s: SurfaceSelector?) {
        processAndOpen(R.string.optimise_working, R.string.optimise_suffix, nothingText = R.string.optimise_nothing) {
            var current = m
            val done = ArrayList<String>()
            if (chosen.isNotEmpty() && s != null) {
                val r = s.flatten(chosen, squareCorners = o.squareCorners)
                current = r.mesh
                done += resources.getQuantityString(R.plurals.optimise_part_flatten, r.planes.size, r.planes.size)
            }
            if (o.clean) {
                val r = MeshCleaner.removeSmallPieces(current, o.minPieceArea)
                if (r.removedPieces > 0) {
                    current = r.mesh
                    done += resources.getQuantityString(R.plurals.optimise_part_clean, r.removedPieces, r.removedPieces)
                }
            }
            if (o.straighten) {
                val r = WallStraightener.process(current, WallStraightener.Options(snapDistance = o.snapDistance, squareCorners = o.squareCorners))
                if (r.planes.isNotEmpty()) {
                    current = r.mesh
                    done += resources.getQuantityString(R.plurals.optimise_part_straighten_walls, r.wallCount, r.wallCount) +
                        " and " + resources.getQuantityString(R.plurals.optimise_part_straighten_levels, r.levelCount, r.levelCount)
                }
            }
            if (done.isEmpty()) null else current to { name: String -> getString(R.string.optimise_done, done.joinToString(", "), name) }
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
        nothingText: Int,
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

    // --- Flattening selected surfaces ---

    private var selecting = false
    private var selector: SurfaceSelector? = null
    private val selections = ArrayList<IntArray>()
    @Volatile private var highlights: Map<Long, MeshData> = emptyMap()
    private val highlightColours = intArrayOf(0xF59E0B, 0x22D3EE, 0xE879F9, 0xA3E635, 0xF87171, 0x818CF8)

    private fun enterSelectMode(matchColour: Boolean) {
        if (mesh == null) return
        selecting = true
        binding.matchColourSwitch.isChecked = matchColour
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
            if (selections.isEmpty()) getString(R.string.select_hint) else resources.getQuantityString(R.plurals.select_count, selections.size, selections.size)
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
        val o = pendingOptimise ?: return
        val m = mesh ?: return
        exitSelectMode()
        runOptimise(m, o, chosen, s)
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
            val current = tapped
            tapped = if (current.size >= 6) floatArrayOf(hit.x, hit.y, hit.z) else current + floatArrayOf(hit.x, hit.y, hit.z)
            runOnUiThread { updateMeasurement() }
        }
    }

    private fun clearMeasurement() {
        tapped = FloatArray(0)
        updateMeasurement()
    }

    /** Applies the ruler mode to the tapped points and refreshes the reading and the drawing. */
    private fun updateMeasurement() {
        val p = tapped
        when (p.size) {
            6 -> {
                val m = Ruler.measure(p.copyOfRange(0, 3), p.copyOfRange(3, 6), rulerMode, wallHeading ?: 0.0)
                markers = m.start + m.end
                rulerTicks = Ruler.ticks(m)
                binding.measureText.text = describe(m)
            }
            3 -> {
                markers = p
                rulerTicks = FloatArray(0)
                binding.measureText.setText(R.string.measure_second)
            }
            else -> {
                markers = FloatArray(0)
                rulerTicks = FloatArray(0)
                binding.measureText.setText(R.string.measure_hint)
            }
        }
        binding.surfaceView.requestRender()
    }

    private fun describe(m: Ruler.Measurement): String = when (rulerMode) {
        Ruler.Mode.FREE -> getString(R.string.ruler_free_reading, formatLength(m.length), formatLength(m.horizontal), formatLength(m.vertical))
        Ruler.Mode.LEVEL -> getString(R.string.ruler_level_reading, formatLength(m.length))
        Ruler.Mode.VERTICAL -> getString(R.string.ruler_vertical_reading, formatLength(m.length))
        Ruler.Mode.SNAP -> {
            val e = Math.round(m.elevationDegrees)
            val elevation = when {
                e == 0 -> getString(R.string.ruler_angle_level)
                abs(e) == 90 -> getString(R.string.ruler_angle_vertical)
                e > 0 -> getString(R.string.ruler_angle_up, e)
                else -> getString(R.string.ruler_angle_down, -e)
            }
            val h = Math.round(m.headingDegrees)
            val heading = if (wallHeading != null) getString(R.string.ruler_heading_wall, h) else getString(R.string.ruler_heading, h)
            if (abs(e) == 90) getString(R.string.ruler_vertical_reading, formatLength(m.length))
            else getString(R.string.ruler_snap_reading, formatLength(m.length), elevation, heading)
        }
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
        markerRenderer.drawSegments(vp, rulerTicks)
        markerRenderer.draw(vp, markers, connect = true, pointSizePx = pointSizePx)
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
