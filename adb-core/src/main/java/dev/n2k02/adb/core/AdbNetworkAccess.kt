package dev.n2k02.adb.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 17 (API 37) gates localhost/mDNS discovery and local networking for
 * targets 37+. The host Activity requests the OS grant; the connection engine
 * never assumes that a manifest declaration is equivalent to consent.
 */
object AdbNetworkAccess {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    fun requiresPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 37 && context.applicationInfo.targetSdkVersion >= 37

    fun granted(context: Context): Boolean =
        !requiresPermission(context) ||
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun blocker(context: Context): String? =
        if (granted(context)) null
        else "Nearby devices permission required for automatic local ADB discovery. Allow it in Android app permissions."
}
