package dev.andrea.speechprod.usbserial;

/**
 * Constants used throughout the USB serial implementation
 */
public class Constants {

    // values have to be globally unique
    public static final String INTENT_ACTION_GRANT_USB = "dev.andrea.myapplication.GRANT_USB";
    public static final String INTENT_ACTION_DISCONNECT = "dev.andrea.myapplication.Disconnect";
    public static final String NOTIFICATION_CHANNEL = "dev.andrea.myapplication.UsbChannel";
    public static final String INTENT_CLASS_MAIN_ACTIVITY = "dev.andrea.myapplication.MainActivity";

    // values have to be unique within each app
    public static final int NOTIFY_MANAGER_START_FOREGROUND_SERVICE = 1001;

    private Constants() {}
}
