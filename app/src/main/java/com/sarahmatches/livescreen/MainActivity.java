package com.sarahmatches.livescreen;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private Button startButton;
    private Button stopButton;
    private TextView statusText;

    private MediaProjectionManager projectionManager;

    private final Handler statusHandler =
            new Handler(Looper.getMainLooper());

    private final Runnable statusRunnable =
            new Runnable() {
                @Override
                public void run() {

                    String diagnostic =
                            getSharedPreferences(
                                    "live_screen",
                                    MODE_PRIVATE
                            ).getString(
                                    "exit_diagnostic",
                                    ""
                            );

                    if (diagnostic != null &&
                            !diagnostic.trim().isEmpty()) {

                        statusText.setText(diagnostic);

                    } else {

                        String status =
                                getSharedPreferences(
                                        "live_screen",
                                        MODE_PRIVATE
                                ).getString(
                                        "status",
                                        "Ready"
                                );

                        statusText.setText(status);
                    }

                    statusHandler.postDelayed(
                            this,
                            700
                    );
                }
            };

    private final ActivityResultLauncher<Intent>
            screenCaptureLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {

                        if (result.getResultCode() != Activity.RESULT_OK ||
                                result.getData() == null) {

                            setLocalStatus(
                                    "Screen capture cancelled"
                            );

                            startButton.setEnabled(true);

                            return;
                        }

                        startScreenService(
                                result.getResultCode(),
                                result.getData()
                        );
                    }
            );

    private final ActivityResultLauncher<String>
            notificationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> requestScreenCapture()
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(
                R.layout.activity_main
        );

        startButton =
                findViewById(
                        R.id.startButton
                );

        stopButton =
                findViewById(
                        R.id.stopButton
                );

        statusText =
                findViewById(
                        R.id.statusText
                );

        projectionManager =
                (MediaProjectionManager)
                        getSystemService(
                                MEDIA_PROJECTION_SERVICE
                        );

        startButton.setOnClickListener(
                view -> startLive()
        );

        stopButton.setOnClickListener(
                view -> stopLive()
        );

        readPreviousProcessExit();

        statusHandler.post(
                statusRunnable
        );
    }

    private void readPreviousProcessExit() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }

        try {

            ActivityManager activityManager =
                    (ActivityManager)
                            getSystemService(
                                    ACTIVITY_SERVICE
                            );

            if (activityManager == null) {
                return;
            }

            List<ApplicationExitInfo> exits =
                    activityManager
                            .getHistoricalProcessExitReasons(
                                    getPackageName(),
                                    0,
                                    5
                            );

            if (exits == null || exits.isEmpty()) {

                getSharedPreferences(
                        "live_screen",
                        MODE_PRIVATE
                )
                        .edit()
                        .remove(
                                "exit_diagnostic"
                        )
                        .apply();

                return;
            }

            ApplicationExitInfo info =
                    exits.get(0);

            StringBuilder diagnostic =
                    new StringBuilder();

            diagnostic.append(
                    "LAST PROCESS EXIT\n\n"
            );

            diagnostic.append(
                    "Reason: "
            );

            diagnostic.append(
                    getExitReasonText(
                            info.getReason()
                    )
            );

            diagnostic.append(
                    "\nReason code: "
            );

            diagnostic.append(
                    info.getReason()
            );

            diagnostic.append(
                    "\nStatus: "
            );

            diagnostic.append(
                    info.getStatus()
            );

            diagnostic.append(
                    "\nImportance: "
            );

            diagnostic.append(
                    info.getImportance()
            );

            diagnostic.append(
                    "\nDescription: "
            );

            diagnostic.append(
                    info.getDescription() == null
                            ? "none"
                            : info.getDescription()
            );

            String trace =
                    readNativeTrace(
                            info
                    );

            if (trace != null &&
                    !trace.trim().isEmpty()) {

                diagnostic.append(
                        "\n\n===== NATIVE TRACE =====\n"
                );

                diagnostic.append(
                        trace
                );

            } else {

                diagnostic.append(
                        "\n\nNative trace: unavailable"
                );
            }

            String output =
                    diagnostic.toString();

            getSharedPreferences(
                    "live_screen",
                    MODE_PRIVATE
            )
                    .edit()
                    .putString(
                            "exit_diagnostic",
                            output
                    )
                    .apply();

            statusText.setText(
                    output
            );

        } catch (Exception e) {

            String diagnostic =
                    "EXIT CHECK ERROR\n\n"
                            +
                            e.getClass().getSimpleName()
                            +
                            ": "
                            +
                            e.getMessage();

            getSharedPreferences(
                    "live_screen",
                    MODE_PRIVATE
            )
                    .edit()
                    .putString(
                            "exit_diagnostic",
                            diagnostic
                    )
                    .apply();

            statusText.setText(
                    diagnostic
            );
        }
    }

    private String readNativeTrace(
            ApplicationExitInfo info
    ) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "";
        }

        InputStream inputStream = null;
        BufferedReader reader = null;

        try {

            inputStream =
                    info.getTraceInputStream();

            if (inputStream == null) {
                return "";
            }

            reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    inputStream
                            )
                    );

            StringBuilder important =
                    new StringBuilder();

            String line;

            int totalLines = 0;

            while ((line = reader.readLine()) != null) {

                totalLines++;

                String lower =
                        line.toLowerCase();

                if (
                        lower.contains("abort") ||
                        lower.contains("signal") ||
                        lower.contains("fatal") ||
                        lower.contains("backtrace") ||
                        lower.contains("libwebrtc") ||
                        lower.contains("webrtc") ||
                        lower.contains("screen") ||
                        lower.contains("peerconnection") ||
                        lower.contains("#00") ||
                        lower.contains("#01") ||
                        lower.contains("#02") ||
                        lower.contains("#03") ||
                        lower.contains("#04") ||
                        lower.contains("#05") ||
                        lower.contains("#06") ||
                        lower.contains("#07") ||
                        lower.contains("#08") ||
                        lower.contains("#09")
                ) {

                    important
                            .append(line)
                            .append("\n");
                }

                if (important.length() > 10000) {
                    break;
                }

                if (totalLines > 3000) {
                    break;
                }
            }

            return important.toString();

        } catch (Exception e) {

            return "Trace read error: "
                    +
                    e.getClass().getSimpleName()
                    +
                    " "
                    +
                    e.getMessage();

        } finally {

            try {

                if (reader != null) {
                    reader.close();
                }

            } catch (Exception ignored) {
            }

            try {

                if (inputStream != null) {
                    inputStream.close();
                }

            } catch (Exception ignored) {
            }
        }
    }

    private String getExitReasonText(int reason) {

        switch (reason) {

            case ApplicationExitInfo.REASON_ANR:
                return "ANR";

            case ApplicationExitInfo.REASON_CRASH:
                return "JAVA_CRASH";

            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "NATIVE_CRASH";

            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
                return "DEPENDENCY_DIED";

            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "EXCESSIVE_RESOURCE_USAGE";

            case ApplicationExitInfo.REASON_EXIT_SELF:
                return "EXIT_SELF";

            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "INITIALIZATION_FAILURE";

            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "LOW_MEMORY";

            case ApplicationExitInfo.REASON_OTHER:
                return "OTHER";

            case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                return "PERMISSION_CHANGE";

            case ApplicationExitInfo.REASON_SIGNALED:
                return "SIGNALED";

            case ApplicationExitInfo.REASON_UNKNOWN:
                return "UNKNOWN";

            case ApplicationExitInfo.REASON_USER_REQUESTED:
                return "USER_REQUESTED";

            case ApplicationExitInfo.REASON_USER_STOPPED:
                return "USER_STOPPED";

            default:
                return "CODE_" + reason;
        }
    }

    private void startLive() {

        getSharedPreferences(
                "live_screen",
                MODE_PRIVATE
        )
                .edit()
                .remove(
                        "exit_diagnostic"
                )
                .apply();

        startButton.setEnabled(false);

        setLocalStatus(
                "Requesting permission..."
        );

        if (
                Build.VERSION.SDK_INT >= 33
                        &&
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
        ) {

            notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
            );

            return;
        }

        requestScreenCapture();
    }

    private void requestScreenCapture() {

        Intent captureIntent =
                projectionManager
                        .createScreenCaptureIntent();

        screenCaptureLauncher.launch(
                captureIntent
        );
    }

    private void startScreenService(
            int resultCode,
            Intent captureData
    ) {

        setLocalStatus(
                "Starting screen service..."
        );

        Intent serviceIntent =
                new Intent(
                        this,
                        ScreenCaptureService.class
                );

        serviceIntent.setAction(
                ScreenCaptureService.ACTION_START
        );

        serviceIntent.putExtra(
                ScreenCaptureService.EXTRA_RESULT_CODE,
                resultCode
        );

        serviceIntent.putExtra(
                ScreenCaptureService.EXTRA_CAPTURE_DATA,
                captureData
        );

        ContextCompat.startForegroundService(
                this,
                serviceIntent
        );

        startButton.setEnabled(false);
        stopButton.setEnabled(true);
    }

    private void stopLive() {

        Intent intent =
                new Intent(
                        this,
                        ScreenCaptureService.class
                );

        intent.setAction(
                ScreenCaptureService.ACTION_STOP
        );

        startService(intent);

        setLocalStatus(
                "Stopped"
        );

        startButton.setEnabled(true);
        stopButton.setEnabled(false);
    }

    private void setLocalStatus(
            String status
    ) {

        getSharedPreferences(
                "live_screen",
                MODE_PRIVATE
        )
                .edit()
                .putString(
                        "status",
                        status
                )
                .apply();

        if (statusText != null) {

            statusText.setText(
                    status
            );
        }
    }

    @Override
    protected void onResume() {

        super.onResume();

        statusHandler.removeCallbacks(
                statusRunnable
        );

        statusHandler.post(
                statusRunnable
        );
    }

    @Override
    protected void onPause() {

        statusHandler.removeCallbacks(
                statusRunnable
        );

        super.onPause();
    }

    @Override
    protected void onDestroy() {

        statusHandler.removeCallbacks(
                statusRunnable
        );

        super.onDestroy();
    }
}
