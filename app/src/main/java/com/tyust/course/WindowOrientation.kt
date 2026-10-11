package com.tyust.course

import android.app.Activity

/** Size-qualified resources must be resolved by the activity, not the manifest parser. */
internal fun Activity.applyAdaptiveOrientation() {
    // Re-read after each system recreation so resizing between phone/tablet
    // configurations does not retain the previous window's orientation lock.
    val orientation = resources.getInteger(R.integer.main_orientation)
    if (requestedOrientation != orientation) requestedOrientation = orientation
}
