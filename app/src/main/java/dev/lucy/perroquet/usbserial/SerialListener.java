package dev.andrea.perroquet.usbserial;

import java.util.ArrayDeque;

/**
 * Interface for serial communication events
 * Implemented by classes that need to receive serial data
 */
public interface SerialListener {
    /**
     * Called when serial connection is established
     */
    void onSerialConnect();
    
    /**
     * Called when connection attempt fails
     */
    void onSerialConnectError(Exception e);
    
    /**
     * Called when new data arrives (socket -> service)
     */
    void onSerialRead(byte[] data);
    
    /**
     * Called when new data is ready for UI thread (service -> UI)
     */
    void onSerialRead(ArrayDeque<byte[]> datas);
    
    /**
     * Called when serial connection has an error
     */
    void onSerialIoError(Exception e);
}
