package com.whyfi.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.whyfi.app.scan.ScanForegroundService
import com.whyfi.app.scan.ScanUiState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Binds to [ScanForegroundService] for as long as the caller is composed, and
 * returns a handle to it (null until the binding lands, and again if the
 * service dies).
 *
 * Bound, not started. Binding only gives the UI a live view of whatever the
 * service is doing; the service is independently *started* when a scan is
 * triggered (see [ScanForegroundService.start]) so it survives the app being
 * backgrounded. Getting that distinction wrong is the bug the foreground
 * service was introduced to fix — see AGENT.md.
 *
 * **Call this exactly once, from `WhyfiApp`, and pass the result down.** It
 * used to be called per-screen, which was quietly unsafe: the service calls
 * `stopSelf()` once it goes idle, and a stopped service is destroyed the
 * moment its last client unbinds. Screens binding individually leave a gap
 * with zero clients on every tab switch — long enough for the service, and
 * with it the retained passes and the survey tally, to be torn down and
 * recreated empty. One binding at the root has no such gap.
 */
@Composable
fun rememberScanService(): ScanForegroundService? {
    val context = LocalContext.current
    val serviceState = remember { mutableStateOf<ScanForegroundService?>(null) }
    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                serviceState.value = (binder as ScanForegroundService.LocalBinder).getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceState.value = null
            }
        }
    }

    DisposableEffect(Unit) {
        context.bindService(
            Intent(context, ScanForegroundService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        onDispose { context.unbindService(connection) }
    }

    return serviceState.value
}

/**
 * The service's live [ScanUiState], falling back to a default while the
 * binding is still in flight so screens never have to render a null state.
 */
@Composable
fun rememberScanState(service: ScanForegroundService?): State<ScanUiState> {
    val fallback = remember { MutableStateFlow(ScanUiState()) }
    return (service?.uiState ?: fallback).collectAsState()
}
