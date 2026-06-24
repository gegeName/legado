package io.legado.app.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.provider.Settings
import android.view.Display
import splitties.init.appCtx
import splitties.systemservices.displayManager
import splitties.systemservices.powerManager


@Suppress("unused")
object SystemUtils {

    @SuppressLint("ObsoleteSdkInt")
    fun ignoreBatteryOptimization(activity: Activity) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return

        val hasIgnored = powerManager.isIgnoringBatteryOptimizations(activity.packageName)
        //  判断当前APP是否有加入电池优化的白名单，如果没有，弹出加入电池优化的白名单的设置对话框。
        if (!hasIgnored) {
            try {
                @SuppressLint("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:" + activity.packageName)
                activity.startActivity(intent)
            } catch (ignored: Throwable) {
            }

        }
    }

    fun isScreenOn(): Boolean {
        return displayManager.displays.filterNotNull().any {
            it.state != Display.STATE_OFF
        }
    }

    fun appNotificationSettingsIntent(context: Context): Intent {
        val packageName = context.packageName
        val manufacturer = Build.MANUFACTURER.lowercase()
        val candidates = buildList {
            when {
                manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
                    add(
                        Intent("miui.intent.action.APP_PERM_EDITOR")
                            .setClassName(
                                "com.miui.securitycenter",
                                "com.miui.permcenter.permissions.PermissionsEditorActivity"
                            )
                            .putExtra("extra_pkgname", packageName)
                    )
                    add(
                        Intent("miui.intent.action.APP_PERM_EDITOR")
                            .setClassName(
                                "com.miui.securitycenter",
                                "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
                            )
                            .putExtra("extra_pkgname", packageName)
                    )
                }

                manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                    add(
                        Intent().setComponent(
                            ComponentName(
                                "com.huawei.systemmanager",
                                "com.huawei.notificationmanager.ui.NotificationManagmentActivity"
                            )
                        ).putExtra("packageName", packageName)
                    )
                    add(
                        Intent().setComponent(
                            ComponentName(
                                "com.huawei.systemmanager",
                                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                            )
                        )
                    )
                }

                manufacturer.contains("oppo") || manufacturer.contains("realme")
                        || manufacturer.contains("oneplus") -> {
                    add(
                        Intent().setComponent(
                            ComponentName(
                                "com.coloros.notificationmanager",
                                "com.coloros.notificationmanager.AppDetailPreferenceActivity"
                            )
                        ).putExtra("packageName", packageName)
                    )
                    add(
                        Intent().setComponent(
                            ComponentName(
                                "com.coloros.safecenter",
                                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                            )
                        )
                    )
                }

                manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                    add(
                        Intent().setComponent(
                            ComponentName(
                                "com.vivo.permissionmanager",
                                "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity"
                            )
                        ).putExtra("packagename", packageName)
                    )
                    add(
                        Intent().setComponent(
                            ComponentName(
                                "com.vivo.permissionmanager",
                                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                            )
                        )
                    )
                }

                manufacturer.contains("meizu") -> {
                    add(
                        Intent("com.meizu.safe.security.SHOW_APPSEC")
                            .setClassName(
                                "com.meizu.safe",
                                "com.meizu.safe.security.AppSecActivity"
                            )
                            .putExtra("packageName", packageName)
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
            add(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }
        return candidates.firstOrNull {
            it.resolveActivity(context.packageManager) != null
        }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * 屏幕像素宽度
     */
    val screenWidthPx by lazy {
        appCtx.resources.displayMetrics.widthPixels
    }

    /**
     * 屏幕像素高度
     */
    val screenHeightPx by lazy {
        appCtx.resources.displayMetrics.heightPixels
    }
}
