package dev.andrea.speechprod.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import dev.andrea.speechprod.logging.EventLogger
import dev.andrea.speechprod.logging.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility class to monitor system resources and handle error recovery
 */
class SystemMonitor private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "SystemMonitor"
        private var instance: SystemMonitor? = null
        
        fun getInstance(context: Context): SystemMonitor {
            return instance ?: synchronized(this) {
                instance ?: SystemMonitor(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val scope = CoroutineScope(Dispatchers.IO)
    private var batteryReceiver: BroadcastReceiver? = null
    private var isMonitoring = false
    
    // Battery thresholds
    private val warningThreshold = 20
    private val criticalThreshold = 10
    
    /**
     * Start monitoring system resources
     */
    fun startMonitoring() {
        if (isMonitoring) return
        
        try {
            // Register battery receiver
            batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    handleBatteryUpdate(intent)
                }
            }
            
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            context.registerReceiver(batteryReceiver, filter)
            
            // Check available storage
            checkStorage()
            
            isMonitoring = true
            Log.d(TAG, "System monitoring started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start system monitoring: ${e.message}")
        }
    }
    
    /**
     * Stop monitoring system resources
     */
    fun stopMonitoring() {
        if (!isMonitoring) return
        
        try {
            batteryReceiver?.let {
                context.unregisterReceiver(it)
                batteryReceiver = null
            }
            
            isMonitoring = false
            Log.d(TAG, "System monitoring stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping system monitoring: ${e.message}")
        }
    }
    
    /**
     * Handle battery update intent
     */
    private fun handleBatteryUpdate(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val batteryPct = level * 100 / scale.toFloat()
        
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                         status == BatteryManager.BATTERY_STATUS_FULL
        
        // Log battery status
        when {
            batteryPct <= criticalThreshold && !isCharging -> {
                logSystemWarning("critical_battery", batteryPct.toInt())
            }
            batteryPct <= warningThreshold && !isCharging -> {
                logSystemWarning("low_battery", batteryPct.toInt())
            }
        }
    }
    
    /**
     * Check available storage space
     */
    private fun checkStorage() {
        scope.launch {
            try {
                val externalDir = context.getExternalFilesDir(null)
                if (externalDir != null) {
                    val freeSpace = externalDir.freeSpace
                    val totalSpace = externalDir.totalSpace
                    val freePercent = (freeSpace.toFloat() / totalSpace) * 100
                    
                    if (freePercent < 10) {
                        logSystemWarning("low_storage", freePercent.toInt())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking storage: ${e.message}")
            }
        }
    }
    
    /**
     * Log system warning to EventLogger
     */
    private fun logSystemWarning(type: String, value: Int) {
        try {
            val logger = EventLogger.getInstance()
            logger.logEvent(
                EventType.BATTERY_WARNING,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log system warning: ${e.message}")
        }
    }
    
    /**
     * Create a system report file with current status
     * @return File containing the report or null if failed
     */
    fun createSystemReport(): File? {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val reportFile = File(context.getExternalFilesDir("reports"), "system_report_$timestamp.txt")
            
            if (!reportFile.parentFile?.exists()!!) {
                reportFile.parentFile?.mkdirs()
            }
            
            reportFile.bufferedWriter().use { writer ->
                // Write header
                writer.write("=== System Report ===\n")
                writer.write("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n\n")
                
                // Battery info
                val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryPct = level * 100 / scale.toFloat()
                
                val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                                 status == BatteryManager.BATTERY_STATUS_FULL
                
                writer.write("Battery Level: $batteryPct%\n")
                writer.write("Charging: $isCharging\n\n")
                
                // Storage info
                val externalDir = context.getExternalFilesDir(null)
                if (externalDir != null) {
                    val freeSpace = externalDir.freeSpace / (1024 * 1024) // MB
                    val totalSpace = externalDir.totalSpace / (1024 * 1024) // MB
                    val freePercent = (externalDir.freeSpace.toFloat() / externalDir.totalSpace) * 100
                    
                    writer.write("Storage:\n")
                    writer.write("  Free: $freeSpace MB\n")
                    writer.write("  Total: $totalSpace MB\n")
                    writer.write("  Free Percent: $freePercent%\n\n")
                }
                
                // Runtime info
                val runtime = Runtime.getRuntime()
                val maxMemory = runtime.maxMemory() / (1024 * 1024) // MB
                val totalMemory = runtime.totalMemory() / (1024 * 1024) // MB
                val freeMemory = runtime.freeMemory() / (1024 * 1024) // MB
                
                writer.write("Memory:\n")
                writer.write("  Max: $maxMemory MB\n")
                writer.write("  Total: $totalMemory MB\n")
                writer.write("  Free: $freeMemory MB\n")
            }
            
            return reportFile
        } catch (e: IOException) {
            Log.e(TAG, "Error creating system report: ${e.message}")
            return null
        }
    }
}
