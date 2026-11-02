package node.nveloper.edge.system

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.Inet4Address
import java.net.NetworkInterface

data class DeviceSnapshot(
    val model: String,
    val soc: String,
    val abis: String,
    val isEmulator: Boolean,
    val totalRamMb: Long,
    val availRamMb: Long,
    val batteryPercent: Int,
    val charging: Boolean,
    val powerSaveMode: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    /** "Restricted" background usage (Settings → App → Battery), which blocks foreground service starts. */
    val backgroundRestricted: Boolean,
    /** UsageStatsManager.STANDBY_BUCKET_*; RARE/RESTRICTED defer the watchdog for hours. */
    val standbyBucket: Int,
    /** Vendor autostart permission (Xiaomi), null when the vendor has none or it can't be read. */
    val autostartAllowed: Boolean?,
    val interactive: Boolean,
    val thermalStatus: Int,
    /** Forecast 10 s ahead; 1.0 means throttling. NaN when unsupported (typical on emulators). */
    val thermalHeadroom: Float,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("model", model)
        put("soc", soc)
        put("abis", abis)
        put("is_emulator", isEmulator)
        put("total_ram_mb", totalRamMb)
        put("avail_ram_mb", availRamMb)
        put("battery_percent", batteryPercent)
        put("charging", charging)
        put("power_save_mode", powerSaveMode)
        put("ignoring_battery_optimizations", ignoringBatteryOptimizations)
        put("background_restricted", backgroundRestricted)
        put("standby_bucket", standbyBucket)
        put("autostart_allowed", autostartAllowed)
        put("screen_interactive", interactive)
        put("thermal_status", DeviceMonitor.thermalName(thermalStatus))
        put("thermal_headroom", if (thermalHeadroom.isNaN()) null else thermalHeadroom)
    }
}

class DeviceMonitor(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val activity = context.getSystemService(ActivityManager::class.java)
    private val usage = context.getSystemService(UsageStatsManager::class.java)

    fun thermalStatus(): Int = power.currentThermalStatus

    /**
     * True when available memory is close to the point where the low-memory killer starts
     * killing processes. The margin covers one more conversation's KV cache.
     */
    fun memoryLow(): Boolean {
        val mem = ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) }
        return mem.lowMemory || mem.availMem < mem.threshold + KV_MARGIN_BYTES
    }

    fun snapshot(): DeviceSnapshot {
        val mem = ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) }
        return DeviceSnapshot(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            soc = "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}",
            abis = Build.SUPPORTED_ABIS.joinToString(),
            isEmulator = isEmulator,
            totalRamMb = mem.totalMem / (1024 * 1024),
            availRamMb = mem.availMem / (1024 * 1024),
            batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            charging = battery.isCharging,
            powerSaveMode = power.isPowerSaveMode,
            ignoringBatteryOptimizations = power.isIgnoringBatteryOptimizations(context.packageName),
            backgroundRestricted = activity.isBackgroundRestricted,
            standbyBucket = usage.appStandbyBucket,
            autostartAllowed = OemPowerGuide.autostartAllowed(context),
            interactive = power.isInteractive,
            thermalStatus = power.currentThermalStatus,
            thermalHeadroom = power.getThermalHeadroom(10),
        )
    }

    companion object {
        private const val KV_MARGIN_BYTES = 768L * 1024 * 1024

        val isEmulator: Boolean by lazy {
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
                Build.PRODUCT.contains("sdk") || Build.FINGERPRINT.startsWith("generic")
        }

        fun thermalName(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN($status)"
        }

        fun ipv4Addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>() }
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }
}
