package com.cameramapper3d.app

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** Pads views away from the status/navigation bars (Android 15 draws apps edge to edge). */
object Insets {
    fun applyBars(top: View?, bottom: View?) {
        top?.let { v ->
            val base = v.paddingTop
            ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.updatePadding(top = base + bars.top)
                insets
            }
        }
        bottom?.let { v ->
            val base = v.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(bottom = base + bars.bottom)
                insets
            }
        }
    }
}
