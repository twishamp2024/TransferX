package app.transferx.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.transferx.core.transfer.ConnectTarget
import com.transferx.core.transfer.RawStream
import com.transferx.core.transfer.TransferEngine
import com.transferx.core.transfer.TransportKind
import com.transferx.core.util.Log
import com.transferx.ui.BluetoothBridge
import com.transferx.ui.BluetoothPeer
import com.transferx.ui.BluetoothState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Bluetooth Classic RFCOMM transport (Android to Android).
 *
 * Uses *secure* RFCOMM sockets, which require the devices to be paired (bonded) in
 * system settings. Android then provides link-level authentication and encryption.
 * TransferX still runs its own TLS and pairing over the socket, so identity checks
 * are the same as on Wi-Fi.
 */
@SuppressLint("MissingPermission") // every entry point checks permission through state()
class AndroidBluetooth(private val context: Context, private val engine: TransferEngine) : BluetoothBridge {
    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val _state = MutableStateFlow(currentState())
    override val state: StateFlow<BluetoothState> = _state.asStateFlow()

    @Volatile private var server: BluetoothServerSocket? = null

    /** Set by the activity: asks for BLUETOOTH_CONNECT or to turn Bluetooth on. */
    @Volatile var enableRequester: (() -> Unit)? = null

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = refresh()
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun hasPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun currentState(): BluetoothState = when {
        adapter == null -> BluetoothState.UNSUPPORTED
        !hasPermission() -> BluetoothState.NO_PERMISSION
        !adapter.isEnabled -> BluetoothState.OFF
        server != null -> BluetoothState.LISTENING
        else -> BluetoothState.READY
    }

    fun refresh() {
        val s = currentState()
        if (s != BluetoothState.LISTENING && s != BluetoothState.READY) stopListening()
        _state.value = currentState()
    }

    override fun bondedDevices(): List<BluetoothPeer> {
        if (currentState() !in setOf(BluetoothState.READY, BluetoothState.LISTENING)) return emptyList()
        return adapter!!.bondedDevices.orEmpty().map { BluetoothPeer(it.name ?: it.address, it.address, true) }.sortedBy { it.name }
    }

    override fun target(peer: BluetoothPeer): ConnectTarget = ConnectTarget.Stream(
        label = peer.name,
        transport = TransportKind.BLUETOOTH,
        open = {
            withContext(Dispatchers.IO) {
                val a = adapter ?: throw IOException("Bluetooth unavailable")
                if (!hasPermission()) throw SecurityException("Bluetooth permission not granted")
                val device = a.getRemoteDevice(peer.address)
                val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                runCatching { a.cancelDiscovery() } // discovery slows connections down
                socket.connect()
                RawStream(socket.inputStream, socket.outputStream, peer.name) { runCatching { socket.close() } }
            }
        },
    )

    override fun requestEnable() {
        enableRequester?.invoke()
    }

    override fun startListening() {
        if (currentState() != BluetoothState.READY) return
        val s = try {
            adapter!!.listenUsingRfcommWithServiceRecord("TransferX", SERVICE_UUID)
        } catch (e: IOException) {
            Log.w(TAG, "Could not open Bluetooth server socket", e); return
        }
        server = s
        _state.value = BluetoothState.LISTENING
        thread(isDaemon = true, name = "bt-accept") {
            while (server === s) {
                val sock = try {
                    s.accept()
                } catch (e: IOException) {
                    break
                }
                val label = runCatching { sock.remoteDevice.name }.getOrNull() ?: "Bluetooth device"
                engine.acceptStream(RawStream(sock.inputStream, sock.outputStream, label) { runCatching { sock.close() } }, TransportKind.BLUETOOTH)
            }
        }
        Log.i(TAG, "Listening for Bluetooth connections")
    }

    override fun stopListening() {
        val s = server ?: return
        server = null
        runCatching { s.close() }
        _state.value = currentState()
    }

    companion object {
        private const val TAG = "Bluetooth"

        /** TransferX's RFCOMM service record (random, app-specific UUID). */
        val SERVICE_UUID: UUID = UUID.fromString("5a1f3c2e-8d4b-4e6a-9c71-2b0e7f3d9a14")
    }
}
