package app.transferx.android

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.transferx.core.app.TransferXNode
import com.transferx.core.transfer.LocalFileSource
import com.transferx.core.transfer.SourceResolver
import com.transferx.core.util.Log
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Paths

class TransferXApplication : Application() {
    lateinit var node: TransferXNode
        private set
    lateinit var bluetooth: AndroidBluetooth
        private set

    override fun onCreate() {
        super.onCreate()
        Log.sink = com.transferx.core.util.LogSink { level, tag, message, _ ->
            android.util.Log.println(
                when (level) {
                    com.transferx.core.util.LogLevel.DEBUG -> android.util.Log.DEBUG
                    com.transferx.core.util.LogLevel.INFO -> android.util.Log.INFO
                    com.transferx.core.util.LogLevel.WARN -> android.util.Log.WARN
                    com.transferx.core.util.LogLevel.ERROR -> android.util.Log.ERROR
                },
                "TransferX/$tag", message,
            )
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        node = TransferXNode(
            dataDir = filesDir.toPath().resolve("transferx"),
            platform = "android",
            defaultName = defaultDeviceName(),
            defaultDestination = defaultDestination(),
            sourceResolver = SourceResolver { ref ->
                when (ref.kind) {
                    "content" -> ContentUriSource.from(contentResolver, Uri.parse(ref.location), ref.relativeDir)
                    "file" -> runCatching { LocalFileSource(Paths.get(ref.location), ref.relativeDir) }.getOrNull()
                    else -> null
                }
            },
            // Android drops multicast/broadcast packets for apps that don't hold this lock.
            acquireMulticastLock = {
                val lock = wifi.createMulticastLock("transferx-discovery").apply { setReferenceCounted(false); acquire() }
                AutoCloseable { if (lock.isHeld) lock.release() }
            },
            extraTransports = if (packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) listOf("bluetooth") else emptyList(),
        )
        node.engine.onFileReceived = { path, mime ->
            // Make received photos/videos/music visible in gallery and media apps.
            MediaScannerConnection.scanFile(this, arrayOf(path.toString()), arrayOf(mime), null)
        }
        node.start()
        bluetooth = AndroidBluetooth(this, node.engine)

        // Run a foreground service while anything is transferring, so Android doesn't
        // suspend or kill the process mid-transfer when the app is in the background.
        node.scope.launch {
            node.engine.sessions.map { m -> m.values.count { it.state.live } }.distinctUntilChanged().collect { live ->
                if (live > 0) TransferService.start(this@TransferXApplication) else TransferService.stop(this@TransferXApplication)
            }
        }
    }

    private fun defaultDeviceName(): String {
        val user = runCatching { Settings.Global.getString(contentResolver, "device_name") }.getOrNull()
        return user?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    }

    /**
     * Download/TransferX where the app may write there with plain file APIs (Android 11+,
     * Android 10 with legacy storage, or older versions with the storage permission);
     * otherwise the app's own external files folder, which is always writable.
     */
    private fun defaultDestination(): java.nio.file.Path {
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TransferX")
        val canUsePublic = when {
            Build.VERSION.SDK_INT >= 30 -> true
            Build.VERSION.SDK_INT == 29 -> Environment.isExternalStorageLegacy()
            else -> ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (canUsePublic && (publicDir.isDirectory || publicDir.mkdirs())) return publicDir.toPath()
        return File(getExternalFilesDir(null) ?: filesDir, "Received").apply { mkdirs() }.toPath()
    }

    fun appSpecificDestination(): File = File(getExternalFilesDir(null) ?: filesDir, "Received").apply { mkdirs() }

    fun publicDestination(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TransferX")
}
