package node.nveloper.edge.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import node.nveloper.edge.EdgeApplication
import node.nveloper.edge.system.OemPowerGuide
import node.nveloper.edge.ui.theme.EdgeTheme

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val modelPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) (application as EdgeApplication).container.models.importFrom(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val container = (application as EdgeApplication).container
        val actions = PlatformActions(
            requestBatteryExemption = ::requestBatteryExemption,
            openPowerScreens = { screens -> OemPowerGuide.open(this, screens) },
            copyToClipboard = ::copyToClipboard,
            // .litertlm has no registered MIME type, so accept anything and validate the name on import.
            pickModelFile = { modelPicker.launch(arrayOf("*/*")) },
            openUrl = { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
        )
        setContent {
            EdgeTheme {
                EdgeApp(container, actions)
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Edge", text))
    }
}
