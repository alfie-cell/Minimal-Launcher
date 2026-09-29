package dev.minimal.launcher

import android.content.ComponentName
import android.content.pm.LauncherActivityInfo
import android.os.UserHandle
import dev.minimal.launcher.search.SearchKey

/** One launchable activity, for one user profile. Immutable. */
class AppEntry(
    /** Stable id: "package/.Activity#userSerial". */
    val key: String,
    val info: LauncherActivityInfo,
    val label: String,
    val isWork: Boolean,
) {
    val component: ComponentName get() = info.componentName
    val user: UserHandle get() = info.user
    val packageName: String get() = info.componentName.packageName
    val searchKey = SearchKey(label)
}
