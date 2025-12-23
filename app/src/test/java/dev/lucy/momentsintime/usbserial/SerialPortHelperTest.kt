package dev.andrea.perroquet.usbserial

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import dev.andrea.perroquet.logging.EventType
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
@ExperimentalCoroutinesApi
class SerialPortHelperTest {

    @MockK
    private lateinit var mockUsbManager: UsbManager

    @MockK
    private lateinit var mockUsbDevice: UsbDevice

    @MockK
    private lateinit var mockUsbConnection: UsbDeviceConnection

    @MockK
    private lateinit var mockUsbSerialDriver: UsbSerialDriver

    @MockK
    private lateinit var mockUsbSerialPort: UsbSerialPort

    private lateinit var context: Context
    private lateinit var serialPortHelper: SerialPortHelper

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        context = ApplicationProvider.getApplicationContext()

        // Mock the system service to return our mock UsbManager
        mockkStatic("android.content.Context")
        every { any<Context>().getSystemService(Context.USB_SERVICE) } returns mockUsbManager

        // Setup default behavior for mocks
        every { mockUsbManager.deviceList } returns HashMap<String, UsbDevice>().apply {
            put("device1", mockUsbDevice)
        }
        every { mockUsbDevice.deviceName } returns "Test Device"
        every { mockUsbDevice.productId } returns 1234
        every { mockUsbDevice.vendorId } returns 5678

        // Mock the UsbSerialProber
        mockkStatic(UsbSerialProber::class)
        val mockProber = mockk<UsbSerialProber>()
        every { UsbSerialProber.getDefaultProber() } returns mockProber
        every { mockProber.findAllDrivers(mockUsbManager) } returns listOf(mockUsbSerialDriver)
        every { mockProber.probeDevice(mockUsbDevice) } returns mockUsbSerialDriver

        // Setup driver and port mocks
        every { mockUsbSerialDriver.device } returns mockUsbDevice
        every { mockUsbSerialDriver.ports } returns listOf(mockUsbSerialPort)
        
        // Setup permission handling
        every { mockUsbManager.hasPermission(mockUsbDevice) } returns false
        every { mockUsbManager.requestPermission(mockUsbDevice, any()) } just Runs
        
        // Setup connection handling
        every { mockUsbManager.openDevice(mockUsbDevice) } returns mockUsbConnection
        every { mockUsbSerialPort.open(mockUsbConnection) } just Runs
        every { mockUsbSerialPort.setParameters(any(), any(), any(), any()) } just Runs
        every { mockUsbSerialPort.write(any(), any()) } just Runs // Return number of bytes written
        every { mockUsbSerialPort.close() } just Runs

        // Create the helper with our mocked context
        serialPortHelper = SerialPortHelper(context)
        
        // Replace the internal UsbManager with our mock
        val field = SerialPortHelper::class.java.getDeclaredField("usbManager")
        field.isAccessible = true
        field.set(serialPortHelper, mockUsbManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `scanForDevices returns list of available devices`() {
        // Act
        val devices = serialPortHelper.scanForDevices()
        
        // Assert
        assertEquals(1, devices.size)
        assertEquals(mockUsbDevice, devices[0])
        verify { mockUsbManager.deviceList }
    }

    @Test
    fun `connectToFirstAvailable returns true when devices are available`() {
        // Act
        val result = serialPortHelper.connectToFirstAvailable()
        
        // Assert
        assertTrue(result)
        verify { mockUsbManager.requestPermission(mockUsbDevice, any()) }
    }

    @Test
    fun `connectToFirstAvailable returns false when no devices are available`() {
        // Arrange
        val mockEmptyProber = mockk<UsbSerialProber>()
        every { UsbSerialProber.getDefaultProber() } returns mockEmptyProber
        every { mockEmptyProber.findAllDrivers(mockUsbManager) } returns emptyList()
        
        // Act
        val result = serialPortHelper.connectToFirstAvailable()
        
        // Assert
        assertFalse(result)
    }

    @Test
    fun `requestPermission connects directly when permission already granted`() = runTest {
        // Arrange
        every { mockUsbManager.hasPermission(mockUsbDevice) } returns true
        
        // Act
        serialPortHelper.requestPermission(mockUsbDevice)
        
        // Advance the main looper to process any posted tasks
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        
        // Assert - verify we didn't request permission again
        verify(exactly = 0) { mockUsbManager.requestPermission(mockUsbDevice, any()) }
    }

    @Test
    fun `requestPermission requests permission when not already granted`() {
        // Arrange
        every { mockUsbManager.hasPermission(mockUsbDevice) } returns false
        
        // Act
        serialPortHelper.requestPermission(mockUsbDevice)
        
        // Assert
        verify { mockUsbManager.requestPermission(mockUsbDevice, any()) }
    }

    @Test
    fun `sendTriggerCode returns false when not connected`() = runTest {
        // Act
        val result = serialPortHelper.sendTriggerCode(1)
        
        // Assert
        assertFalse(result)
    }

    @Test
    fun `sendEventTrigger maps event types to correct trigger codes`() {
        // Arrange - Mock the sendTriggerCode method
        val spyHelper = spyk(serialPortHelper)
        every { spyHelper.sendTriggerCode(any()) } returns true
        
        // Act
        spyHelper.sendEventTrigger(EventType.EXPERIMENT_START)
        spyHelper.sendEventTrigger(EventType.BLOCK_START)
        spyHelper.sendEventTrigger(EventType.TRIAL_START)
        
        // Assert
        verify { spyHelper.sendTriggerCode(SerialPortHelper.Companion.TriggerCode.EXPERIMENT_START) }
        verify { spyHelper.sendTriggerCode(SerialPortHelper.Companion.TriggerCode.BLOCK_START) }
        verify { spyHelper.sendTriggerCode(SerialPortHelper.Companion.TriggerCode.TRIAL_START) }
    }

    @Test
    fun `disconnect changes state to DISCONNECTED`() = runTest {
        // Arrange - Set a non-disconnected state first
        val field = SerialPortHelper::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        val stateFlow = field.get(serialPortHelper)
        val setValue = stateFlow.javaClass.getDeclaredMethod("setValue", Any::class.java)
        setValue.isAccessible = true
        setValue.invoke(stateFlow, SerialPortHelper.ConnectionState.CONNECTED)
        
        // Act
        serialPortHelper.disconnect()
        
        // Advance the main looper to process any posted tasks
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        
        // Assert
        assertEquals(SerialPortHelper.ConnectionState.DISCONNECTED, serialPortHelper.connectionState.first())
    }

    @Test
    fun `cleanup unregisters receivers and disconnects`() {
        // Arrange
        val spyHelper = spyk(serialPortHelper)
        every { spyHelper.disconnect() } just Runs
        
        // Act
        spyHelper.cleanup()
        
        // Assert
        verify { spyHelper.disconnect() }
    }
}
