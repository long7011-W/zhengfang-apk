package com.tyust.course.academic.plugin

import android.os.Bundle
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.tyust.course.ui.system.GlassLoadingIndicator
import com.tyust.course.ui.system.SystemDialogButton
import kotlinx.coroutines.delay

@Composable
internal fun PluginAmapSurface(apiKey: String, point: PluginCoordinates.Point?, focusRevision: Int,
                              onChoose: (PluginCoordinates.Point) -> Unit, modifier: Modifier = Modifier) {
    var attempt by remember { mutableIntStateOf(0) }
    key(apiKey, attempt) { AmapSurface(apiKey, point, focusRevision, onChoose, { attempt++ }, modifier) }
}

@Composable
private fun AmapSurface(apiKey: String, point: PluginCoordinates.Point?, focusRevision: Int,
                        onChoose: (PluginCoordinates.Point) -> Unit, retry: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestChoose by rememberUpdatedState(onChoose)
    var loaded by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    val created = remember {
        runCatching {
            PluginAmap.initialize(context, apiKey)
            val createdView = object : TextureMapView(context) {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                    return super.dispatchTouchEvent(event)
                }
            }
            try { createdView.onCreate(Bundle()); createdView to createdView.map }
            catch (error: Throwable) { runCatching { createdView.onDestroy() }; throw error }
        }
    }
    val view = created.getOrNull()?.first
    val map = created.getOrNull()?.second
    DisposableEffect(view, lifecycle) {
        var disposed = false
        val resource = view?.let { PluginMapLifecycle({}, it::onResume, it::onPause, {}, it::onDestroy) }
        val observer = LifecycleEventObserver { _, _ ->
            resource?.update(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED), lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        val mapLoaded = AMap.OnMapLoadedListener { if (!disposed) { loaded = true; timedOut = false } }
        val clicked = AMap.OnMapClickListener { location ->
            if (!disposed) latestChoose(PluginCoordinates.convert(PluginCoordinates.Point(location.latitude, location.longitude), "GCJ02", "WGS84"))
        }
        map?.apply {
            addOnMapLoadedListener(mapLoaded); addOnMapClickListener(clicked)
            uiSettings.isZoomControlsEnabled = true
            uiSettings.isMyLocationButtonEnabled = false
            isMyLocationEnabled = false
            if (point == null) moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(35.0, 104.0), 4f))
        }
        lifecycle.addObserver(observer)
        resource?.update(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED), lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            disposed = true
            lifecycle.removeObserver(observer)
            map?.removeOnMapLoadedListener(mapLoaded); map?.removeOnMapClickListener(clicked)
            resource?.close()
        }
    }
    LaunchedEffect(view) { delay(15_000); if (!loaded) timedOut = true }
    LaunchedEffect(map, point, focusRevision) {
        map?.clear()
        point?.let {
            val gcj = PluginCoordinates.convert(it, "WGS84", "GCJ02")
            val location = LatLng(gcj.latitude, gcj.longitude)
            map?.addMarker(MarkerOptions().position(location))
            map?.moveCamera(CameraUpdateFactory.newLatLngZoom(location, 17f))
        }
    }
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).testTag("location-map")) {
        if (view != null) AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        if (!loaded) Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .94f)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
            val failed = created.isFailure || timedOut
            if (!failed) GlassLoadingIndicator()
            Text(if (created.isFailure) "高德地图初始化失败" else if (timedOut) "高德地图加载超时，搜索结果仍可选择" else "正在加载高德地图…")
            if (failed) SystemDialogButton(onClick = retry, primary = true) { Text("重新加载地图") }
        }
    }
}
