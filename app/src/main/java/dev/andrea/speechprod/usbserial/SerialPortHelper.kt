package dev.andrea.speechprod.usbserial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.Executors
import dev.andrea.speechprod.BuildConfig

/**
 * Helper class for managing USB serial port connections and sending trigger codes
 */
class SerialPortHelper(private val context: Context) {

    companion object {
        private const val TAG = "SerialPortHelper"
        private const val ACTION_USB_PERMISSION = "dev.andrea.speechprod.USB_PERMISSION"
        private const val BAUD_RATE = 9600

        // Trigger codes for different event types
        object TriggerCode {
            const val EXPERIMENT_START = 1
            const val EXPERIMENT_END = 200
            const val TRIAL_START = 20
            const val TRIAL_END = 25
            const val VIDEO_START = 30
            const val VIDEO_END = 35
            const val RECORDING_START = 50
            const val RECORDING_END = 55
        }
    }

    // Serial service connection
    private var serialIoManager: SerialInputOutputManager? = null
    private var deviceAddress: String? = null

    // USB components
    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbSerialPort: UsbSerialPort? = null

    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.IO)
    private val executor = Executors.newSingleThreadExecutor()

    // Permission request pending intent
    private val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
        Intent(ACTION_USB_PERMISSION),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT else PendingIntent.FLAG_MUTABLE
    )

    // USB permission and detach receivers
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    }
                    
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            Log.d(TAG, "USB permission granted for device: ${it.deviceName}")
                            connectToDevice(it)
                        }
                    } else {
                        Log.e(TAG, "USB permission denied")
                        _connectionState.value = ConnectionState.PERMISSION_DENIED
                    }
                }
            }
        }
    }

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                }
                
                device?.let {
                    if (it == usbDevice) {
                        Log.d(TAG, "USB device detached: ${it.deviceName}")
                        disconnect()
                    }
                }
            }
        }
    }

    init {
        // Register receivers
        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
                context,
                usbPermissionReceiver,
                permissionFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val detachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        ContextCompat.registerReceiver(
                context,
                usbDetachReceiver,
                detachFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Initial device scan
        scanForDevices()
    }

    /**
     * Scan for available USB serial devices
     * @return List of available USB devices
     */
    fun scanForDevices(): List<UsbDevice> {
        // Debug: Log ALL USB devices first
        val allDevices = usbManager.deviceList
        Log.d(TAG, "Total USB devices connected: ${allDevices.size}")
        allDevices.values.forEach { device ->
            Log.d(TAG, "System sees USB device: ${device.deviceName} VID=${device.vendorId} PID=${device.productId}")
        }

        val availableDrivers = CustomProber.getCustomProber().findAllDrivers(usbManager)
        val devices = mutableListOf<UsbDevice>()

        if (availableDrivers.isEmpty()) {
            Log.d(TAG, "No USB serial devices with recognized drivers found")
            return devices
        }

        for (driver in availableDrivers) {
            val device = driver.device
            devices.add(device)
            Log.d(
                    TAG, "Found supported USB serial device: ${device.deviceName}, " +
                            "Product ID: ${device.productId}, " +
                            "Vendor ID: ${device.vendorId}")
        }

        return devices
    }

    /**
     * Request permission for a USB device
     * @param device The USB device to request permission for
     */
    fun requestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "Already have permission for device: ${device.deviceName}")
            connectToDevice(device)
        } else {
            Log.d(TAG, "Requesting permission for device: ${device.deviceName}")
            usbManager.requestPermission(device, permissionIntent)
            _connectionState.value = ConnectionState.PERMISSION_PENDING
        }
    }

    /**
     * Connect to the first available USB serial device
     * @return true if a device was found and connection attempt started
     */
    fun connectToFirstAvailable(): Boolean {
        val devices = scanForDevices()
        if (devices.isEmpty()) {
            _connectionState.value = ConnectionState.NO_DEVICES
            return false
        }

        // Just use the first available supported device
        requestPermission(devices[0])
        return true
    }

    /**
     * Connect to a specific USB device
     * @param device The USB device to connect to
     */
    private fun connectToDevice(device: UsbDevice) {
        scope.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING

                // Find the driver for the device
                val driver = CustomProber.getCustomProber().probeDevice(device)
                if (driver == null) {
                    Log.e(TAG, "No driver found for device: ${device.deviceName}")
                    _connectionState.value = ConnectionState.DRIVER_NOT_FOUND
                    return@launch
                }

                // Store device reference
                usbDevice = device
                deviceAddress = device.deviceName

                val connection = usbManager.openDevice(device)
                if (connection == null) {
                    Log.e(TAG, "Could not open connection to device: ${device.deviceName}")
                    _connectionState.value = ConnectionState.CONNECTION_FAILED
                    return@launch
                }

                val port = driver.ports[0]
                // Open the port and configure it
                try {
                    port.open(connection)
                    port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    port.dtr = true
                    port.rts = true

                    // Store the connection and port
                    usbConnection = connection
                    usbSerialPort = port

                    // Set up the serial I/O manager
                    setupSerialIoManager(port)

                    _connectionState.value = ConnectionState.CONNECTED
                    Log.d(TAG, "Successfully connected to serial device")

                } catch (e: IOException) {
                    Log.e(TAG, "IOException opening port", e)
                    try {
                        port.close()
                    } catch (e2: IOException) { }
                    _connectionState.value = ConnectionState.ERROR
                }

                // Send a test byte
                sendTriggerCode(0)

            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to device: ${e.message}", e)
                _connectionState.value = ConnectionState.CONNECTION_FAILED
            }
        }
    }

    /**
     * Initialize the serial service and listener
     */
    private fun setupSerialIoManager(port: UsbSerialPort) {
        serialIoManager?.stop()

        serialIoManager =
            SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    Log.d(TAG, "Received data: ${data.joinToString(", ") { it.toString() }}")
                }

                override fun onRunError(e: Exception) {
                    Log.e(TAG, "Serial I/O error", e)
                }
            })

        serialIoManager?.start()
    }

    /**
     * Disconnect from the current USB device
     */
    fun disconnect() {
        scope.launch {
            serialIoManager?.stop()
            serialIoManager = null
            
            try {
                usbSerialPort?.close()
            } catch (e: Exception) {}
            usbSerialPort = null
            
            usbConnection?.close()
            usbConnection = null
            
            usbDevice = null
            deviceAddress = null

            _connectionState.value = ConnectionState.DISCONNECTED
            Log.d(TAG, "Disconnected from USB device")
        }
    }

    /**
     * Send a trigger code to the connected device
     * @param code The trigger code to send
     * @return true if the code was sent successfully
     */
    fun sendTriggerCode(code: Int): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send trigger code: not connected (state: ${_connectionState.value})")
            return false
        }

        scope.launch {
            try {
                usbSerialPort?.let { port ->
                    val buffer = byteArrayOf(code.toByte())
                    port.write(buffer, 1000)
                    Log.d(TAG, "Sent trigger code: $code")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending trigger code: ${e.message}")
                // If it's a persistent error, we might want to update state
            }
        }
        return true
    }

    /**
     * Send a trigger code for an experiment event
     * @param eventType The event type from EventType enum
     * @return true if the code was sent successfully
     */
    fun sendEventTrigger(eventType: dev.andrea.speechprod.logging.EventType): Boolean {
        val triggerCode = when (eventType) {
            dev.andrea.speechprod.logging.EventType.EXPERIMENT_START -> {
                // Dynamically change code based on build flavor
                when (BuildConfig.FLAVOR) {
                    "conversational" -> 11
                    "auditorynaming" -> 12
                    "wordrepetition" -> 13
                    "nonwordrepetition" -> 14
                    else             -> TriggerCode.EXPERIMENT_START // Fallback to 1
                }
            }
            dev.andrea.speechprod.logging.EventType.EXPERIMENT_END,
            dev.andrea.speechprod.logging.EventType.EXPERIMENT_ENDED -> {
                when (BuildConfig.FLAVOR) {
                    "conversational" -> 211
                    "auditorynaming" -> 212
                    "wordrepetition" -> 213
                    else             -> TriggerCode.EXPERIMENT_END // Fallback to 200
                }
            }
            dev.andrea.speechprod.logging.EventType.TRIAL_START -> TriggerCode.TRIAL_START
            dev.andrea.speechprod.logging.EventType.TRIAL_END -> TriggerCode.TRIAL_END
            dev.andrea.speechprod.logging.EventType.STIMULUS_ONSET -> TriggerCode.VIDEO_START
            dev.andrea.speechprod.logging.EventType.STIMULUS_OFFSET -> TriggerCode.VIDEO_END
            dev.andrea.speechprod.logging.EventType.RECORDING_START -> TriggerCode.RECORDING_START
            dev.andrea.speechprod.logging.EventType.RECORDING_END -> TriggerCode.RECORDING_END
            else -> return false
        }

        Log.d(TAG, "Sending flavor-aware trigger code: $triggerCode for event: $eventType (Flavor: ${BuildConfig.FLAVOR})")

        return sendTriggerCode(triggerCode)
    }

    /**
     * Clean up resources when no longer needed
     */
    fun cleanup() {
        try {
            context.unregisterReceiver(usbPermissionReceiver)
            context.unregisterReceiver(usbDetachReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers: ${e.message}")
        }

        disconnect()
    }

    /**
     * Connection state enum
     */
    enum class ConnectionState {
        DISCONNECTED,
        NO_DEVICES,
        PERMISSION_PENDING,
        PERMISSION_DENIED,
        CONNECTING,
        CONNECTED,
        DRIVER_NOT_FOUND,
        CONNECTION_FAILED,
        ERROR
    }
}
