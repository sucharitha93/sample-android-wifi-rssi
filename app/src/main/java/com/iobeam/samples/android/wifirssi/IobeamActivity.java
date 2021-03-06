package com.iobeam.samples.android.wifirssi;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.MenuItem;
import android.widget.TextView;

import com.iobeam.api.ApiException;
import com.iobeam.api.client.Iobeam;
import com.iobeam.api.client.RegisterCallback;
import com.iobeam.api.client.RestRequest;
import com.iobeam.api.client.SendCallback;
import com.iobeam.api.resource.DataStore;
import com.iobeam.api.resource.ImportBatch;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * This is an example activity of how to use the iobeam Java client library. This example will
 * measure the RSSI of the WiFi at periodic intervals, batch them into an import request, and import
 * that data periodically. By default, readings are taken every 20s and are send to iobeam
 * once there are 3 readings.
 */
public class IobeamActivity extends AppCompatActivity implements Handler.Callback {

    private static final String LOG_TAG = "IobeamActivity";

    private static final String SERIES_NAME = "rssi";
    private static final String KEY_DEVICE_ID = "device_id";

    /* UI control constants */
    private static final int MSG_GET_RSSI = 0;
    private static final int MSG_SEND_SUCCESS = 1;  // sent if data upload succeeds
    private static final int MSG_SEND_FAILURE = 2;  // sent if data upload fails
    private static final int MSG_REGISTER_SUCCESS = 3;  // sent if registration succeeds
    private static final int MSG_REGISTER_FAILURE = 4;  // sent if registration fails
    private static final long DELAY = TimeUnit.SECONDS.toMillis(20);  // take measurement every 20s

    private static final int BATCH_SIZE = 3;

    private static Iobeam iobeam;
    private static DataStore wifiReadings;

    private static long lastSuccess = 0;
    private static long lastFailure = 0;
    private static long totalSuccesses = 0;
    private static long totalFailures = 0;
    private static final Map<String, Long> errors = new HashMap<String, Long>();

    private static String formatDate(long msec) {
        return new SimpleDateFormat("MM/dd/yyyy - HH:mm", Locale.US).format(new Date(msec));
    }

    /* Views */
    private TextView mDeviceField;
    private TextView mFailureField;
    private TextView mSuccessField;
    private TextView mTotalFailureField;
    private TextView mTotalSuccessField;
    private TextView mErrorsField;

    private WifiManager mWifiManager;
    private Handler mHandler;
    
    private SendCallback mDataCallback;
    private String mDeviceId;
    private boolean mCanSend = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mDeviceField = (TextView) findViewById(R.id.field_device_id);
        mFailureField = (TextView) findViewById(R.id.field_last_failure);
        mSuccessField = (TextView) findViewById(R.id.field_last_success);
        if (lastFailure != 0)
            mFailureField.setText(formatDate(lastFailure));
        if (lastSuccess != 0)
            mSuccessField.setText(formatDate(lastSuccess));
        mTotalFailureField = (TextView) findViewById(R.id.field_total_failure);
        mTotalSuccessField = (TextView) findViewById(R.id.field_total_success);
        mTotalFailureField.setText(String.format(Locale.US, "%d", totalFailures));
        mTotalSuccessField.setText(String.format(Locale.US, "%d", totalSuccesses));
        mErrorsField = (TextView) findViewById(R.id.field_errors);
        mHandler = new Handler(this);

        initIobeam();

        // Setup app to get RSSI measurements and kick off the measurement loop. The Handler also
        // will be used for callbacks from registration (if needed) or from sending data.
        mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        mHandler.sendEmptyMessage(MSG_GET_RSSI);

