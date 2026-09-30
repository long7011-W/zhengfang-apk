package com.tyust.course.ui.theme

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class StartupLogoAnimationTest {
    private val controller = Robolectric.buildActivity(Activity::class.java)
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private lateinit var icon: View

    @Before fun setup() {
        activity = controller.setup().visible().get()
        root = FrameLayout(activity)
        activity.setContentView(root)
        icon = View(activity)
        root.addView(icon, FrameLayout.LayoutParams(240, 240))
        root.layout(40, 80, 1040, 1680)
        icon.layout(380, 680, 620, 920)
        assertTrue(root.isAttachedToWindow)
        assertTrue(icon.isAttachedToWindow)
    }

    @After fun cleanup() { controller.pause().stop().destroy() }

    @Test fun missingNotificationIconRemovesSplashWithoutInventingATopLeftLogo() {
        // Mirrors core-splashscreen 1.2.0's fallback when platform iconView is null.
        val absent = View(activity)
        assertNull(StartupLogoAnimation.iconBounds(root, absent))
        var removed = 0
        StartupLogoAnimation.showOverlay(activity, root, absent) { removed++ }
        assertEquals(1, removed)
        assertEquals(1, root.childCount)
        assertEquals(1f, StartupLogoAnimation.contentProgress, 0f)
    }

    @Test fun realIconUsesItsMeasuredBoundsRelativeToTheTargetRoot() {
        assertEquals(Rect(380, 680, 620, 920), StartupLogoAnimation.iconBounds(root, icon))
        root.translationX = 30f
        root.translationY = 80f
        assertEquals(Rect(380, 680, 620, 920), StartupLogoAnimation.iconBounds(root, icon))
    }

    @Test fun hiddenEmptyAndOffscreenIconsNeverReceiveFallbackBounds() {
        icon.visibility = View.INVISIBLE
        assertNull(StartupLogoAnimation.iconBounds(root, icon))
        icon.visibility = View.VISIBLE; icon.alpha = 0f
        assertNull(StartupLogoAnimation.iconBounds(root, icon))
        icon.alpha = 1f; icon.layout(0, 0, 0, 0)
        assertNull(StartupLogoAnimation.iconBounds(root, icon))
        icon.layout(-10, 50, 230, 290)
        assertNull(StartupLogoAnimation.iconBounds(root, icon))
    }

    @Test fun removingSystemIconBeforeFirstDrawDoesNotStartAnOverlay() {
        var removed = 0
        StartupLogoAnimation.showOverlay(activity, root, icon) { removed++ }
        root.removeView(icon)
        root.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(1, removed)
        assertEquals(0, root.childCount)
        assertEquals(1f, StartupLogoAnimation.contentProgress, 0f)
    }

    @Test fun validHandoffAndTouchCancellationReleaseBothViewsExactlyOnce() {
        var removed = 0
        StartupLogoAnimation.showOverlay(activity, root, icon) { removed++; root.removeView(icon) }
        root.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(1, removed)
        assertEquals(1, root.childCount)
        assertEquals(0f, StartupLogoAnimation.contentProgress, 0f)
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 500f, 800f, 0)
        try { root.getChildAt(0).onTouchEvent(event) } finally { event.recycle() }
        assertEquals(0, root.childCount)
        assertEquals(1, removed)
        assertEquals(1f, StartupLogoAnimation.contentProgress, 0f)
    }

    @Test fun detachingOverlayBeforeItsFirstDrawStillReleasesSystemSplash() {
        var removed = 0
        StartupLogoAnimation.showOverlay(activity, root, icon) { removed++ }
        root.removeViewAt(root.childCount - 1)
        root.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(1, removed)
        assertEquals(1, root.childCount)
        assertEquals(1f, StartupLogoAnimation.contentProgress, 0f)
    }
}
