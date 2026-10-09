package com.autobuy.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.autobuy.app.core.Permissions

data class PermissionState(
    val accessibility: Boolean,
    val overlay: Boolean
) {
    val ready: Boolean get() = accessibility
}

/** 读取无障碍/悬浮窗权限，并在回到前台时刷新。 */
@Composable
fun rememberPermissionState(): PermissionState {
    val context = LocalContext.current
    var accessibility by remember { mutableStateOf(Permissions.isAccessibilityEnabled(context)) }
    var overlay by remember { mutableStateOf(Permissions.canDrawOverlays(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibility = Permissions.isAccessibilityEnabled(context)
                overlay = Permissions.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return PermissionState(accessibility, overlay)
}
