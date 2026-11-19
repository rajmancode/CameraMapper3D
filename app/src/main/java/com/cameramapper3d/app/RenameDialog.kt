package com.cameramapper3d.app

import android.app.Activity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.cameramapper3d.app.databinding.DialogRenameBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Asks for a new name for a scan and renames its file. */
object RenameDialog {
    fun show(activity: Activity, scan: File, onRenamed: (File) -> Unit) {
        val content = DialogRenameBinding.inflate(activity.layoutInflater)
        content.nameInput.setText(ScanStore.displayName(scan))
        content.nameInput.selectAll()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.rename_title)
            .setView(content.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.rename, null) // set below so an invalid name keeps the dialog open
            .create()
        fun submit() {
            try {
                val renamed = ScanStore.rename(scan, content.nameInput.text?.toString().orEmpty())
                dialog.dismiss()
                onRenamed(renamed)
            } catch (e: IllegalArgumentException) {
                content.nameLayout.error = e.message
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            content.nameInput.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        content.nameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        content.nameInput.doAfterTextChanged { content.nameLayout.error = null }
        dialog.show()
    }
}
