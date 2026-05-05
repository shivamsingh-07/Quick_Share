package com.quickshare.tv.system

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * Single source of truth for SDK-version branching. All [Build.VERSION.SDK_INT]
 * checks in the codebase route through here so they're easy to audit and to
 * later compile-time-erase as `minSdk` rises.
 *
 * Each predicate is annotated with [ChecksSdkIntAtLeast] so Android Lint
 * understands that calls guarded by these getters are safe — no need to
 * sprinkle `@SuppressLint("NewApi")` at the call sites.
 *
 * The constants are wrapped in `inline val` getters so R8 can fold
 * them into constants at the call site during release builds.
 */
object SdkCompat {

    /** Android 11.0 — our minimum target. Everything `≤ R` is unconditionally available. */
    const val R        = Build.VERSION_CODES.R                  // 30

    /** Android 12 — exported flag mandatory, FGS restrictions tighten. */
    const val S        = Build.VERSION_CODES.S                  // 31

    /** Android 13 — runtime POST_NOTIFICATIONS, granular media perms. */
    const val TIRAMISU = Build.VERSION_CODES.TIRAMISU           // 33

    /** Android 14 — FGS type permission, NSD `host` deprecation. */
    const val U        = Build.VERSION_CODES.UPSIDE_DOWN_CAKE   // 34

    @get:ChecksSdkIntAtLeast(api = S)
    inline val atLeastS:        Boolean get() = Build.VERSION.SDK_INT >= S

    @get:ChecksSdkIntAtLeast(api = TIRAMISU)
    inline val atLeastTiramisu: Boolean get() = Build.VERSION.SDK_INT >= TIRAMISU

    @get:ChecksSdkIntAtLeast(api = U)
    inline val atLeastU:        Boolean get() = Build.VERSION.SDK_INT >= U
}
