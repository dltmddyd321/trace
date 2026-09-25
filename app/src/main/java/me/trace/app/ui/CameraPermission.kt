package me.trace.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** 권한이 도달할 수 있는 상태. 거부 이후 어떻게 안내할지가 갈린다. */
enum class PermissionState { Granted, Denied, PermanentlyDenied }

data class CameraPermission(
    val state: PermissionState,
    val request: () -> Unit,
    val openSettings: () -> Unit,
)

/**
 * 카메라 권한을 요청하고 상태를 돌려준다.
 *
 * 앱 진입 시점이 아니라 **구도를 고른 직후** 이 화면에서 묻는다.
 * 왜 카메라가 필요한지 보여준 다음에 물어야 수락률이 높다.
 */
@Composable
fun rememberCameraPermission(): CameraPermission {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    var state by remember {
        mutableStateOf(
            if (context.hasCameraPermission()) PermissionState.Granted else PermissionState.Denied
        )
    }
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        state = when {
            granted -> PermissionState.Granted
            // 거부한 뒤에도 rationale을 보여줄 수 있으면 다시 물어볼 여지가 있다는 뜻이고,
            // 그마저 false면 시스템이 더는 묻지 않으므로 설정으로 보내야 한다.
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true ->
                PermissionState.Denied
            else -> PermissionState.PermanentlyDenied
        }
    }

    LaunchedEffect(Unit) {
        if (state != PermissionState.Granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    // 설정 화면에서 권한을 켜고 돌아오면 앱은 그걸 모른다. 복귀 시점에 다시 확인해야
    // 사용자가 허용하고 왔는데도 거부 안내가 남아 있는 상황을 막는다.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && context.hasCameraPermission()) {
                state = PermissionState.Granted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return CameraPermission(
        state = state,
        request = { launcher.launch(Manifest.permission.CAMERA) },
        openSettings = { context.openAppSettings() },
    )
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
    )
}