        initDataCallback();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                return true;
            case R.id.menu_reset:
                // Not implemented for now.
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }


    /**
     * Initializes the Iobeam client library, including registering for a new device ID if needed.
     */
    private void initIobeam() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // projectId and token are defined in the res/values/iobeam_config.xml file.
        int projectId = getResources().getInteger(R.integer.iobeam_project_id);
        String token = getString(R.string.iobeam_project_token);
        mDeviceId = prefs.getString(KEY_DEVICE_ID, null);

        if (iobeam == null) {
            iobeam = new Iobeam.Builder(projectId, token)
                    .autoRetry()
                    .build();
            wifiReadings = iobeam.createDataStore(SERIES_NAME);
        }
        try {
            iobeam.registerOrSetDeviceAsync(mDeviceId, new RegisterCallback() {
                @Override
                public void onSuccess(String deviceId) {
                    mCanSend = true;
                    mDeviceId = deviceId;
                    updateDeviceId(deviceId);
                }

                @Override
                public void onFailure(Throwable throwable, RestRequest restRequest) {
                    throwable.printStackTrace();
                    mHandler.sendEmptyMessage(MSG_REGISTER_FAILURE);
                    mCanSend = false;
                    mDeviceId = null;
                }
            });
        } catch (ApiException e) {
            e.printStackTrace();
            mCanSend = false;
        }
    }

    private void updateDeviceId(String id) {
        Message m = new Message();
        m.what = MSG_REGISTER_SUCCESS;
        m.getData().putString(KEY_DEVICE_ID, id);
        mHandler.sendMessage(m);
    }

    private void initDataCallback() {
        // This callback notifies mHandler of success or failure.
        mDataCallback = new SendCallback() {
            @Override
            public void onSuccess(ImportBatch data) {
                mHandler.sendEmptyMessage(MSG_SEND_SUCCESS);
            }

            @Override
            public void onFailure(Throwable t, ImportBatch req) {
                t.printStackTrace();
                String key = String.format("%s: %s", t.getClass().getSimpleName(),
                        t.getMessage().substring(0, 20));

                Long cnt = errors.get(key);
                if (cnt == null) {
                    cnt = 0L;
                }
                cnt++;
                errors.put(key, cnt);
                mHandler.sendEmptyMessage(MSG_SEND_FAILURE);
            }
        };
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeMessages(MSG_GET_RSSI);
        mHandler.removeMessages(MSG_SEND_SUCCESS);
        mHandler.removeMessages(MSG_SEND_FAILURE);
        mHandler = null;
    }

    /**
     * Adds an RSSI reading to iobeam for future sending. Readings are sent when iobeam's data store
     * contains more than `BATCH_SIZE` points.
     *
     * @param rssi RSSI value to be added
     */
    private void addDataPoint(int rssi) {
        Log.v(LOG_TAG, "rssi: " + rssi);
        wifiReadings.add(new String[]{SERIES_NAME}, new Object[]{rssi});

        if (mCanSend && iobeam.getDataSize() >= BATCH_SIZE) {
            iobeam.sendAsync(mDataCallback);
        }
    }

    @Override
    public boolean handleMessage(Message m) {
        switch (m.what) {
            case MSG_GET_RSSI:
                mHandler.removeMessages(MSG_GET_RSSI);  // remove spurious messages
                // Get current RSSI value, add data point, then schedule next reading.
                if (mWifiManager.getConnectionInfo().getBSSID() != null) {
                    int rssi = mWifiManager.getConnectionInfo().getRssi();
                    addDataPoint(rssi);
                } else {
                    Log.v(LOG_TAG, "Not connected to wifi, skipping...");
                }
                mHandler.sendEmptyMessageDelayed(MSG_GET_RSSI, DELAY);
                return true;
            case MSG_SEND_SUCCESS:
            case MSG_SEND_FAILURE:
                boolean success = m.what == MSG_SEND_SUCCESS;
                Log.d(LOG_TAG, "Send suceeded: " + success);
                // Update stats and then update UI to reflect latest.
                if (success) {
                    totalSuccesses++;
                    lastSuccess = System.currentTimeMillis();
                }
                else {
                    totalFailures++;
                    lastFailure = System.currentTimeMillis();
                    if (errors.size() > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (String s : errors.keySet()) {
                            sb.append(s);
                            sb.append(" (");
                            sb.append(errors.get(s));
                            sb.append(")\n");
                        }
                        mErrorsField.setText(sb.toString());
                    }
                }

                String countStr = Long.toString(success ? totalSuccesses : totalFailures);
                String timeStr = formatDate(success ? lastSuccess : lastFailure);
                (success ? mSuccessField : mFailureField).setText(timeStr);
                (success ? mTotalSuccessField : mTotalFailureField).setText(countStr);
                return true;
            case MSG_REGISTER_SUCCESS:
            case MSG_REGISTER_FAILURE:
                Bundle data = m.getData();
                boolean registered = data != null && data.getString(KEY_DEVICE_ID) != null;
                Log.d(LOG_TAG, "Registration succeeded: " + registered);
                if (registered) {
                    // The iobeam client persists the device ID, but we do as well.
                    String deviceId = m.getData().getString(KEY_DEVICE_ID);
                    PreferenceManager.getDefaultSharedPreferences(this).edit()
                            .putString(KEY_DEVICE_ID, deviceId).apply();
                    mDeviceField.setText(deviceId);
                } else {
                    mDeviceField.setText(getString(R.string.error));
                }
                return true;
            default:
                return false;
        }
    }
}
