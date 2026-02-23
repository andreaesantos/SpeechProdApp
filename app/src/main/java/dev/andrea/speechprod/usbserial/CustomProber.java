package dev.andrea.speechprod.usbserial;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.Ch34xSerialDriver;
import com.hoho.android.usbserial.driver.FtdiSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.ProlificSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

/**
 * Adds support for USB devices not recognized by the default USB serial probers
 * 
 * If the app should auto-start for these devices, also add their
 * VID/PID values to app/src/main/res/xml/usb_device_filter.xml
 */
public class CustomProber {

    /**
     * List of common USB vendor IDs for serial adapters
     */
    public static final int VENDOR_FTDI = 0x0403;      // FTDI
    public static final int VENDOR_SILABS = 0x10C4;    // Silicon Labs CP210x
    public static final int VENDOR_PROLIFIC = 0x067B;  // Prolific
    public static final int VENDOR_QINHENG = 0x1A86;   // QinHeng CH340/CH341
    public static final int VENDOR_ARDUINO = 0x2341;   // Arduino
    public static final int VENDOR_TEENSY = 0x16C0;    // Teensyduino
    
    /**
     * Gets a custom USB serial prober with additional device support
     */
    public static UsbSerialProber getCustomProber() {
        ProbeTable customTable = new ProbeTable();
        
        // Add custom devices here
        customTable.addProduct(0x1234, 0xabcd, FtdiSerialDriver.class);      // Example custom device
        customTable.addProduct(0x0557, 0x2008, ProlificSerialDriver.class);  // Prolific device
        
        // Common Arduino devices
        customTable.addProduct(VENDOR_ARDUINO, 0x0043, CdcAcmSerialDriver.class);  // Arduino Uno
        customTable.addProduct(VENDOR_ARDUINO, 0x8036, CdcAcmSerialDriver.class);  // Arduino Leonardo
        
        // CH340 variants
        customTable.addProduct(VENDOR_QINHENG, 0x7523, Ch34xSerialDriver.class);  // CH340
        customTable.addProduct(VENDOR_QINHENG, 0x5523, Ch34xSerialDriver.class);  // CH341
        
        return new UsbSerialProber(customTable);
    }
}
