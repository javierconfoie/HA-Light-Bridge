package com.ys.serialportdemo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import com.ys.serialport.LightController;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MqttLightService extends Service {
    private static final String TAG = "MqttLightService";
    private static final String CHANNEL_ID = "mqtt_light_service";
    private static final int NOTIFICATION_ID = 1;
    private static final String DEFAULT_SERIAL_DEVICE = "/dev/ttyS3";
    private static final int BAUD_RATE = 9600;
    public static final String PREFS_NAME = "mqtt_settings";
    private static final String STATE_PREFS = "light_state";

    private String serialDevice = DEFAULT_SERIAL_DEVICE;

    private MqttClient mqttClient;
    private Handler ledHandler;
    private HandlerThread ledThread;
    private PowerManager.WakeLock wakeLock;
    private boolean deviceOpen = false;

    // Current state
    private boolean lightOn = false;
    private int red = 255, green = 255, blue = 255;
    private int brightness = 255;
    private String currentEffect = "solid";
    private int breathingSpeed = 5; // 1 (slowest) to 10 (fastest)

    // MQTT topics (set dynamically based on device ID)
    private String topicPrefix;
    private String commandTopic;
    private String stateTopic;
    private String availabilityTopic;
    private String discoveryTopic;
    private String speedCommandTopic;
    private String speedStateTopic;
    private String speedDiscoveryTopic;

    // Brightness control topics
    private String screenBrightnessCommandTopic;
    private String screenBrightnessStateTopic;
    private String screenBrightnessDiscoveryTopic;
    private String screensaverBrightnessCommandTopic;
    private String screensaverBrightnessStateTopic;
    private String screensaverBrightnessDiscoveryTopic;
    private String screensaverSensorStateTopic;
    private String screensaverSensorDiscoveryTopic;

    // Brightness control state
    private boolean brightnessEnabled = false;
    private String fullyPassword = "";
    private int screenBrightness = 128;
    private int screensaverBrightness = 10;
    private boolean lastScreensaverState = false;
    private long lastUserInteractionTime = 0;
    private volatile boolean pollingRunning = false;
    private Thread pollingThread = null;

    private volatile boolean reconnecting = false;
    private volatile boolean effectRunning = false;
    private Thread effectThread = null;

    @Override
    public void onCreate() {
        super.onCreate();
        ledThread = new HandlerThread("LEDControl");
        ledThread.start();
        ledHandler = new Handler(ledThread.getLooper());

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "halightbridge:mqtt");
        wakeLock.acquire();

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."));

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String host = prefs.getString("host", "");
        int port = prefs.getInt("port", 1883);
        String deviceId = prefs.getString("device_id", "tablet_led");
        serialDevice = prefs.getString("serial_port", DEFAULT_SERIAL_DEVICE);

        if (host.isEmpty()) {
            updateNotification("No MQTT broker configured");
            return START_STICKY;
        }

        topicPrefix = deviceId;
        commandTopic = topicPrefix + "/light/set";
        stateTopic = topicPrefix + "/light/state";
        availabilityTopic = topicPrefix + "/light/availability";
        discoveryTopic = "homeassistant/light/" + deviceId + "/config";
        speedCommandTopic = topicPrefix + "/breathing_speed/set";
        speedStateTopic = topicPrefix + "/breathing_speed/state";
        speedDiscoveryTopic = "homeassistant/number/" + deviceId + "_breathing_speed/config";

        // Brightness control topics
        screenBrightnessCommandTopic = topicPrefix + "/screen_brightness/set";
        screenBrightnessStateTopic = topicPrefix + "/screen_brightness/state";
        screenBrightnessDiscoveryTopic = "homeassistant/number/" + deviceId + "_screen_brightness/config";
        screensaverBrightnessCommandTopic = topicPrefix + "/screensaver_brightness/set";
        screensaverBrightnessStateTopic = topicPrefix + "/screensaver_brightness/state";
        screensaverBrightnessDiscoveryTopic = "homeassistant/number/" + deviceId + "_screensaver_brightness/config";
        screensaverSensorStateTopic = topicPrefix + "/screensaver/state";
        screensaverSensorDiscoveryTopic = "homeassistant/binary_sensor/" + deviceId + "_screensaver/config";

        // Load brightness settings
        brightnessEnabled = prefs.getBoolean("brightness_enabled", false);
        fullyPassword = prefs.getString("fully_password", "");

        restoreState();
        openSerialDevice();
        connectMqtt(host, port, prefs.getString("user", ""), prefs.getString("pass", ""));

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopFullyPolling();
        disconnectMqtt();
        closeSerialDevice();
        if (ledThread != null) {
            ledThread.quitSafely();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    private void openSerialDevice() {
        ledHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    LightController.getInstance().close();
                    LightController.getInstance().openDevice(MqttLightService.this, serialDevice, BAUD_RATE);
                    deviceOpen = true;
                    Log.i(TAG, "Serial device opened: " + serialDevice);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to open serial device", e);
                    deviceOpen = false;
                }
            }
        });
    }

    private void closeSerialDevice() {
        try {
            LightController.getInstance().close();
            deviceOpen = false;
        } catch (Exception e) {
            Log.e(TAG, "Error closing serial device", e);
        }
    }

    private void connectMqtt(final String host, final int port, final String user, final String pass) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String brokerUri = "tcp://" + host + ":" + port;
                    String clientId = "ha_light_bridge_" + Build.SERIAL;

                    mqttClient = new MqttClient(brokerUri, clientId, new MemoryPersistence());
                    mqttClient.setCallback(new MqttCallback() {
                        @Override
                        public void connectionLost(Throwable cause) {
                            Log.w(TAG, "MQTT connection lost", cause);
                            updateNotification("Disconnected - reconnecting...");
                            broadcastStatus("disconnected");
                            scheduleReconnect(host, port, user, pass);
                        }

                        @Override
                        public void messageArrived(String topic, MqttMessage message) {
                            String payload = new String(message.getPayload());
                            if (topic.equals(speedCommandTopic)) {
                                handleSpeedCommand(payload);
                            } else if (topic.equals(screenBrightnessCommandTopic)) {
                                handleScreenBrightnessCommand(payload);
                            } else if (topic.equals(screensaverBrightnessCommandTopic)) {
                                handleScreensaverBrightnessCommand(payload);
                            } else {
                                handleCommand(payload);
                            }
                        }

                        @Override
                        public void deliveryComplete(IMqttDeliveryToken token) {
                        }
                    });

                    MqttConnectOptions options = new MqttConnectOptions();
                    options.setCleanSession(true);
                    options.setAutomaticReconnect(false);
                    options.setKeepAliveInterval(30);
                    options.setConnectionTimeout(10);

                    if (user != null && !user.isEmpty()) {
                        options.setUserName(user);
                        if (pass != null && !pass.isEmpty()) {
                            options.setPassword(pass.toCharArray());
                        }
                    }

                    // Set LWT (Last Will and Testament) for offline detection
                    options.setWill(availabilityTopic, "offline".getBytes(), 1, true);

                    updateNotification("Connecting...");
                    broadcastStatus("connecting");
                    mqttClient.connect(options);

                    Log.i(TAG, "MQTT connected to " + brokerUri);
                    updateNotification("Connected to " + host);
                    broadcastStatus("connected");
                    reconnecting = false;

                    // Subscribe to command topics
                    mqttClient.subscribe(commandTopic, 1);
                    mqttClient.subscribe(speedCommandTopic, 1);
                    if (brightnessEnabled) {
                        mqttClient.subscribe(screenBrightnessCommandTopic, 1);
                        mqttClient.subscribe(screensaverBrightnessCommandTopic, 1);
                    }

                    // Publish discovery config
                    publishDiscovery();

                    // Publish availability
                    publish(availabilityTopic, "online", true);

                    // Restore last light state and publish
                    publishState();
                    publishSpeedState();
                    if (brightnessEnabled) {
                        publishScreenBrightnessState();
                        publishScreensaverBrightnessState();
                        startFullyPolling();
                    }
                    if (lightOn) {
                        ledHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                applyLightState();
                            }
                        });
                    }

                } catch (MqttException e) {
                    Log.e(TAG, "MQTT connection failed", e);
                    updateNotification("Connection failed: " + e.getMessage());
                    broadcastStatus("error");
                    scheduleReconnect(host, port, user, pass);
                }
            }
        }).start();
    }

    private void scheduleReconnect(final String host, final int port, final String user, final String pass) {
        if (reconnecting) return;
        reconnecting = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    return;
                }
                if (reconnecting) {
                    reconnecting = false;
                    connectMqtt(host, port, user, pass);
                }
            }
        }).start();
    }

    private void disconnectMqtt() {
        reconnecting = false;
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                publish(availabilityTopic, "offline", true);
                mqttClient.disconnect();
            }
        } catch (MqttException e) {
            Log.e(TAG, "MQTT disconnect error", e);
        }
    }

    private void publishDiscovery() {
        try {
            JSONObject config = new JSONObject();
            config.put("name", "Tablet LED Strip");
            config.put("unique_id", topicPrefix + "_light");
            config.put("object_id", topicPrefix + "_light");
            config.put("command_topic", commandTopic);
            config.put("state_topic", stateTopic);
            config.put("availability_topic", availabilityTopic);
            config.put("schema", "json");
            config.put("brightness", true);

            JSONArray colorModes = new JSONArray();
            colorModes.put("rgb");
            config.put("supported_color_modes", colorModes);

            config.put("effect", true);
            JSONArray effects = new JSONArray();
            effects.put("solid");
            effects.put("breathing");
            effects.put("flash");
            effects.put("crazy");
            config.put("effect_list", effects);

            JSONObject device = new JSONObject();
            JSONArray identifiers = new JSONArray();
            identifiers.put(topicPrefix + "_device");
            device.put("identifiers", identifiers);
            device.put("name", "Tablet LED Controller");
            device.put("model", "YS Serial LED Strip");
            device.put("manufacturer", "YS");
            config.put("device", device);

            publish(discoveryTopic, config.toString(), true);
            Log.i(TAG, "Published HA discovery config");

            // Publish breathing speed number entity discovery
            JSONObject speedConfig = new JSONObject();
            speedConfig.put("name", "Breathing Speed");
            speedConfig.put("unique_id", topicPrefix + "_breathing_speed");
            speedConfig.put("object_id", topicPrefix + "_breathing_speed");
            speedConfig.put("command_topic", speedCommandTopic);
            speedConfig.put("state_topic", speedStateTopic);
            speedConfig.put("availability_topic", availabilityTopic);
            speedConfig.put("min", 1);
            speedConfig.put("max", 10);
            speedConfig.put("step", 1);
            speedConfig.put("icon", "mdi:speedometer");
            speedConfig.put("device", device);

            publish(speedDiscoveryTopic, speedConfig.toString(), true);
            Log.i(TAG, "Published HA breathing speed discovery config");

            // Publish brightness control discovery if enabled
            if (brightnessEnabled) {
                publishBrightnessDiscovery(device);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error publishing discovery", e);
        }
    }

    private void publishState() {
        try {
            JSONObject state = new JSONObject();
            state.put("state", lightOn ? "ON" : "OFF");
            state.put("brightness", brightness);
            state.put("color_mode", "rgb");

            JSONObject color = new JSONObject();
            color.put("r", red);
            color.put("g", green);
            color.put("b", blue);
            state.put("color", color);

            state.put("effect", currentEffect);

            publish(stateTopic, state.toString(), true);
        } catch (Exception e) {
            Log.e(TAG, "Error publishing state", e);
        }
    }

    private void publishSpeedState() {
        publish(speedStateTopic, String.valueOf(breathingSpeed), true);
    }

    private void handleSpeedCommand(final String payload) {
        ledHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    int speed = Math.round(Float.parseFloat(payload.trim()));
                    if (speed < 1) speed = 1;
                    if (speed > 10) speed = 10;
                    breathingSpeed = speed;
                    saveState();
                    publishSpeedState();
                    // Restart breathing if currently active
                    if (lightOn && "breathing".equals(currentEffect)) {
                        applyLightState();
                    }
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Invalid speed value: " + payload, e);
                }
            }
        });
    }

    private void publish(String topic, String payload, boolean retained) {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                MqttMessage msg = new MqttMessage(payload.getBytes());
                msg.setQos(1);
                msg.setRetained(retained);
                mqttClient.publish(topic, msg);
            }
        } catch (MqttException e) {
            Log.e(TAG, "Error publishing to " + topic, e);
        }
    }

    private void handleCommand(final String payload) {
        ledHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject cmd = new JSONObject(payload);

                    // Handle state
                    if (cmd.has("state")) {
                        String state = cmd.getString("state");
                        if ("OFF".equals(state)) {
                            turnOff();
                            saveState();
                            publishState();
                            return;
                        }
                        lightOn = true;
                    }

                    // Handle brightness
                    if (cmd.has("brightness")) {
                        brightness = cmd.getInt("brightness");
                    }

                    // Handle color
                    if (cmd.has("color")) {
                        JSONObject color = cmd.getJSONObject("color");
                        red = color.getInt("r");
                        green = color.getInt("g");
                        blue = color.getInt("b");
                    }

                    // Handle effect
                    if (cmd.has("effect")) {
                        currentEffect = cmd.getString("effect");
                    }

                    // Apply the command
                    if (lightOn) {
                        applyLightState();
                    }

                    saveState();
                    publishState();
                } catch (Exception e) {
                    Log.e(TAG, "Error handling command: " + payload, e);
                }
            }
        });
    }

    private void stopEffect() {
        effectRunning = false;
        if (effectThread != null) {
            try {
                effectThread.join(3000);
            } catch (InterruptedException ignored) {
            }
            effectThread = null;
        }
    }

    private void turnOff() {
        lightOn = false;
        stopEffect();
        if (!deviceOpen) return;
        try {
            List<LightController.Led> allLeds = new ArrayList<>();
            allLeds.add(LightController.Led.RED);
            allLeds.add(LightController.Led.GREEN);
            allLeds.add(LightController.Led.BLUE);
            LightController.getInstance().close(allLeds);
        } catch (Exception e) {
            Log.e(TAG, "Error turning off LEDs", e);
        }
    }

    private void setColor(int r, int g, int b) {
        // Hardware mapping: LED.GREEN = physical Red, LED.BLUE = physical Green, LED.RED = physical Blue
        LightController.getInstance().keepMode(LightController.Led.GREEN, 0, gammaCorrect(r));
        SystemClock.sleep(40);
        LightController.getInstance().keepMode(LightController.Led.BLUE, 0, gammaCorrect(g));
        SystemClock.sleep(40);
        LightController.getInstance().keepMode(LightController.Led.RED, 0, gammaCorrect(b));
    }

    private void setChannel(LightController.Led led, int value) {
        LightController.getInstance().keepMode(led, 0, gammaCorrect(value));
        SystemClock.sleep(40);
    }

    private static int gammaCorrect(int value) {
        if (value <= 0) return 0;
        if (value >= 255) return 255;
        return (int) (Math.pow(value / 255.0, 2.2) * 255.0 + 0.5);
    }

    private void applyLightState() {
        if (!deviceOpen) return;

        stopEffect();

        // Scale RGB by brightness
        final int scaledR = (red * brightness) / 255;
        final int scaledG = (green * brightness) / 255;
        final int scaledB = (blue * brightness) / 255;

        try {
            if ("solid".equals(currentEffect)) {
                setColor(scaledR, scaledG, scaledB);
            } else if ("breathing".equals(currentEffect)) {
                effectRunning = true;
                effectThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        // Determine which channels are active
                        final boolean useR = scaledR > 0;
                        final boolean useG = scaledG > 0;
                        final boolean useB = scaledB > 0;

                        // Turn off inactive channels once at the start
                        if (!useR) setChannel(LightController.Led.GREEN, 0);
                        if (!useG) setChannel(LightController.Led.BLUE, 0);
                        if (!useB) setChannel(LightController.Led.RED, 0);

                        // Speed 1=30 steps (slowest), speed 10=3 steps (fastest)
                        final int steps = Math.max(3, 33 - (breathingSpeed * 3));
                        while (effectRunning && deviceOpen) {
                            // Fade in
                            for (int i = 0; i <= steps && effectRunning; i++) {
                                float factor = (float) i / steps;
                                if (useR) setChannel(LightController.Led.GREEN, (int) (scaledR * factor));
                                if (useG) setChannel(LightController.Led.BLUE, (int) (scaledG * factor));
                                if (useB) setChannel(LightController.Led.RED, (int) (scaledB * factor));
                            }
                            // Fade out
                            for (int i = steps; i >= 0 && effectRunning; i--) {
                                float factor = (float) i / steps;
                                if (useR) setChannel(LightController.Led.GREEN, (int) (scaledR * factor));
                                if (useG) setChannel(LightController.Led.BLUE, (int) (scaledG * factor));
                                if (useB) setChannel(LightController.Led.RED, (int) (scaledB * factor));
                            }
                        }
                    }
                });
                effectThread.start();
            } else if ("flash".equals(currentEffect)) {
                setColor(scaledR, scaledG, scaledB);
                SystemClock.sleep(40);
                LightController.getInstance().flashMode(5);
            } else if ("crazy".equals(currentEffect)) {
                LightController.getInstance().crazyMode(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error applying light state", e);
        }
    }

    private void saveState() {
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
                .putBoolean("on", lightOn)
                .putInt("r", red)
                .putInt("g", green)
                .putInt("b", blue)
                .putInt("brightness", brightness)
                .putString("effect", currentEffect)
                .putInt("breathing_speed", breathingSpeed)
                .putInt("screen_brightness", screenBrightness)
                .putInt("screensaver_brightness", screensaverBrightness)
                .apply();
    }

    private void restoreState() {
        SharedPreferences sp = getSharedPreferences(STATE_PREFS, MODE_PRIVATE);
        if (!sp.contains("on")) return;
        lightOn = sp.getBoolean("on", false);
        red = sp.getInt("r", 255);
        green = sp.getInt("g", 255);
        blue = sp.getInt("b", 255);
        brightness = sp.getInt("brightness", 255);
        currentEffect = sp.getString("effect", "solid");
        breathingSpeed = sp.getInt("breathing_speed", 5);
        screenBrightness = sp.getInt("screen_brightness", 128);
        screensaverBrightness = sp.getInt("screensaver_brightness", 10);
    }

    private void broadcastStatus(String status) {
        Intent intent = new Intent("com.ys.halightbridge.MQTT_STATUS");
        intent.putExtra("status", status);
        sendBroadcast(intent);
    }

    // ======================== Brightness Control ========================

    private void publishBrightnessDiscovery(JSONObject device) {
        try {
            // Screen brightness number entity
            JSONObject screenBrtConfig = new JSONObject();
            screenBrtConfig.put("name", "Screen Brightness");
            screenBrtConfig.put("unique_id", topicPrefix + "_screen_brightness");
            screenBrtConfig.put("object_id", topicPrefix + "_screen_brightness");
            screenBrtConfig.put("command_topic", screenBrightnessCommandTopic);
            screenBrtConfig.put("state_topic", screenBrightnessStateTopic);
            screenBrtConfig.put("availability_topic", availabilityTopic);
            screenBrtConfig.put("min", 1);
            screenBrtConfig.put("max", 255);
            screenBrtConfig.put("step", 1);
            screenBrtConfig.put("icon", "mdi:brightness-6");
            screenBrtConfig.put("device", device);
            publish(screenBrightnessDiscoveryTopic, screenBrtConfig.toString(), true);

            // Screensaver brightness number entity
            JSONObject ssaverBrtConfig = new JSONObject();
            ssaverBrtConfig.put("name", "Screensaver Brightness");
            ssaverBrtConfig.put("unique_id", topicPrefix + "_screensaver_brightness");
            ssaverBrtConfig.put("object_id", topicPrefix + "_screensaver_brightness");
            ssaverBrtConfig.put("command_topic", screensaverBrightnessCommandTopic);
            ssaverBrtConfig.put("state_topic", screensaverBrightnessStateTopic);
            ssaverBrtConfig.put("availability_topic", availabilityTopic);
            ssaverBrtConfig.put("min", 1);
            ssaverBrtConfig.put("max", 255);
            ssaverBrtConfig.put("step", 1);
            ssaverBrtConfig.put("icon", "mdi:brightness-4");
            ssaverBrtConfig.put("device", device);
            publish(screensaverBrightnessDiscoveryTopic, ssaverBrtConfig.toString(), true);

            // Screensaver active binary sensor
            JSONObject sensorConfig = new JSONObject();
            sensorConfig.put("name", "Screensaver");
            sensorConfig.put("unique_id", topicPrefix + "_screensaver");
            sensorConfig.put("object_id", topicPrefix + "_screensaver");
            sensorConfig.put("state_topic", screensaverSensorStateTopic);
            sensorConfig.put("availability_topic", availabilityTopic);
            sensorConfig.put("payload_on", "ON");
            sensorConfig.put("payload_off", "OFF");
            sensorConfig.put("icon", "mdi:monitor-eye");
            sensorConfig.put("device", device);
            publish(screensaverSensorDiscoveryTopic, sensorConfig.toString(), true);

            Log.i(TAG, "Published brightness control discovery configs");
        } catch (Exception e) {
            Log.e(TAG, "Error publishing brightness discovery", e);
        }
    }

    private void publishScreenBrightnessState() {
        publish(screenBrightnessStateTopic, String.valueOf(screenBrightness), true);
    }

    private void publishScreensaverBrightnessState() {
        publish(screensaverBrightnessStateTopic, String.valueOf(screensaverBrightness), true);
    }

    private void publishScreensaverSensorState(boolean active) {
        publish(screensaverSensorStateTopic, active ? "ON" : "OFF", true);
    }

    private void handleScreenBrightnessCommand(final String payload) {
        try {
            int value = Math.round(Float.parseFloat(payload.trim()));
            if (value < 1) value = 1;
            if (value > 255) value = 255;
            screenBrightness = value;
            saveState();
            publishScreenBrightnessState();
            // Apply immediately if screensaver is NOT active
            if (!lastScreensaverState) {
                setSystemBrightness(screenBrightness);
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid screen brightness value: " + payload, e);
        }
    }

    private void handleScreensaverBrightnessCommand(final String payload) {
        try {
            int value = Math.round(Float.parseFloat(payload.trim()));
            if (value < 1) value = 1;
            if (value > 255) value = 255;
            screensaverBrightness = value;
            saveState();
            publishScreensaverBrightnessState();
            // Apply immediately if screensaver IS active
            if (lastScreensaverState) {
                setSystemBrightness(screensaverBrightness);
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid screensaver brightness value: " + payload, e);
        }
    }

    private void setSystemBrightness(int value) {
        try {
            ContentResolver resolver = getContentResolver();
            // Disable auto-brightness first
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            // Set brightness (0-255)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value);
            Log.d(TAG, "Set system brightness to " + value);
        } catch (Exception e) {
            Log.e(TAG, "Error setting system brightness", e);
        }
    }

    private void startFullyPolling() {
        if (pollingRunning) return;
        pollingRunning = true;
        pollingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "Fully screensaver polling started");
                while (pollingRunning) {
                    try {
                        JSONObject info = queryFullyDeviceInfo();
                        if (info == null) {
                            Thread.sleep(5000);
                            continue;
                        }

                        boolean screensaverActive = info.optBoolean("isInScreensaver", false);
                        long interactionTime = info.optLong("lastUserInteractionTime", 0);

                        // Anticipate screensaver exit: user tapped while screensaver is active
                        if (screensaverActive && lastScreensaverState && interactionTime != lastUserInteractionTime) {
                            Log.i(TAG, "User interaction detected during screensaver, pre-applying screen brightness");
                            setSystemBrightness(screenBrightness);
                        }

                        lastUserInteractionTime = interactionTime;

                        if (screensaverActive != lastScreensaverState) {
                            lastScreensaverState = screensaverActive;
                            Log.i(TAG, "Screensaver state changed: " + screensaverActive);
                            publishScreensaverSensorState(screensaverActive);
                            if (screensaverActive) {
                                setSystemBrightness(screensaverBrightness);
                            } else {
                                setSystemBrightness(screenBrightness);
                            }
                        }
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "Error in Fully polling", e);
                        try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
                    }
                }
                Log.i(TAG, "Fully screensaver polling stopped");
            }
        });
        pollingThread.start();
    }

    private void stopFullyPolling() {
        pollingRunning = false;
        if (pollingThread != null) {
            pollingThread.interrupt();
            try {
                pollingThread.join(3000);
            } catch (InterruptedException ignored) {
            }
            pollingThread = null;
        }
    }

    private JSONObject queryFullyDeviceInfo() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://localhost:2323/?cmd=deviceInfo&type=json&password=" + fullyPassword);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return new JSONObject(sb.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying Fully API", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    // Notification helpers
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "MQTT Light Service", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Persistent notification for LED MQTT bridge");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, SettingsActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle(getString(R.string.mqtt_notification_title))
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
