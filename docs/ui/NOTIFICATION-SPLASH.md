# Notification launch: misplaced startup icon

On Android 12+, the system may provide a splash screen without an icon when opening a notification. In `androidx.core:core-splashscreen:1.2.0`, `SplashScreenViewProvider.ViewImpl31.iconView` returns `View(activity)` when the platform icon is absent. That fallback is unattached and has no measured size or position.

The old handoff read its `(0, 0)` location, invented a 240 dp size and drew the custom startup animation in the top-left corner. The handoff now requires a real attached icon. Missing icons dismiss the system splash directly. Valid icon bounds are read after layout, before removal, and converted from screen coordinates into the overlay root. Hidden, empty, detached, foreign-window and out-of-bounds icons cannot start the overlay. Detaching before the first draw still releases the system splash once.

Launcher animation artwork, timing and trajectory are unchanged. MainActivity and LoginActivity share the corrected handoff; notification routing is unchanged.

`StartupLogoAnimationTest` uses Robolectric API 32 to check absent icons, root offsets, invalid geometry, removal before drawing, valid handoff, cancellation and cleanup. Existing choreography and reminder/startup tests are also run. These are local lifecycle/geometry tests, not GPU or device acceptance. The same-device notification reproduction and normal launcher animation still require the deferred device check. No APK is built or published by this change.

Upstream evidence: [core-splashscreen 1.2.0 sources](https://dl.google.com/dl/android/maven2/androidx/core/core-splashscreen/1.2.0/core-splashscreen-1.2.0-sources.jar), `androidx/core/splashscreen/SplashScreenViewProvider.kt`.
