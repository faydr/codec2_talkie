package com.radio.codec2talkie.connect;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.radio.codec2talkie.R;
import com.radio.codec2talkie.settings.PreferenceKeys;

import java.io.IOException;
import java.util.List;

public class UsbConnectActivity extends AppCompatActivity {

    private final int USB_NOT_FOUND = 1;
    private final int USB_CONNECTED = 2;

    private final int USB_BAUD_RATE_DEFAULT = 115200;
    private final int USB_DATA_BITS_DEFAULT = 8;
    private final int USB_STOP_BITS_DEFAULT = UsbSerialPort.STOPBITS_1;
    private final int USB_PARITY_DEFAULT = UsbSerialPort.PARITY_NONE;

    private int _baudRate;
    private int _dataBits;
    private int _stopBits;
    private int _parity;
    private boolean _enableDtr;
    private boolean _enableRts;

    private String _usbDeviceName;
    private UsbSerialPort _usbPort;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb_connect);

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        _baudRate = Integer.parseInt(sharedPreferences.getString(PreferenceKeys.PORTS_USB_SERIAL_SPEED, String.valueOf(USB_BAUD_RATE_DEFAULT)));
        _dataBits = Integer.parseInt(sharedPreferences.getString(PreferenceKeys.PORTS_USB_DATA_BITS, String.valueOf(USB_DATA_BITS_DEFAULT)));
        _stopBits = Integer.parseInt(sharedPreferences.getString(PreferenceKeys.PORTS_USB_STOP_BITS, String.valueOf(USB_STOP_BITS_DEFAULT)));
        _parity = Integer.parseInt(sharedPreferences.getString(PreferenceKeys.PORTS_USB_PARITY, String.valueOf(USB_PARITY_DEFAULT)));
        _enableDtr = sharedPreferences.getBoolean(PreferenceKeys.PORTS_USB_DTR, false);
        _enableRts = sharedPreferences.getBoolean(PreferenceKeys.PORTS_USB_RTS, false);

        ProgressBar progressBarUsb = findViewById(R.id.progressBarUsb);
        progressBarUsb.setVisibility(View.VISIBLE);
        ObjectAnimator.ofInt(progressBarUsb, "progress", 10)
                .setDuration(300)
                .start();
        connectUsb();
    }

    private UsbSerialProber getCustomProber() {
        ProbeTable customTable = new ProbeTable();
        // Spark Fun
        customTable.addProduct(0x1b4f, 0x9204, CdcAcmSerialDriver.class);
        // Arduino Due
        customTable.addProduct(0x2341, 0x003d, CdcAcmSerialDriver.class);
        return new UsbSerialProber(customTable);
    }

    private void connectUsb() {

        new Thread() {
            @Override
            public void run() {
                Message resultMsg = new Message();

                UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
                List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
                if (availableDrivers.isEmpty()) {
                    availableDrivers = getCustomProber().findAllDrivers(manager);
                }
                if (availableDrivers.isEmpty()) {
                    resultMsg.what = USB_NOT_FOUND;
                    onUsbStateChanged.sendMessage(resultMsg);
                    return;
                }

                UsbSerialDriver driver = availableDrivers.get(0);
                UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
                if (connection == null) {
                    resultMsg.what = USB_NOT_FOUND;
                    onUsbStateChanged.sendMessage(resultMsg);
                    return;
                }
                UsbSerialPort port = driver.getPorts().get(0);
                if (port == null) {
                    resultMsg.what = USB_NOT_FOUND;
                    onUsbStateChanged.sendMessage(resultMsg);
                    return;
                }

                try {
                    port.open(connection);
                    port.setParameters(_baudRate, _dataBits, _stopBits, _parity);
                    port.setDTR(_enableDtr);
                    port.setRTS(_enableRts);
                } catch (IOException e) {
                    resultMsg.what = USB_NOT_FOUND;
                    onUsbStateChanged.sendMessage(resultMsg);
                    return;
                }
                _usbPort = port;
                _usbDeviceName = port.getClass().getSimpleName().replace("SerialDriver","");
                resultMsg.what = USB_CONNECTED;
                onUsbStateChanged.sendMessage(resultMsg);
            }
        }.start();
    }

    private final Handler onUsbStateChanged = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            String toastMsg;
            if (msg.what == USB_CONNECTED) {
                UsbPortHandler.setPort(_usbPort);

                toastMsg = String.format("USB connected %s", _usbDeviceName);
                Toast.makeText(getBaseContext(), toastMsg, Toast.LENGTH_SHORT).show();

                Intent resultIntent = new Intent();
                resultIntent.putExtra("name", _usbDeviceName);
                setResult(Activity.RESULT_OK, resultIntent);
            } else {
                setResult(Activity.RESULT_CANCELED);
            }
            finish();
        }
    };
}
