package app.transferx.android

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.transferx.core.transfer.FileSource
import com.transferx.core.transfer.SkippedEntry
import com.transferx.ui.AppController
import com.transferx.ui.BluetoothBridge
import com.transferx.ui.Capability
import com.transferx.ui.CapabilityStatus
import com.transferx.ui.PickResult
import com.transferx.ui.PlatformBridge
import com.transferx.ui.TransferXApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class MainActivity : ComponentActivity(), PlatformBridge {
    private val app get() = application as TransferXApplication
    private lateinit var controller: AppController

    private var pendingFiles: CompletableDeferred<List<Uri>>? = null
    private var pendingTree: CompletableDeferred<Uri?>? = null

    private val openDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pendingFiles?.complete(uris)
    }
    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        pendingTree?.complete(uri)
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        app.bluetooth.refresh()
    }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        app.bluetooth.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = AppController(app.node, this, lifecycleScope)
        app.bluetooth.enableRequester = { requestBluetooth() }
        askStartupPermissions()
        handleIntent(intent)
        setContent {
            BackHandler(enabled = true) { if (!controller.back()) finish() }
            TransferXApp(controller)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        app.bluetooth.refresh()
    }

    private fun askStartupPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }

    private fun requestBluetooth() {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        } else {
            runCatching { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        }
    }

    /** Deep links from a scanned QR code, and files shared from other apps. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { it.startsWith("transferx://") }?.let { link ->
                controller.pendingLink = link
                controller.showManualConnect = true
                if (controller.selection.isEmpty()) controller.toast("Choose files, then tap Connect and send")
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val uris = buildList {
                    @Suppress("DEPRECATION")
                    (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { add(it) }
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { addAll(it) }
                }
                if (uris.isNotEmpty()) controller.addPicked(sourcesFor(uris))
            }
        }
    }

    // ------------------------------------------------------------------ PlatformBridge

    override val isMobile = true
    override val supportsFolderSelection = true
    override val bluetooth: BluetoothBridge get() = app.bluetooth

    override val capabilities: List<Capability>
        get() = listOf(
            Capability("Wi-Fi / local network", CapabilityStatus.AVAILABLE, "Direct TCP connection secured with TLS. Works on any shared Wi-Fi, or on a hotspot one device creates."),
            Capability("Automatic discovery", CapabilityStatus.AVAILABLE, "UDP multicast + broadcast (holds a Wi-Fi multicast lock while open). Blocked on some guest networks; use the QR code instead."),
            Capability("QR code", CapabilityStatus.LIMITED, "Scan the receiver's QR code with the system camera app: it opens TransferX with the address and code filled in. No in-app scanner."),
            Capability("Bluetooth (RFCOMM)", if (app.bluetooth.state.value == com.transferx.ui.BluetoothState.UNSUPPORTED) CapabilityStatus.UNAVAILABLE else CapabilityStatus.AVAILABLE,
                "Android to Android only, with phones paired in system settings. Much slower than Wi-Fi (typically under 0.3 MB/s); best for small files."),
            Capability("Wi-Fi Direct / local-only hotspot", CapabilityStatus.UNAVAILABLE, "Not implemented. Turn on the system hotspot on one phone, connect the other device to it, then use TransferX as usual."),
        )

    override suspend fun pickFiles(): PickResult {
        val d = CompletableDeferred<List<Uri>>()
        pendingFiles = d
        openDocuments.launch(arrayOf("*/*"))
        return sourcesFor(d.await())
    }

    override suspend fun pickFolderToSend(): PickResult {
        val d = CompletableDeferred<Uri?>()
        pendingTree = d
        openTree.launch(null)
        val tree = d.await() ?: return PickResult(emptyList())
        ContentUriSource.persist(contentResolver, tree)
        val (sources, skipped) = withContext(Dispatchers.IO) { ContentUriSource.expandTree(contentResolver, tree) }
        return PickResult(sources, skipped)
    }

    private fun sourcesFor(uris: List<Uri>): PickResult {
        val sources = mutableListOf<FileSource>()
        val skipped = mutableListOf<SkippedEntry>()
        for (u in uris) {
            ContentUriSource.persist(contentResolver, u)
            ContentUriSource.from(contentResolver, u)?.let { sources += it } ?: run { skipped += SkippedEntry(u.lastPathSegment ?: "file", "size unknown or unreadable") }
        }
        return PickResult(sources, skipped)
    }

    /**
     * Receiving writes with plain file APIs (needed for atomic rename and checkpoints), so
     * the choices are folders TransferX can write to directly.
     */
    override suspend fun pickDestination(current: String): String? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val options = listOf(
            "Downloads/TransferX (visible in the Files app)" to app.publicDestination(),
            "App folder (private to TransferX; removed on uninstall)" to app.appSpecificDestination(),
        )
        AlertDialog.Builder(this)
            .setTitle("Save received files to")
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                val dir: File = options[which].second
                dir.mkdirs()
                if (cont.isActive) cont.resume(dir.absolutePath)
            }
            .setOnCancelListener { if (cont.isActive) cont.resume(null) }
            .show()
    }

    override fun openFolder(path: String) {
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure { Toast.makeText(this, "Files are in $path", Toast.LENGTH_LONG).show() }
    }

    override fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("TransferX link", text))
    }
}
