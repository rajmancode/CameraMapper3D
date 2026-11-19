package com.cameramapper3d.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cameramapper3d.app.databinding.ActivityMainBinding
import java.io.File

/** Lists saved scans and starts new ones. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val adapter = ScanAdapter { file ->
        startActivity(Intent(this, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, file.absolutePath))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.applyBars(top = binding.toolbar, bottom = binding.bottomBar)

        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { null }
        binding.appTitle.text = if (version != null) getString(R.string.app_title_version, version) else getString(R.string.app_name)

        binding.scanList.layoutManager = LinearLayoutManager(this)
        binding.scanList.adapter = adapter

        val labels = DETAIL_LEVELS.map { it.label }
        binding.detailPicker.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        var selected = prefs.getInt("detail", 1).coerceIn(DETAIL_LEVELS.indices)
        binding.detailPicker.setText(labels[selected], false)
        binding.detailPicker.setOnItemClickListener { _, _, position, _ ->
            selected = position
            prefs.edit { putInt("detail", position) }
        }

        binding.newScanButton.setOnClickListener {
            startActivity(
                Intent(this, ScanActivity::class.java)
                    .putExtra(ScanActivity.EXTRA_VOXEL_SIZE, DETAIL_LEVELS[selected].voxelSize),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val scans = ScanStore.list(this)
        adapter.submit(scans)
        binding.emptyText.visibility = if (scans.isEmpty()) View.VISIBLE else View.GONE
    }

    data class DetailLevel(val label: String, val voxelSize: Float)

    companion object {
        val DETAIL_LEVELS = listOf(
            DetailLevel("Fine · 1.5 cm (objects, small rooms)", 0.015f),
            DetailLevel("Standard · 2 cm (rooms)", 0.02f),
            DetailLevel("Large site · 4 cm (buildings, outdoors)", 0.04f),
        )
    }

    private class ScanAdapter(private val onClick: (File) -> Unit) : RecyclerView.Adapter<ScanAdapter.Holder>() {
        private var items: List<File> = emptyList()

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.title)
            val subtitle: TextView = view.findViewById(R.id.subtitle)
        }

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(files: List<File>) {
            items = files
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_scan, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val f = items[position]
            holder.title.text = ScanStore.displayName(f)
            holder.subtitle.text = Formatter.formatShortFileSize(holder.itemView.context, f.length())
            holder.itemView.setOnClickListener { onClick(f) }
        }
    }
}
