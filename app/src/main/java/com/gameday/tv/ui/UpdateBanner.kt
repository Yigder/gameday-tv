package com.gameday.tv.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.gameday.tv.data.AppUpdate
import com.gameday.tv.data.Device
import com.gameday.tv.data.UpdateProgress
import com.gameday.tv.ui.theme.AppColors

/** The banner's two lines for where the update has got to (shared by the TV and phone banners). */
fun updateText(u: AppUpdate, progress: UpdateProgress): Pair<String, String> {
    val app = Device.appName
    return when (progress) {
        is UpdateProgress.Downloading -> "Downloading $app ${u.version}…" to (progress.fraction?.let { "${(it * 100).toInt()}%" } ?: "Starting")
        UpdateProgress.Installing -> "Installing $app ${u.version}" to "Confirm in Android's installer"
        is UpdateProgress.Failed -> "Update didn't finish" to "${progress.message}. Try again."
        UpdateProgress.NeedsPermission -> "Allow $app to install updates" to "Turn on \"Allow from this source\", then come back"
        UpdateProgress.Idle -> "$app ${u.version} is available" to "Update now. Your account, profiles and recordings stay."
    }
}

/**
 * What the banner's Update button does: downloads and installs the update, asking once for
 * permission to install apps. A release without the APK opens its page instead.
 */
@Composable
fun rememberUpdateAction(vm: AppViewModel, u: AppUpdate): () -> Unit {
    val uri = LocalUriHandler.current
    // Back from "Install unknown apps": carry on straight away if it was allowed.
    val allow = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (vm.canInstallUpdates()) vm.installUpdate()
    }
    fun askPermission() {
        runCatching { allow.launch(vm.updatePermissionIntent()) }
            .onFailure { vm.showMessage("Allow ${Device.appName} to install unknown apps in Android Settings, then try again") }
    }
    LaunchedEffect(vm.updateProgress) {
        if (vm.updateProgress is UpdateProgress.NeedsPermission) askPermission()
    }
    return {
        when {
            !u.isApk -> runCatching { uri.openUri(u.url) }.onFailure { vm.showMessage("Get ${Device.appName} ${u.version} from github.com/Yigder/gameday-tv") }
            vm.updateProgress is UpdateProgress.NeedsPermission -> askPermission()
            else -> vm.installUpdate()
        }
    }
}

/**
 * "A new GameDay TV is out" at the top of Sports, with Update and Not now. The Update button stays
 * while it downloads so focus has somewhere to be; [onDismissed] moves focus on once the banner goes.
 */
@Composable
fun UpdateBanner(vm: AppViewModel, onDismissed: () -> Unit) {
    LaunchedEffect(Unit) { vm.checkForUpdate() }
    val u = vm.update ?: return
    val progress = vm.updateProgress
    val start = rememberUpdateAction(vm, u)
    val busy = progress is UpdateProgress.Downloading || progress is UpdateProgress.Installing
    val (title, detail) = updateText(u, progress)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 48.dp, bottom = 18.dp)
            .background(AppColors.Raised, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Download, null, Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp)
            Text(detail, fontSize = 13.sp, color = AppColors.TextDim)
        }
        Spacer(Modifier.width(16.dp))
        val label = when {
            busy -> "Updating…"
            progress is UpdateProgress.Failed -> "Try again"
            else -> "Update"
        }
        PillButton(label, { if (!busy) start() }, primary = true)
        if (!busy) {
            Spacer(Modifier.width(10.dp))
            PillButton("Not now", { vm.dismissUpdate(); onDismissed() })
        }
    }
}
