package dev.andrea.perroquet.usbserial;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.security.InvalidParameterException;

/**
 * Manages the low-level USB serial connection
 * Handles reading/writing data and connection state
 */
public class SerialSocket implements SerialInputOutputManager.Listener {

    private static final int WRITE_WAIT_MILLIS = 200; // 0 blocked infinitely on unprogrammed arduino
    private final static String TAG = SerialSocket.class.getSimpleName();

    private final BroadcastReceiver disconnectBroadcastReceiver;

    private final Context context;
    private SerialListener listener;
    private UsbDeviceConnection connection;
    private UsbSerialPort serialPort;
    private SerialInputOutputManager ioManager;

    /**
     * Creates a new serial socket
     * 
     * @param context Application context (not an Activity)
     * @param connection USB device connection
     * @param serialPort USB serial port
     */
    public SerialSocket(Context context, UsbDeviceConnection connection, UsbSerialPort serialPort) {
        if(context instanceof Activity)
            throw new InvalidParameterException("Expected non-UI context, received Activity");
        this.context = context;
        this.connection = connection;
        this.serialPort = serialPort;
        disconnectBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (listener != null)
                    listener.onSerialIoError(new IOException("Background disconnect received"));
                disconnect(); // disconnect now, else would be queued until UI re-attached
            }
        };
    }

    /**
     * Gets the name of the connected serial device
     */
    public String getName() { 
        return serialPort.getDriver().getClass().getSimpleName().replace("SerialDriver",""); 
    }

    /**
     * Connects to the serial device and starts listening for data
     * 
     * @param listener Listener to receive serial events
     * @throws IOException If connection fails
     */
    public void connect(SerialListener listener) throws IOException {
        this.listener = listener;
        try {
            ContextCompat.registerReceiver(context, disconnectBroadcastReceiver, 
                    new IntentFilter(Constants.INTENT_ACTION_DISCONNECT), 
                    ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Exception e) {
            Log.w(TAG, "Error registering disconnect receiver", e);
        }
        
        try {
            serialPort.open(connection);
            serialPort.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            
            try {
                serialPort.setDTR(true); // for Arduino devices
                serialPort.setRTS(true);
            } catch (UnsupportedOperationException e) {
                Log.d(TAG, "Failed to set initial DTR/RTS", e);
            }
            
            ioManager = new SerialInputOutputManager(serialPort, this);
            ioManager.start();
            
            if (listener != null) {
                listener.onSerialConnect();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error connecting to serial device", e);
            disconnect();
            throw e;
        }
    }

    /**
     * Disconnects from the serial device and cleans up resources
     */
    public void disconnect() {
        listener = null; // ignore remaining data and errors
        
        if (ioManager != null) {
            ioManager.setListener(null);
            ioManager.stop();
            ioManager = null;
        }
        
        if (serialPort != null) {
            try {
                serialPort.setDTR(false);
                serialPort.setRTS(false);
            } catch (Exception e) {
                Log.d(TAG, "Exception while clearing DTR/RTS", e);
            }
            
            try {
                serialPort.close();
            } catch (Exception e) {
                Log.d(TAG, "Exception while closing serial port", e);
            }
            
            serialPort = null;
        }
        
        if (connection != null) {
            connection.close();
            connection = null;
        }
        
        try {
            context.unregisterReceiver(disconnectBroadcastReceiver);
        } catch (Exception e) {
            Log.d(TAG, "Exception while unregistering receiver", e);
        }
    }

    /**
     * Writes data to the serial port
     * 
     * @param data Data to write
     * @throws IOException If write fails or not connected
     */
    public void write(byte[] data) throws IOException {
        if (serialPort == null)
            throw new IOException("Not connected to serial device");
        
        serialPort.write(data, WRITE_WAIT_MILLIS);
    }

    @Override
    public void onNewData(byte[] data) {
        if(listener != null)
            listener.onSerialRead(data);
    }

    @Override
    public void onRunError(Exception e) {
        if (listener != null)
            listener.onSerialIoError(e);
    }
}
