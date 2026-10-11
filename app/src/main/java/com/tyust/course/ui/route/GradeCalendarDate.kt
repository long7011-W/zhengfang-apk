package com.tyust.course.ui.route

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Calendar
import java.util.GregorianCalendar

internal data class GradeCalendarDate(val year: Int, val month: Int, val day: Int) {
    fun calendar(): Calendar = GregorianCalendar(year, month, day)

    companion object {
        fun from(calendar: Calendar) = GradeCalendarDate(calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
    }
}

/** Update local choices at midnight, clock/timezone changes and foreground return; no polling/network. */
@Composable
internal fun rememberGradeCalendarDate(now: () -> Calendar = { Calendar.getInstance() }): GradeCalendarDate {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clock by rememberUpdatedState(now)
    var date by remember { mutableStateOf(GradeCalendarDate.from(now())) }
    DisposableEffect(context, lifecycle) {
        fun refresh() { date = GradeCalendarDate.from(clock()) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { refresh() }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycle.addObserver(observer)
        refresh()
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return date
}
