package com.transferx.desktop

import com.transferx.core.transfer.LocalSelection
import com.transferx.ui.BluetoothBridge
import com.transferx.ui.Capability
import com.transferx.ui.CapabilityStatus
import com.transferx.ui.PickResult
import com.transferx.ui.PlatformBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.JFileChooser

class DesktopPlatform(private val frame: () -> Frame?) : PlatformBridge {
    override val isMobile = false
    override val supportsFolderSelection = true

    /** The JVM has no Bluetooth API; see docs/PLATFORM_MATRIX.md. */
    override val bluetooth: BluetoothBridge? = null

    override val capabilities = listOf(
        Capability("Wi-Fi / local network", CapabilityStatus.AVAILABLE, "Direct TCP connection secured with TLS 1.3/1.2. No internet or cloud involved."),
        Capability(
            "Automatic discovery", CapabilityStatus.AVAILABLE,
            "UDP multicast and broadcast. Guest networks and some routers block it; use the QR code or address instead.",
        ),
        Capability("QR code / address and pairing code", CapabilityStatus.AVAILABLE, "Works whenever both devices can reach each other on the same network."),
        Capability("Phone hotspot", CapabilityStatus.AVAILABLE, "Connect this computer to the phone's hotspot; TransferX then works over that network."),
        Capability(
            "Bluetooth", CapabilityStatus.UNAVAILABLE,
            "Not implemented in the desktop app: the Java runtime has no Bluetooth API, and native RFCOMM bindings per OS aren't included. Use Wi-Fi or a hotspot.",
        ),
    )

    override suspend fun pickFiles(): PickResult {
        val files = withContext(Dispatchers.Swing) {
            val dialog = FileDialog(frame(), "Choose files to send", FileDialog.LOAD)
            dialog.isMultipleMode = true
            dialog.isVisible = true // modal
            dialog.files?.toList().orEmpty()
        }
        return expand(files)
    }

    override suspend fun pickFolderToSend(): PickResult {
        val files = withContext(Dispatchers.Swing) {
            val chooser = JFileChooser().apply {
                dialogTitle = "Choose folders to send"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isMultiSelectionEnabled = true
            }
            if (chooser.showOpenDialog(frame()) == JFileChooser.APPROVE_OPTION) chooser.selectedFiles.toList() else emptyList()
        }
        return expand(files)
    }

    private suspend fun expand(files: List<File>): PickResult = withContext(Dispatchers.IO) {
        val sel = LocalSelection.expand(files.map { it.toPath() })
        PickResult(sel.sources, sel.skipped)
    }

    override suspend fun pickDestination(current: String): String? = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser(File(current).takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))).apply {
            dialogTitle = "Choose where to save received files"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        if (chooser.showDialog(frame(), "Use this folder") == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
    }

    override fun openFolder(path: String) {
        runCatching {
            val p = Paths.get(path)
            Files.createDirectories(p)
            Desktop.getDesktop().open(p.toFile())
        }
    }

    override fun copyToClipboard(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }
}
