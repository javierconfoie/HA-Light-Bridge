package com.ys.serialportdemo;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    private EditText hostEdit, portEdit, userEdit, passEdit, deviceIdEdit;
    private TextView statusText;
    private Button saveButton, disconnectButton, testUiButton;

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

        loadSettings();

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
}
