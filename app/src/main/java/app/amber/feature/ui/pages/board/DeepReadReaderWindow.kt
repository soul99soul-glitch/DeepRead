package app.amber.feature.ui.pages.board

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
internal fun DeepReadImmersiveWindowEffect(darkTheme: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, darkTheme) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val previousStatusBarColor = window.statusBarColor
            val previousNavigationBarColor = window.navigationBarColor
            val previousSoftInputMode = window.attributes.softInputMode
            val previousCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode
            } else {
                null
            }
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            controller.apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                show(WindowInsetsCompat.Type.statusBars())
                hide(WindowInsetsCompat.Type.navigationBars())
            }
            onDispose {
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.statusBarColor = previousStatusBarColor
                window.navigationBarColor = previousNavigationBarColor
                window.attributes = window.attributes.apply {
                    softInputMode = previousSoftInputMode
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && previousCutoutMode != null) {
                        layoutInDisplayCutoutMode = previousCutoutMode
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
