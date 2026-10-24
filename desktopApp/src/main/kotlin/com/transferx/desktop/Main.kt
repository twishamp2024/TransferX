package com.transferx.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.transferx.core.app.DesktopPaths
import com.transferx.core.app.TransferXNode
import com.transferx.core.transfer.LocalSelection
import com.transferx.ui.AppController
import com.transferx.ui.PickResult
import com.transferx.ui.Screen
import com.transferx.ui.TransferXApp
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.UIManager

/** Original TransferX mark: two opposing arrows in a rounded teal square. */
private val Logo: ImageVector = ImageVector.Builder("TransferX", 64.dp, 64.dp, 64f, 64f).apply {
    path(fill = SolidColor(Color(0xFF0B7A75))) {
        moveTo(14f, 2f); lineTo(50f, 2f); quadTo(62f, 2f, 62f, 14f); lineTo(62f, 50f); quadTo(62f, 62f, 50f, 62f)
        lineTo(14f, 62f); quadTo(2f, 62f, 2f, 50f); lineTo(2f, 14f); quadTo(2f, 2f, 14f, 2f); close()
    }
    val arrow = SolidColor(Color.White)
    path(stroke = arrow, strokeLineWidth = 5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(16f, 24f); lineTo(46f, 24f); moveTo(38f, 16f); lineTo(46f, 24f); lineTo(38f, 32f)
        moveTo(48f, 40f); lineTo(18f, 40f); moveTo(26f, 32f); lineTo(18f, 40f); lineTo(26f, 48f)
    }
}.build()

private fun arg(args: Array<String>, name: String): String? = args.indexOf(name).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }

/** Files dropped onto the window are added to the send selection. */
@OptIn(ExperimentalComposeUiApi::class)
private fun dropTarget(controller: AppController) = object : DragAndDropTarget {
    override fun onDrop(event: DragAndDropEvent): Boolean {
        val t = event.awtTransferable
        if (!t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
        @Suppress("UNCHECKED_CAST")
        val files = t.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
        val sel = LocalSelection.expand(files.map { it.toPath() })
        controller.addPicked(PickResult(sel.sources, sel.skipped))
        return true
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
fun main(args: Array<String>) {
    val home = arg(args, "--home")?.let { Paths.get(it) } ?: DesktopPaths.defaultDataDir()
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val node = TransferXNode(home, DesktopPaths.platform, DesktopPaths.defaultDeviceName(), DesktopPaths.defaultDownloads())
    arg(args, "--name")?.let { n -> node.settingsStore.update { it.copy(deviceName = n) } }
    runCatching { Files.createDirectories(Paths.get(node.settingsStore.current.destination)) }
    node.start()

    application {
        val state = rememberWindowState(width = 1120.dp, height = 820.dp)
        val scope = rememberCoroutineScope()
        var frame: Frame? = null
        val controller = remember { AppController(node, DesktopPlatform { frame }, scope) }
        Window(
            onCloseRequest = {
                node.close()
                exitApplication()
            },
            title = "TransferX — ${node.settingsStore.current.deviceName}",
            icon = rememberVectorPainter(Logo),
            state = state,
            onPreviewKeyEvent = { e ->
                if (e.type != KeyEventType.KeyDown) return@Window false
                val mod = e.isCtrlPressed || e.isMetaPressed
                when {
                    mod && e.key == Key.O -> { controller.addFiles(); true }
                    mod && e.key == Key.R -> { controller.selectTab(Screen.RECEIVE); true }
                    mod && e.key == Key.H -> { controller.selectTab(Screen.HISTORY); true }
                    e.key == Key.F5 -> { node.discovery.refresh(); true }
                    e.key == Key.Escape -> controller.back()
                    else -> false
                }
            },
        ) {
            frame = window
            val target = remember { dropTarget(controller) }
            Box(Modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)) {
                TransferXApp(controller)
            }
        }
    }
}
