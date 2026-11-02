package node.nveloper.edge.system

import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.annotation.StringRes
import node.nveloper.edge.R

/**
 * Many vendors kill background apps beyond what stock Android does (see dontkillmyapp.com).
 * This picks the steps and settings screens for the device the app is running on;
 * unknown vendors get the stock Android path only.
 */
data class OemPowerGuide(
    /** Vendor/skin shown to the user, e.g. "Samsung One UI". */
    val vendor: String,
    /** What the user has to switch on, in the order the vendor's settings present it. */
    val steps: List<Step>,
    val infoUrl: String?,
) {
    /** How a step's current state can be read back, if at all. */
    enum class Check {
        /** Exempt from battery optimization and not background-restricted. */
        BATTERY_UNRESTRICTED,
        /** Xiaomi autostart permission. */
        AUTOSTART,
    }

    data class Step(
        @StringRes val label: Int,
        /** Settings screens to try, most specific first; empty when there is nothing to open. */
        val screens: List<Screen> = emptyList(),
        /** Null when the state can't be read (the user has to check it themselves). */
        val check: Check? = null,
    )

    /** [withPackage] passes this app's package/label, which opens the per-app page directly. */
    data class Screen(val component: ComponentName, val withPackage: Boolean = false)

    val isVendorSpecific: Boolean get() = steps.any { step -> step.screens.isNotEmpty() }

    fun title(context: Context): String =
        if (isVendorSpecific) context.getString(R.string.oem_title, vendor) else context.getString(R.string.oem_title_generic)

    /** State of [step] from a device snapshot: true/false, or null when it can't be read. */
    fun state(step: Step, device: DeviceSnapshot): Boolean? = when (step.check) {
        Check.BATTERY_UNRESTRICTED -> device.ignoringBatteryOptimizations && !device.backgroundRestricted
        Check.AUTOSTART -> device.autostartAllowed
        null -> null
    }

    companion object {
        fun forThisDevice(): OemPowerGuide = forManufacturer(Build.MANUFACTURER)

        fun forManufacturer(manufacturer: String): OemPowerGuide = when (manufacturer.lowercase()) {
            "xiaomi", "redmi", "poco" -> OemPowerGuide(
                vendor = "Xiaomi HyperOS/MIUI",
                steps = listOf(
                    Step(
                        R.string.step_xiaomi_autostart,
                        listOf(Screen(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))),
                        Check.AUTOSTART,
                    ),
                    Step(
                        R.string.step_xiaomi_battery,
                        listOf(Screen(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"), withPackage = true)),
                        Check.BATTERY_UNRESTRICTED,
                    ),
                    Step(R.string.step_xiaomi_lock),
                ),
                infoUrl = "https://dontkillmyapp.com/xiaomi",
            )
            "samsung" -> {
                val battery = listOf(
                    Screen(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
                    Screen(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity")),
                )
                OemPowerGuide(
                    vendor = "Samsung One UI",
                    steps = listOf(
                        Step(R.string.step_generic_battery, check = Check.BATTERY_UNRESTRICTED),
                        Step(R.string.step_samsung_never_sleep, battery),
                        Step(R.string.step_samsung_unused, battery),
                    ),
                    infoUrl = "https://dontkillmyapp.com/samsung",
                )
            }
            "huawei", "honor" -> OemPowerGuide(
                vendor = "Huawei/Honor",
                steps = listOf(
                    Step(
                        R.string.step_huawei_manual,
                        listOf(
                            Screen(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                            Screen(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
                            Screen(ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                        ),
                    ),
                    Step(R.string.step_generic_battery, check = Check.BATTERY_UNRESTRICTED),
                ),
                infoUrl = "https://dontkillmyapp.com/huawei",
            )
            "oppo", "realme", "oneplus" -> OemPowerGuide(
                vendor = "ColorOS/OxygenOS",
                steps = listOf(
                    Step(
                        R.string.step_oppo_background,
                        listOf(Screen(ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"))),
                        Check.BATTERY_UNRESTRICTED,
                    ),
                    Step(
                        R.string.step_allow_autostart,
                        listOf(Screen(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))),
                    ),
                ),
                infoUrl = "https://dontkillmyapp.com/${manufacturer.lowercase()}",
            )
            "vivo", "iqoo" -> OemPowerGuide(
                vendor = "vivo OriginOS/Funtouch",
                steps = listOf(
                    Step(R.string.step_vivo_background, check = Check.BATTERY_UNRESTRICTED),
                    Step(
                        R.string.step_allow_autostart,
                        listOf(
                            Screen(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                            Screen(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
                        ),
                    ),
                ),
                infoUrl = "https://dontkillmyapp.com/vivo",
            )
            else -> OemPowerGuide(
                vendor = manufacturer.replaceFirstChar { it.uppercase() },
                steps = listOf(Step(R.string.step_generic_battery, check = Check.BATTERY_UNRESTRICTED)),
                infoUrl = null,
            )
        }

        /** Opens the first of [screens] that exists, falling back to the app's system settings page. */
        fun open(context: Context, screens: List<Screen>) {
            for (screen in screens) {
                val intent = Intent().setComponent(screen.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (screen.withPackage) {
                    intent.putExtra("package_name", context.packageName)
                    intent.putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager).toString())
                }
                try {
                    context.startActivity(intent)
                    return
                } catch (_: ActivityNotFoundException) {
                } catch (_: SecurityException) {
                }
            }
            openAppDetails(context)
        }

        fun openAppDetails(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /** MIUI/HyperOS keeps autostart in a private app-op; null where it doesn't exist or can't be read. */
        private const val OP_MIUI_AUTOSTART = 10008

        fun autostartAllowed(context: Context): Boolean? {
            if (Build.MANUFACTURER.lowercase() !in setOf("xiaomi", "redmi", "poco")) return null
            return try {
                val ops = context.getSystemService(AppOpsManager::class.java)
                val check = AppOpsManager::class.java.getMethod(
                    "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
                )
                when (check.invoke(ops, OP_MIUI_AUTOSTART, Process.myUid(), context.packageName) as Int) {
                    AppOpsManager.MODE_ALLOWED -> true
                    AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
