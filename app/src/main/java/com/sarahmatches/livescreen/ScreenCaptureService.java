package com.sarahmatches.livescreen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {

    public static final String ACTION_START =
            "com.sarahmatches.livescreen.START";

    public static final String ACTION_STOP =
            "com.sarahmatches.livescreen.STOP";

    public static final String EXTRA_RESULT_CODE =
            "result_code";

    public static final String EXTRA_CAPTURE_DATA =
            "capture_data";

    private static final String CHANNEL_ID =
            "screen_capture_channel";

    private static final int NOTIFICATION_ID = 1001;

    /*
     * VPS settings
     */
    private static final String VPS_HOST =
            "192.99.144.179";

    private static final int VPS_PORT =
            5000;

    private static final int CONNECT_TIMEOUT_MS =
            8000;

    private static final int RECONNECT_DELAY_MS =
            800;

    /*
     * Video settings
     */
    private static final int WIDTH = 540;
    private static final int HEIGHT = 960;
    private static final int FPS = 15;
    private static final int BITRATE = 1_500_000;
    private static final int I_FRAME_INTERVAL = 1;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;

    private MediaCodec encoder;
    private Surface encoderInputSurface;

    private HandlerThread encoderThread;
    private Handler encoderHandler;

    private Socket socket;
    private OutputStream socketOutput;

    private volatile boolean running = false;
    private volatile boolean stopping = false;
    private volatile boolean socketConnected = false;

    private long frameCount = 0;

    /*
     * Saved H264 SPS/PPS.
     *
     * These are sent again after reconnect
     * so FFmpeg can decode a new connection.
     */
    private byte[] cachedCsd0;
    private byte[] cachedCsd1;

    @Override
    public void onCreate() {

        super.onCreate();

        createNotificationChannel();

        startForeground(
                NOTIFICATION_ID,
                buildNotification(
                        "Service ready"
                )
        );

        updateStatus(
                "Service ready"
        );
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action =
                intent.getAction();

        if (ACTION_STOP.equals(action)) {

            stopStreaming();

            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(action)) {

            return START_NOT_STICKY;
        }

        if (running) {

            updateStatus(
                    "Already streaming"
            );

            return START_NOT_STICKY;
        }

        int resultCode =
                intent.getIntExtra(
                        EXTRA_RESULT_CODE,
                        0
                );

        Intent captureData =
                getCaptureIntent(intent);

        if (
                resultCode == 0
                        ||
                captureData == null
        ) {

            updateStatus(
                    "Capture permission missing"
            );

            stopSelf();

            return START_NOT_STICKY;
        }

        startStreaming(
                resultCode,
                captureData
        );

        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Intent getCaptureIntent(
            Intent serviceIntent
    ) {

        if (Build.VERSION.SDK_INT >= 33) {

            return serviceIntent
                    .getParcelableExtra(
                            EXTRA_CAPTURE_DATA,
                            Intent.class
                    );
        }

        return serviceIntent
                .getParcelableExtra(
                        EXTRA_CAPTURE_DATA
                );
    }

    private void startStreaming(
            int resultCode,
            Intent captureData
    ) {

        running = true;
        stopping = false;

        frameCount = 0;

        cachedCsd0 = null;
        cachedCsd1 = null;

        encoderThread =
                new HandlerThread(
                        "LiveScreenEncoder"
                );

        encoderThread.start();

        encoderHandler =
                new Handler(
                        encoderThread.getLooper()
                );

        encoderHandler.post(
                () -> {

                    try {

                        /*
                         * Keep trying VPS until
                         * connected or user presses STOP.
                         */
                        if (!connectToVpsWithRetry()) {

                            if (!stopping) {

                                updateStatus(
                                        "VPS connection stopped"
                                );
                            }

                            return;
                        }

                        if (
                                !running
                                        ||
                                stopping
                        ) {
                            return;
                        }

                        updateStatus(
                                "VPS connected"
                        );

                        prepareEncoder();

                        updateStatus(
                                "H264 encoder started"
                        );

                        prepareMediaProjection(
                                resultCode,
                                captureData
                        );

                        updateStatus(
                                "LIVE: sending to VPS"
                        );

                        drainEncoder();

                    } catch (Exception e) {

                        if (
                                running
                                        &&
                                !stopping
                        ) {

                            updateStatus(
                                    "Start error: "
                                            +
                                            e.getClass()
                                                    .getSimpleName()
                                            +
                                            " "
                                            +
                                            safeMessage(e)
                            );
                        }

                        stopStreaming();
                    }
                }
        );
    }

    /*
     * Initial VPS connection.
     *
     * It keeps retrying instead of stopping
     * the entire screen-share service.
     */
    private boolean connectToVpsWithRetry() {

        int attempt = 0;

        while (
                running
                        &&
                !stopping
        ) {

            attempt++;

            try {

                updateStatus(
                        "Connecting VPS... "
                                +
                                attempt
                );

                connectToVps();

                return true;

            } catch (IOException e) {

                closeSocket();

                if (
                        !running
                                ||
                                stopping
                ) {
                    return false;
                }

                updateStatus(
                        "VPS unavailable - retrying..."
                );

                if (!waitBeforeReconnect()) {
                    return false;
                }
            }
        }

        return false;
    }

    private void connectToVps()
            throws IOException {

        closeSocket();

        Socket newSocket =
                new Socket();

        newSocket.setTcpNoDelay(
                true
        );

        newSocket.setKeepAlive(
                true
        );

        newSocket.connect(
                new InetSocketAddress(
                        VPS_HOST,
                        VPS_PORT
                ),
                CONNECT_TIMEOUT_MS
        );

        OutputStream newOutput =
                new BufferedOutputStream(
                        newSocket.getOutputStream(),
                        256 * 1024
                );

        socket =
                newSocket;

        socketOutput =
                newOutput;

        socketConnected =
                true;
    }

    /*
     * Called whenever TCP connection breaks
     * while screen capture is still running.
     */
    private boolean reconnectToVps() {

        closeSocket();

        int attempt = 0;

        while (
                running
                        &&
                !stopping
        ) {

            attempt++;

            updateStatus(
                    "Reconnecting VPS... "
                            +
                            attempt
            );

            try {

                connectToVps();

                /*
                 * New TCP connection needs
                 * SPS/PPS again.
                 */
                sendCachedCodecSpecificData();

                /*
                 * Request a fresh keyframe so
                 * FFmpeg can immediately resume.
                 */
                requestKeyFrame();

                updateStatus(
                        "VPS reconnected ✓"
                );

                return true;

            } catch (Exception e) {

                closeSocket();

                if (
                        !running
                                ||
                                stopping
                ) {

                    return false;
                }

                updateStatus(
                        "Reconnect failed - retrying..."
                );

                if (!waitBeforeReconnect()) {

                    return false;
                }
            }
        }

        return false;
    }

    private boolean waitBeforeReconnect() {

        long end =
                SystemClock.elapsedRealtime()
                        +
                        RECONNECT_DELAY_MS;

        while (
                running
                        &&
                !stopping
                        &&
                SystemClock.elapsedRealtime()
                        <
                        end
        ) {

            try {

                Thread.sleep(
                        250
                );

            } catch (InterruptedException e) {

                Thread.currentThread()
                        .interrupt();

                return false;
            }
        }

        return running
                &&
                !stopping;
    }

    private void prepareEncoder()
            throws IOException {

        MediaFormat format =
                MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                        WIDTH,
                        HEIGHT
                );

        format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo
                        .CodecCapabilities
                        .COLOR_FormatSurface
        );

        format.setInteger(
                MediaFormat.KEY_BIT_RATE,
                BITRATE
        );

        format.setInteger(
                MediaFormat.KEY_FRAME_RATE,
                FPS
        );

        format.setInteger(
                MediaFormat.KEY_I_FRAME_INTERVAL,
                I_FRAME_INTERVAL
        );

        encoder =
                MediaCodec.createEncoderByType(
                        MediaFormat.MIMETYPE_VIDEO_AVC
                );

        encoder.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
        );

        encoderInputSurface =
                encoder.createInputSurface();

        encoder.start();
    }

    private void prepareMediaProjection(
            int resultCode,
            Intent captureData
    ) {

        MediaProjectionManager projectionManager =
                (MediaProjectionManager)
                        getSystemService(
                                Context.MEDIA_PROJECTION_SERVICE
                        );

        if (projectionManager == null) {

            throw new RuntimeException(
                    "MediaProjectionManager unavailable"
            );
        }

        mediaProjection =
                projectionManager
                        .getMediaProjection(
                                resultCode,
                                captureData
                        );

        if (mediaProjection == null) {

            throw new RuntimeException(
                    "MediaProjection failed"
            );
        }

        mediaProjection.registerCallback(
                new MediaProjection.Callback() {

                    @Override
                    public void onStop() {

                        if (!stopping) {

                            updateStatus(
                                    "Screen capture stopped"
                            );

                            stopStreaming();
                        }
                    }
                },
                encoderHandler
        );

        int density =
                getResources()
                        .getDisplayMetrics()
                        .densityDpi;

        virtualDisplay =
                mediaProjection
                        .createVirtualDisplay(
                                "LiveScreenCapture",
                                WIDTH,
                                HEIGHT,
                                density,
                                DisplayManager
                                        .VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                                encoderInputSurface,
                                null,
                                encoderHandler
                        );

        if (virtualDisplay == null) {

            throw new RuntimeException(
                    "VirtualDisplay failed"
            );
        }
    }

    private void drainEncoder() {

        MediaCodec.BufferInfo info =
                new MediaCodec.BufferInfo();

        while (
                running
                        &&
                !stopping
                        &&
                encoder != null
        ) {

            int outputIndex =
                    -1;

            try {

                outputIndex =
                        encoder.dequeueOutputBuffer(
                                info,
                                10_000
                        );

                if (
                        outputIndex
                                ==
                        MediaCodec.INFO_TRY_AGAIN_LATER
                ) {

                    continue;
                }

                if (
                        outputIndex
                                ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                ) {

                    MediaFormat outputFormat =
                            encoder.getOutputFormat();

                    cacheCodecSpecificData(
                            outputFormat
                    );

                    try {

                        sendCachedCodecSpecificData();

                        updateStatus(
                                "H264 ready - sending"
                        );

                    } catch (IOException e) {

                        if (!reconnectToVps()) {
                            return;
                        }
                    }

                    continue;
                }

                if (outputIndex < 0) {

                    continue;
                }

                ByteBuffer buffer =
                        encoder.getOutputBuffer(
                                outputIndex
                        );

                if (
                        buffer != null
                                &&
                        info.size > 0
                ) {

                    buffer.position(
                            info.offset
                    );

                    buffer.limit(
                            info.offset
                                    +
                                    info.size
                    );

                    byte[] data =
                            new byte[
                                    info.size
                                    ];

                    buffer.get(
                            data
                    );

                    boolean sent =
                            false;

                    try {

                        sendEncodedData(
                                data
                        );

                        sent =
                                true;

                    } catch (IOException e) {

                        if (
                                running
                                        &&
                                !stopping
                        ) {

                            updateStatus(
                                    "Connection lost - reconnecting..."
                            );
                        }

                        if (!reconnectToVps()) {

                            return;
                        }
                    }

                    if (sent) {

                        frameCount++;

                        boolean keyFrame =
                                (
                                        info.flags
                                                &
                                        MediaCodec
                                                .BUFFER_FLAG_KEY_FRAME
                                )
                                        != 0;

                        if (keyFrame) {

                            updateStatus(
                                    "LIVE ✓ keyframe sent"
                            );

                        } else if (
                                frameCount % 150 == 0
                        ) {

                            updateStatus(
                                    "LIVE ✓ frames: "
                                            +
                                            frameCount
                            );
                        }
                    }
                }

            } catch (Exception e) {

                if (
                        running
                                &&
                        !stopping
                ) {

                    updateStatus(
                            "Encoder error: "
                                    +
                                    e.getClass()
                                            .getSimpleName()
                                    +
                                    " "
                                    +
                                    safeMessage(e)
                    );
                }

                stopStreaming();

                return;

            } finally {

                if (
                        outputIndex >= 0
                                &&
                        encoder != null
                ) {

                    try {

                        encoder.releaseOutputBuffer(
                                outputIndex,
                                false
                        );

                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private void cacheCodecSpecificData(
            MediaFormat format
    ) {

        cachedCsd0 =
                copyByteBuffer(
                        format.getByteBuffer(
                                "csd-0"
                        )
                );

        cachedCsd1 =
                copyByteBuffer(
                        format.getByteBuffer(
                                "csd-1"
                        )
                );
    }

    private byte[] copyByteBuffer(
            ByteBuffer source
    ) {

        if (source == null) {
            return null;
        }

        ByteBuffer copy =
                source.duplicate();

        byte[] bytes =
                new byte[
                        copy.remaining()
                        ];

        copy.get(
                bytes
        );

        return bytes;
    }

    private void sendCachedCodecSpecificData()
            throws IOException {

        if (
                cachedCsd0 != null
                        &&
                        cachedCsd0.length > 0
        ) {

            sendEncodedData(
                    cachedCsd0
            );
        }

        if (
                cachedCsd1 != null
                        &&
                        cachedCsd1.length > 0
        ) {

            sendEncodedData(
                    cachedCsd1
            );
        }

        if (socketOutput != null) {

            socketOutput.flush();
        }
    }

    private void requestKeyFrame() {

        if (encoder == null) {
            return;
        }

        try {

            Bundle params =
                    new Bundle();

            params.putInt(
                    MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,
                    0
            );

            encoder.setParameters(
                    params
            );

        } catch (Exception ignored) {
        }
    }

    private void sendEncodedData(
            byte[] data
    ) throws IOException {

        if (
                !socketConnected
                        ||
                socketOutput == null
        ) {

            throw new IOException(
                    "VPS socket disconnected"
            );
        }

        socketOutput.write(
                data
        );

        /*
         * Frequent flush keeps latency low.
         */
        socketOutput.flush();
    }

    private synchronized void closeSocket() {

        socketConnected =
                false;

        if (socketOutput != null) {

            try {

                socketOutput.flush();

            } catch (Exception ignored) {
            }

            try {

                socketOutput.close();

            } catch (Exception ignored) {
            }

            socketOutput =
                    null;
        }

        if (socket != null) {

            try {

                socket.close();

            } catch (Exception ignored) {
            }

            socket =
                    null;
        }
    }

    private synchronized void stopStreaming() {

        if (stopping) {
            return;
        }

        stopping =
                true;

        running =
                false;

        updateStatus(
                "Stopping..."
        );

        /*
         * Closing socket also interrupts
         * active network writes.
         */
        closeSocket();

        if (encoderHandler != null) {

            encoderHandler.post(
                    this::releaseEverything
            );

        } else {

            releaseEverything();
        }
    }

    private void releaseEverything() {

        closeSocket();

        if (virtualDisplay != null) {

            try {

                virtualDisplay.release();

            } catch (Exception ignored) {
            }

            virtualDisplay =
                    null;
        }

        if (mediaProjection != null) {

            try {

                mediaProjection.stop();

            } catch (Exception ignored) {
            }

            mediaProjection =
                    null;
        }

        if (encoder != null) {

            try {

                encoder.stop();

            } catch (Exception ignored) {
            }

            try {

                encoder.release();

            } catch (Exception ignored) {
            }

            encoder =
                    null;
        }

        if (encoderInputSurface != null) {

            try {

                encoderInputSurface.release();

            } catch (Exception ignored) {
            }

            encoderInputSurface =
                    null;
        }

        cachedCsd0 =
                null;

        cachedCsd1 =
                null;

        updateStatus(
                "Stopped"
        );

        stopForeground(
                true
        );

        stopSelf();

        if (encoderThread != null) {

            try {

                encoderThread.quitSafely();

            } catch (Exception ignored) {
            }

            encoderThread =
                    null;
        }

        encoderHandler =
                null;

        stopping =
                false;
    }

    private String safeMessage(
            Exception e
    ) {

        String message =
                e.getMessage();

        if (message == null) {

            return "";
        }

        return message;
    }

    private void updateStatus(
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

        updateNotification(
                status
        );
    }

    private void createNotificationChannel() {

        if (
                Build.VERSION.SDK_INT
                        >=
                Build.VERSION_CODES.O
        ) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Live Screen Sharing",
                            NotificationManager
                                    .IMPORTANCE_LOW
                    );

            NotificationManager manager =
                    getSystemService(
                            NotificationManager.class
                    );

            if (manager != null) {

                manager.createNotificationChannel(
                        channel
                );
            }
        }
    }

    private Notification buildNotification(
            String text
    ) {

        return new NotificationCompat
                .Builder(
                        this,
                        CHANNEL_ID
                )
                .setContentTitle(
                        "SarahMatches Live Screen"
                )
                .setContentText(
                        text
                )
                .setSmallIcon(
                        android.R.drawable
                                .presence_video_online
                )
                .setOngoing(
                        true
                )
                .setOnlyAlertOnce(
                        true
                )
                .build();
    }

    private void updateNotification(
            String text
    ) {

        try {

            NotificationManager manager =
                    (NotificationManager)
                            getSystemService(
                                    NOTIFICATION_SERVICE
                            );

            if (manager != null) {

                manager.notify(
                        NOTIFICATION_ID,
                        buildNotification(
                                text
                        )
                );
            }

        } catch (Exception ignored) {
        }
    }

    @Nullable
    @Override
    public IBinder onBind(
            Intent intent
    ) {

        return null;
    }

    @Override
    public void onDestroy() {

        if (
                running
                        &&
                !stopping
        ) {

            stopStreaming();
        }

        super.onDestroy();
    }
}
