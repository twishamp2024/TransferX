package com.transferx.core.app

import java.net.InetAddress
import java.nio.file.Path
import java.nio.file.Paths

/** Per-OS locations for desktop builds (Windows, macOS, Linux). Not used on Android. */
object DesktopPaths {
    val os: String = System.getProperty("os.name").orEmpty().lowercase()

    val platform: String = when {
        os.contains("win") -> "windows"
        os.contains("mac") -> "macos"
        else -> "linux"
    }

    /** App data: %APPDATA%\TransferX, ~/Library/Application Support/TransferX, or $XDG_DATA_HOME/transferx. */
    fun defaultDataDir(): Path {
        System.getenv("TRANSFERX_HOME")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
        val home = Paths.get(System.getProperty("user.home"))
        return when (platform) {
            "windows" -> Paths.get(System.getenv("APPDATA") ?: home.resolve("AppData/Roaming").toString()).resolve("TransferX")
            "macos" -> home.resolve("Library/Application Support/TransferX")
            else -> (System.getenv("XDG_DATA_HOME")?.let { Paths.get(it) } ?: home.resolve(".local/share")).resolve("transferx")
        }
    }

    fun defaultDownloads(): Path = Paths.get(System.getProperty("user.home")).resolve("Downloads").resolve("TransferX")

    fun defaultDeviceName(): String {
        val host = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
        return host?.takeIf { it.isNotBlank() }?.substringBefore('.') ?: "My computer"
    }
}
