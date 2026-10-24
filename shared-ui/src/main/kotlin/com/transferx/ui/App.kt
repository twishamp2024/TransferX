package com.transferx.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private data class Tab(val screen: Screen, val icon: ImageVector, val label: String)

private val tabs = listOf(
    Tab(Screen.HOME, Icons.Default.Home, "Home"),
    Tab(Screen.SEND, Icons.Default.Send, "Send"),
    Tab(Screen.RECEIVE, Icons.Default.Email, "Receive"),
    Tab(Screen.DEVICES, Icons.Default.Share, "Nearby"),
    Tab(Screen.HISTORY, Icons.Default.List, "History"),
    Tab(Screen.SETTINGS, Icons.Default.Settings, "Settings"),
)

/** Root of the TransferX UI. Wide windows get a navigation rail, phones a bottom bar. */
@Composable
fun TransferXApp(c: AppController) {
    TransferXTheme {
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(Unit) { c.messages.collect { snackbar.showSnackbar(it) } }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    if (!wide) {
                        NavigationBar {
                            tabs.filter { it.screen != Screen.DEVICES }.forEach { t ->
                                NavigationBarItem(
                                    selected = c.screen == t.screen, onClick = { c.selectTab(t.screen) },
                                    icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label, maxLines = 1) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (wide) {
                        NavigationRail {
                            tabs.forEach { t ->
                                NavigationRailItem(
                                    selected = c.screen == t.screen, onClick = { c.selectTab(t.screen) },
                                    icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) },
                                )
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.widthIn(max = 920.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
                            if (c.screen == Screen.TRANSFER || (!wide && c.screen == Screen.DEVICES)) {
                                IconButton(onClick = { if (!c.back()) c.selectTab(Screen.HOME) }) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                }
                            }
                            when (c.screen) {
                                Screen.HOME -> HomeScreen(c)
                                Screen.SEND -> SendScreen(c)
                                Screen.RECEIVE -> ReceiveScreen(c)
                                Screen.DEVICES -> DevicesScreen(c)
                                Screen.TRANSFER -> TransferScreen(c)
                                Screen.HISTORY -> HistoryScreen(c)
                                Screen.SETTINGS -> SettingsScreen(c)
                            }
                        }
                    }
                }
            }
        }
        PromptHost(c)
        if (c.showManualConnect) ManualConnectDialog(c)
    }
}
