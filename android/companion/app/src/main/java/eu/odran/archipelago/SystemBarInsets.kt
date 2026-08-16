package eu.odran.archipelago

import android.view.View
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Keeps interactive content clear of system bars and physical display cutouts. */
object SystemBarInsets {
    fun apply(window: Window, root: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val safe = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(
                initialLeft + safe.left,
                initialTop + safe.top,
                initialRight + safe.right,
                initialBottom + safe.bottom,
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
