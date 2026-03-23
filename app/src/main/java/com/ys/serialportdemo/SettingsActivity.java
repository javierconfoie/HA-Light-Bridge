package com.ys.serialportdemo;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SettingsActivity extends Activity {
    private EditText hostEdit, portEdit, userEdit, passEdit, deviceIdEdit, fullyPasswordEdit;
    private CheckBox brightnessEnabledCheck;
    private Spinner serialPortSpinner;
    private TextView statusText;
    private Button saveButton, disconnectButton, testUiButton;
    private ArrayAdapter<String> portAdapter;

    private static final String[] COMMON_PORTS = {
            "/dev/ttyS0", "/dev/ttyS1", "/dev/ttyS2", "/dev/ttyS3",
            "/dev/ttyS4", "/dev/ttyS5", "/dev/ttyS6", "/dev/ttyS7",
            "/dev/ttyUSB0", "/dev/ttyUSB1"
    };

    private BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("status");
            updateStatusDisplay(status);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        hostEdit = findViewById(R.id.settings_mqtt_host);
        portEdit = findViewById(R.id.settings_mqtt_port);
        userEdit = findViewById(R.id.settings_mqtt_user);
        passEdit = findViewById(R.id.settings_mqtt_pass);
        deviceIdEdit = findViewById(R.id.settings_mqtt_device_id);
        statusText = findViewById(R.id.settings_mqtt_status);
        saveButton = findViewById(R.id.settings_save_btn);
        disconnectButton = findViewById(R.id.settings_disconnect_btn);
        testUiButton = findViewById(R.id.settings_test_ui_btn);
        serialPortSpinner = findViewById(R.id.settings_serial_port);
        fullyPasswordEdit = findViewById(R.id.settings_fully_password);
        brightnessEnabledCheck = findViewById(R.id.settings_brightness_enabled);

        // Populate serial port spinner with ports that exist on this device
        List<String> availablePorts = new ArrayList<>();
        for (String port : COMMON_PORTS) {
            if (new File(port).exists()) {
                availablePorts.add(port);
            }
        }
        if (availablePorts.isEmpty()) {
            availablePorts.addAll(Arrays.asList(COMMON_PORTS));
        }
        portAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, availablePorts);
        portAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        serialPortSpinner.setAdapter(portAdapter);

        loadSettings();
        checkWriteSettingsPermission();

        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndConnect();
            }
        });

        disconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(SettingsActivity.this, MqttLightService.class));
                updateStatusDisplay("disconnected");
            }
        });

        testUiButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, MainActivity.class));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(statusReceiver, new IntentFilter("com.ys.halightbridge.MQTT_STATUS"));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(statusReceiver);
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(MqttLightService.PREFS_NAME, MODE_PRIVATE);
        hostEdit.setText(prefs.getString("host", ""));
        portEdit.setText(String.valueOf(prefs.getInt("port", 1883)));
        userEdit.setText(prefs.getString("user", ""));
        passEdit.setText(prefs.getString("pass", ""));
        deviceIdEdit.setText(prefs.getString("device_id", "tablet_led"));

        String savedPort = prefs.getString("serial_port", "/dev/ttyS3");
        int pos = portAdapter.getPosition(savedPort);
        if (pos >= 0) {
            serialPortSpinner.setSelection(pos);
        }

        fullyPasswordEdit.setText(prefs.getString("fully_password", ""));
        brightnessEnabledCheck.setChecked(prefs.getBoolean("brightness_enabled", false));
    }

    private void saveAndConnect() {
        String host = hostEdit.getText().toString().trim();
        if (TextUtils.isEmpty(host)) {
            Toast.makeText(this, "Broker host is required", Toast.LENGTH_SHORT).show();
            return;
        }

        String portStr = portEdit.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            port = 1883;
        }

        String user = userEdit.getText().toString().trim();
        String pass = passEdit.getText().toString().trim();
        String deviceId = deviceIdEdit.getText().toString().trim();
        if (TextUtils.isEmpty(deviceId)) {
            deviceId = "tablet_led";
        }

        SharedPreferences.Editor editor = getSharedPreferences(MqttLightService.PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString("host", host);
        editor.putInt("port", port);
        editor.putString("user", user);
        editor.putString("pass", pass);
        editor.putString("device_id", deviceId);
        editor.putString("serial_port", serialPortSpinner.getSelectedItem().toString());
        editor.putString("fully_password", fullyPasswordEdit.getText().toString().trim());
        editor.putBoolean("brightness_enabled", brightnessEnabledCheck.isChecked());
        editor.apply();

        // Stop existing service and restart
        stopService(new Intent(this, MqttLightService.class));
        Intent serviceIntent = new Intent(this, MqttLightService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        updateStatusDisplay("connecting");
        Toast.makeText(this, "Connecting to " + host + ":" + port, Toast.LENGTH_SHORT).show();
    }

    private void updateStatusDisplay(String status) {
        if (status == null) return;
        switch (status) {
            case "connected":
                statusText.setText(R.string.mqtt_status_connected);
                statusText.setTextColor(Color.parseColor("#4CAF50"));
                break;
            case "connecting":
                statusText.setText(R.string.mqtt_status_connecting);
                statusText.setTextColor(Color.parseColor("#FF9800"));
                break;
            case "disconnected":
                statusText.setText(R.string.mqtt_status_disconnected);
                statusText.setTextColor(Color.GRAY);
                break;
            case "error":
                statusText.setText(R.string.mqtt_status_error);
                statusText.setTextColor(Color.RED);
                break;
        }
    }

    private void checkWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(this, "Please allow Modify System Settings for brightness control", Toast.LENGTH_LONG).show();
            }
        }
    }
}
