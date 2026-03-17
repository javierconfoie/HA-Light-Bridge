package com.ys.serialportdemo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.ys.serialport.LightController;
import com.ys.serialport.SerialPort;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements View.OnClickListener,
        RadioGroup.OnCheckedChangeListener, CompoundButton.OnCheckedChangeListener {
    private Button devNameBt, devRateBt, devBt, liveSetBt, flashSetbt, stateBt, helpBt,
            crazySetBt, keepSetBt, closeSetBt, resumeBt;
    private EditText liveEdit, flashEdit, crazyEdit, keepEdit, keepLightEdit;
    private RadioGroup liveGroup, keepGroup;
    private CheckBox redCloseBox, greenCloseBox, blueCloseBox;
    private TextView stateTxt, helpTxt;
    private AlertDialog mDevicesDialog, mRateDialog;
    private String curDevice;
    private int curRate;
    private LightController.Led curKeepLed = LightController.Led.NULL,
            curLiveLed = LightController.Led.NULL;
    private List<LightController.Led> closeLeds;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        devNameBt = findViewById(R.id.light_dev);
        devRateBt = findViewById(R.id.light_rate);
        devBt = findViewById(R.id.light_bt);
        liveSetBt = findViewById(R.id.light_live_bt);
        flashSetbt = findViewById(R.id.light_flash_bt);
        stateBt = findViewById(R.id.light_state_bt);
        helpBt = findViewById(R.id.light_help_bt);
        crazySetBt = findViewById(R.id.light_crazy_bt);
        keepSetBt = findViewById(R.id.light_keep_bt);
        closeSetBt = findViewById(R.id.light_close_bt);
        resumeBt = findViewById(R.id.light_state_resume_bt);

        liveEdit = findViewById(R.id.light_live_edit);
        flashEdit = findViewById(R.id.light_flash_edit);
        crazyEdit = findViewById(R.id.light_crazy_edit);
        keepEdit = findViewById(R.id.light_keep_edit);
        keepLightEdit = findViewById(R.id.light_keep_light_edit);

        stateTxt = findViewById(R.id.light_state_txt);
        helpTxt = findViewById(R.id.light_help_txt);

        keepGroup = findViewById(R.id.light_keep_group);
        liveGroup = findViewById(R.id.light_live_group);

        redCloseBox = findViewById(R.id.light_close_red);
        greenCloseBox = findViewById(R.id.light_close_green);
        blueCloseBox = findViewById(R.id.light_close_blue);

        keepGroup.setOnCheckedChangeListener(this);
        liveGroup.setOnCheckedChangeListener(this);

        redCloseBox.setOnCheckedChangeListener(this);
        greenCloseBox.setOnCheckedChangeListener(this);
        blueCloseBox.setOnCheckedChangeListener(this);

        devNameBt.setOnClickListener(this);
        devRateBt.setOnClickListener(this);
        devBt.setOnClickListener(this);
        liveSetBt.setOnClickListener(this);
        flashSetbt.setOnClickListener(this);
        crazySetBt.setOnClickListener(this);
        keepSetBt.setOnClickListener(this);
        closeSetBt.setOnClickListener(this);
        resumeBt.setOnClickListener(this);
        stateBt.setOnClickListener(this);
        helpBt.setOnClickListener(this);

        ratesDialog();
        devicesDialog();

        closeLeds = new ArrayList<>();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Device lifecycle is now managed by MqttLightService
    }

    @Override
    public void onClick(View view) {
        if (view == devNameBt) {
            if (mDevicesDialog != null) mDevicesDialog.show();
        } else if (view == devRateBt) {
            if (mRateDialog != null) mRateDialog.show();
        } else if (view == devBt) {
            LightController.getInstance().close();
            if (devBt.getText().equals(getResources().getString(R.string.open))) {
                curDevice = devNameBt.getText().toString();
                curRate = Integer.parseInt(devRateBt.getText().toString());
                LightController.getInstance().openDevice(this, curDevice, curRate);
                devBt.setText(R.string.close);
            } else {
                devBt.setText(R.string.open);
            }
        } else if (view == liveSetBt) {
            Editable text = liveEdit.getText();
            if (TextUtils.isEmpty(text)) return;
            LightController.getInstance().liveMode(curLiveLed, Integer.parseInt(text.toString()));
        } else if (view == keepSetBt) {
            Editable text = keepEdit.getText();
            if (TextUtils.isEmpty(text)) return;
            String strL = keepLightEdit.getText().toString();
            if (TextUtils.isEmpty(strL)) return;
            int light = Integer.parseInt(strL);
            if (light < 0 || light > 255) {
                Toast.makeText(this, R.string.light_range_tip, Toast.LENGTH_SHORT).show();
                return;
            }
            LightController.getInstance().keepMode(curKeepLed, Integer.parseInt(text.toString()), light);
        } else if (view == closeSetBt) {
            LightController.getInstance().close(closeLeds);
        } else if (view == flashSetbt) {
            Editable text = flashEdit.getText();
            if (TextUtils.isEmpty(text)) return;
            LightController.getInstance().flashMode(Integer.parseInt(text.toString()));
        } else if (view == crazySetBt) {
            Editable text = crazyEdit.getText();
            if (TextUtils.isEmpty(text)) return;
            LightController.getInstance().crazyMode(Integer.parseInt(text.toString()));
        } else if (view == resumeBt) {
            LightController.getInstance().resume();
        } else if (view == stateBt) {
            LightController.getInstance().getStatus(new LightController.LightState() {
                @Override
                public void onLightResult(String state, final String result) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            stateTxt.setText(result);
                        }
                    });
                }
            });
        } else if (view == helpBt) {
            LightController.getInstance().getHelp(new LightController.LightState() {
                @Override
                public void onLightResult(String state, final String result) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            helpTxt.setText(result);
                        }
                    });
                }
            });
        }
    }

    private void ratesDialog() {
        if (mRateDialog == null) {
            curRate = Integer.parseInt(SerialPort.RATES[13]);
            devRateBt.setText(SerialPort.RATES[13]);
            AlertDialog.Builder builder = new AlertDialog.Builder(this,
                    SerialPort.RATES.length);
            builder.setSingleChoiceItems(SerialPort.RATES, 13,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            resetButton();
                            curRate = Integer.parseInt(SerialPort.RATES[which]);
                            devRateBt.setText(SerialPort.RATES[which]);
                            mRateDialog.dismiss();
                        }
                    });
            mRateDialog = builder.create();
        }
    }

    private void devicesDialog() {
        if (mDevicesDialog == null) {
            final String[] devices = SerialPort.getDevices();
            if (devices == null || devices.length == 0) return;
            curDevice = devices[0];
            devNameBt.setText(devices[0]);
            AlertDialog.Builder builder = new AlertDialog.Builder(this, devices.length);
            builder.setSingleChoiceItems(devices, 0,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            resetButton();
                            curDevice = devices[which];
                            devNameBt.setText(curDevice);
                            mDevicesDialog.dismiss();
                        }
                    });
            mDevicesDialog = builder.create();
        }
    }

    private void resetButton() {
        if (getResources().getString(R.string.close).equals(devBt.getText().toString())) {
            devBt.performClick();
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
        if (compoundButton == redCloseBox) {
            if (b) closeLeds.add(LightController.Led.GREEN);
            else closeLeds.remove(LightController.Led.GREEN);
        } else if (compoundButton == greenCloseBox) {

            if (b) closeLeds.add(LightController.Led.BLUE);
            else closeLeds.remove(LightController.Led.BLUE);
        } else if (compoundButton == blueCloseBox) {


            if (b) closeLeds.add(LightController.Led.RED);
            else closeLeds.remove(LightController.Led.RED);
        }
    }

    @Override
    public void onCheckedChanged(RadioGroup radioGroup, int i) {
        if (radioGroup == keepGroup) {
            switch (i) {
                case R.id.light_keep_red:
                    curKeepLed = LightController.Led.GREEN;

                    break;
                case R.id.light_keep_green:
                    curKeepLed = LightController.Led.BLUE;
                    break;
                case R.id.light_keep_blue:
                    curKeepLed = LightController.Led.RED;

                    break;
            }
        } else if (radioGroup == liveGroup) {
            switch (i) {
                case R.id.light_live_red:
                    curLiveLed = LightController.Led.GREEN;

                    break;
                case R.id.light_live_green:
                    curLiveLed = LightController.Led.BLUE;

                    break;
                case R.id.light_live_blue:
                    curLiveLed = LightController.Led.RED;

                    break;
            }
        }
    }

    private int colorRed = 0xFF;
    private int colorGreen = 0xFF;
    private int colorBlue = 0xFF;
    private int colorRedSub = 0xFF;
    private int colorGreenSub = 0xFF;
    private int colorBlueSub = 0xFF;

    public void onRedCut(View view) {
        colorGreen = colorGreen - 10;
//        if (colorGreen < 0) colorGreen = 0;
//        String col = "#" + (colorGreen <= 0xF ? "0" : "") + Integer.toHexString(colorGreen) + "00FF00";
//        view.setBackgroundColor(Color.parseColor(col));
//        findViewById(R.id.bt_green_add).setBackgroundColor(Color.parseColor(col));
        try {
            LightController.getInstance().keepMode(LightController.Led.GREEN, 0, colorGreen);
            colorRedSub = colorRedSub -10;
            if (colorRedSub < 0) colorRedSub = 0;
            String col = "#" + (colorRedSub <= 0xF ? "0" : "") + Integer.toHexString(colorRedSub) + "FF0000";
            view.setBackgroundColor(Color.parseColor(col));
            findViewById(R.id.bt_red_add).setBackgroundColor(Color.parseColor(col));
        }catch (Exception e){
            Log.d("ldslds",e.toString());
            e.printStackTrace();
        }


//        colorRedSub = colorRedSub -10;
    }

    public void onRedAdd(View view) {
        colorGreen = colorGreen + 10;
//        if (colorGreen > 0xFF) colorGreen = 0xFF;
//        String col = "#" + (colorGreen <= 0xF ? "0" : "") + Integer.toHexString(colorGreen) + "00FF00";
//        view.setBackgroundColor(Color.parseColor(col));
//        findViewById(R.id.bt_green_cut).setBackgroundColor(Color.parseColor(col));
        try {
            LightController.getInstance().keepMode(LightController.Led.GREEN, 0, colorGreen);
            colorRedSub = colorRedSub + 10;
            if (colorRedSub < 0) colorRedSub = 0;
            String col = "#" + (colorRedSub <= 0xF ? "0" : "") + Integer.toHexString(colorRedSub) + "FF0000";
            view.setBackgroundColor(Color.parseColor(col));
            findViewById(R.id.bt_red_cut).setBackgroundColor(Color.parseColor(col));
        }catch (Exception e){
            Log.d("ldslds",e.toString());
            e.printStackTrace();
        }

    }

    public void onGreenCut(View view) {
        colorBlue = colorBlue - 10;
//        if (colorBlue < 0) colorBlue = 0;
//        String col = "#" + (colorBlue <= 0xF ? "0" : "") + Integer.toHexString(colorBlue) + "0000FF";
//        view.setBackgroundColor(Color.parseColor(col));
//        findViewById(R.id.bt_blue_add).setBackgroundColor(Color.parseColor(col));
        LightController.getInstance().keepMode(LightController.Led.BLUE, 0, colorBlue);
        colorGreenSub = colorGreenSub - 10;
        if (colorGreenSub < 0) colorGreenSub = 0;
        String col = "#" + (colorGreenSub <= 0xF ? "0" : "") + Integer.toHexString(colorGreenSub) + "00FF00";
        view.setBackgroundColor(Color.parseColor(col));
        findViewById(R.id.bt_green_add).setBackgroundColor(Color.parseColor(col));

    }

    public void onGreenAdd(View view) {
        colorBlue = colorBlue + 10;
//        if (colorBlue > 0xFF) colorBlue = 0xFF;
//        String col = "#" + (colorBlue <= 0xF ? "0" : "") + Integer.toHexString(colorBlue) + "0000FF";
//        view.setBackgroundColor(Color.parseColor(col));
//        findViewById(R.id.bt_blue_cut).setBackgroundColor(Color.parseColor(col));
        LightController.getInstance().keepMode(LightController.Led.BLUE, 0, colorBlue);
        colorGreenSub = colorGreenSub +10;
        if (colorGreenSub > 0xFF) colorGreenSub = 0xFF;
        String col = "#" + (colorGreenSub <= 0xF ? "0" : "") + Integer.toHexString(colorGreenSub) + "00FF00";
        view.setBackgroundColor(Color.parseColor(col));
        findViewById(R.id.bt_green_cut).setBackgroundColor(Color.parseColor(col));
    }


    public void onBlueCut(View view) {
        colorRed = colorRed - 10;
//        if (colorRed < 0) colorRed = 0;
//        String col = "#" + (colorRed <= 0xF ? "0" : "") + Integer.toHexString(colorRed) + "FF0000";
//        view.setBackgroundColor(Color.parseColor(col));
//        findViewById(R.id.bt_blue_add).setBackgroundColor(Color.parseColor(col));
        LightController.getInstance().keepMode(LightController.Led.RED, 0, colorRed);
        colorBlueSub = colorBlueSub - 10;
        if (colorBlueSub < 0) colorBlueSub = 0;
        String col = "#" + (colorBlueSub <= 0xF ? "0" : "") + Integer.toHexString(colorBlueSub) + "0000FF";
        view.setBackgroundColor(Color.parseColor(col));
        findViewById(R.id.bt_blue_add).setBackgroundColor(Color.parseColor(col));

        colorBlue = colorBlue - 10;
//        if (colorBlue < 0) colorBlue = 0;
//        String col = "#" + (colorBlue <= 0xF ? "0" : "") + Integer.toHexString(colorBlue) + "0000FF";
//        view.setBackgroundColor(Color.parseColor(col));
//        findViewById(R.id.bt_blue_add).setBackgroundColor(Color.parseColor(col));
//        LightController.getInstance().keepMode(LightController.Led.BLUE, 0, colorBlue);
    }

    public void onBlueAdd(View view) {
        colorRed = colorRed + 10;
        LightController.getInstance().keepMode(LightController.Led.RED, 0, colorRed);
        colorBlueSub = colorBlueSub + 10;
        if (colorBlueSub > 0xFF) colorBlueSub = 0xFF;
        String col = "#" + (colorBlueSub <= 0xF ? "0" : "") + Integer.toHexString(colorBlueSub) + "0000FF";
        view.setBackgroundColor(Color.parseColor(col));
        findViewById(R.id.bt_blue_cut).setBackgroundColor(Color.parseColor(col));
//        LightController.getInstance().keepMode(LightController.Led.BLUE, 0, colorBlueSub);
//        colorBlue = colorBlue + 10;
//        if (colorBlue > 0xFF) colorBlue = 0xFF;
//        String col = "#" + (colorBlue <= 0xF ? "0" : "") + Integer.toHexString(colorBlue) + "0000FF";
//        view.setBackgroundColor(Color.parseColor(col));
//        findViewById(R.id.bt_blue_cut).setBackgroundColor(Color.parseColor(col));
//        LightController.getInstance().keepMode(LightController.Led.BLUE, 0, colorBlue);
    }

    public void onBlue(View view) {//蓝色
//        colorGreen=0;colorRed=0;colorBlue=255;
        colorGreen=0;colorRed=255;colorBlue=0;
        setColor();
    }
    public void onRed(View view) {//红色
//        colorGreen=0;colorRed=255;colorBlue=0;
        colorGreen=165;colorRed=0;colorBlue=0;
        setColor();
    }
    public void onGreen(View view) {//绿色
//        colorGreen=165;colorRed=0;colorBlue=0;
        colorGreen=0;colorRed=0;colorBlue=255;
        setColor();
    }
    public void onPink(View view) {//粉色
        if (colorGreen > 0xFF) colorGreen = 0xFF;
        if (colorRed > 0xFF) colorRed = 0xFF;
        if (colorBlue > 0xFF) colorBlue = 0xFF;
//        colorGreen=45;colorRed=255;colorBlue=160;
        colorGreen=165;colorRed=50;colorBlue=0;
        setColor();
    }
    public void onPyellow(View view) {//淡黄色
//        colorGreen=250;colorRed=255;colorBlue=0;
        colorGreen=255;colorRed=0;colorBlue=150;
        setColor();
    }
    public void onWhite(View view) {//白色
//        colorGreen=255;colorRed=255;colorBlue=255;
        colorGreen=255;colorRed=255;colorBlue=255;
        setColor();
    }
    public void onPurple(View view) {//紫色
//        colorGreen=0;colorRed=115;colorBlue=115;
        colorGreen=115;colorRed=115;colorBlue=0;
        setColor();
    }
    public void onOrange(View view) {//橙色
//        colorGreen=30;colorRed=255;colorBlue=0;
        colorGreen=255;colorRed=0;colorBlue=30;
        setColor();
    }
    private void setColor() {
        LightController.getInstance().keepMode(LightController.Led.RED, 0, colorRed);
        SystemClock.sleep(40);
        LightController.getInstance().keepMode(LightController.Led.GREEN, 0, colorGreen);
        SystemClock.sleep(40);
        LightController.getInstance().keepMode(LightController.Led.BLUE, 0, colorBlue);
       // SystemClock.sleep(100);
       // Log.d("lyy", "onRedCut: colorRed:"+colorRed+",colorGreen:"+colorGreen+",colorBlue:"+colorBlue);
    }
}
