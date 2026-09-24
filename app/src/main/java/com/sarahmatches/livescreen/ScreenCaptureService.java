package com.sarahmatches.livescreen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
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

    private static final int VIDEO_PORT =
            5000;

    private static final int AUDIO_PORT =
            5001;

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

    /*
     * Audio settings
     */
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_CHANNELS = 2;
    private static final int AUDIO_BITRATE = 128000;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;

    private MediaCodec encoder;
    private Surface encoderInputSurface;

    private HandlerThread encoderThread;
    private Handler encoderHandler;

    private Socket videoSocket;
    private OutputStream videoOutput;

    private Thread audioThread;
    private AudioRecord audioRecord;
    private MediaCodec audioEncoder;

    private Socket audioSocket;
    private OutputStream audioOutput;

    private volatile boolean running = false;
    private volatile boolean stopping = false;
    private volatile boolean videoSocketConnected = false;
    private volatile boolean audioSocketConnected = false;

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

                        if (!connectVideoWithRetry()) {

                            if (!stopping) {

                                updateStatus(
                                        "VPS video connection stopped"
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
                                "VPS video connected"
                        );

                        prepareVideoEncoder();

                        prepareMediaProjection(
                                resultCode,
                                captureData
                        );

                        startAudioPipeline();

                        updateStatus(
                                "LIVE: video + audio starting"
                        );

                        drainVideoEncoder();

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

    private boolean connectVideoWithRetry() {

        int attempt = 0;

        while (
                running
                        &&
                !stopping
        ) {

            attempt++;

            try {

                updateStatus(
                        "Connecting video VPS... "
                                +
                                attempt
                );

                connectVideoSocket();

                return true;

            } catch (IOException e) {

                closeVideoSocket();

                if (
                        !running
                                ||
                                stopping
                ) {
                    return false;
                }

                if (!waitBeforeReconnect()) {
                    return false;
                }
            }
        }

        return false;
    }

    private void connectVideoSocket()
            throws IOException {

        closeVideoSocket();

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
                        VIDEO_PORT
                ),
                CONNECT_TIMEOUT_MS
        );

        OutputStream newOutput =
                new BufferedOutputStream(
                        newSocket.getOutputStream(),
                        256 * 1024
                );

        videoSocket =
                newSocket;

        videoOutput =
                newOutput;

        videoSocketConnected =
                true;
    }

    private boolean reconnectVideo() {

        closeVideoSocket();

        int attempt = 0;

        while (
                running
                        &&
                !stopping
        ) {

            attempt++;

            updateStatus(
                    "Reconnecting video... "
                            +
                            attempt
            );

            try {

                connectVideoSocket();

                sendCachedCodecSpecificData();

                requestKeyFrame();

                updateStatus(
                        "Video reconnected ✓"
                );

                return true;

            } catch (Exception e) {

                closeVideoSocket();

                if (
                        !running
                                ||
                                stopping
                ) {

                    return false;
                }

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
                        200
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

    private void prepareVideoEncoder()
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

    private void startAudioPipeline() {

        audioThread =
                new Thread(
                        this::runAudioPipeline,
                        "LiveScreenAudio"
                );

        audioThread.start();
    }

    private void runAudioPipeline() {

        try {

            audioRecord =
                    createAudioRecord();

            if (
                    audioRecord == null
                            ||
                    audioRecord.getState()
                            !=
                    AudioRecord.STATE_INITIALIZED
            ) {

                updateStatus(
                        "Video live; audio unavailable"
                );

                return;
            }

            MediaFormat audioFormat =
                    MediaFormat.createAudioFormat(
                            MediaFormat.MIMETYPE_AUDIO_AAC,
                            AUDIO_SAMPLE_RATE,
                            AUDIO_CHANNELS
                    );

            audioFormat.setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
            );

            audioFormat.setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    AUDIO_BITRATE
            );

            audioFormat.setInteger(
                    MediaFormat.KEY_MAX_INPUT_SIZE,
                    16384
            );

            audioEncoder =
                    MediaCodec.createEncoderByType(
                            MediaFormat.MIMETYPE_AUDIO_AAC
                    );

            audioEncoder.configure(
                    audioFormat,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
            );

            audioEncoder.start();

            audioRecord.startRecording();

            connectAudioWithRetry();

            byte[] pcm =
                    new byte[8192];

            MediaCodec.BufferInfo info =
                    new MediaCodec.BufferInfo();

            while (
                    running
                            &&
                    !stopping
            ) {

                int read =
                        audioRecord.read(
                                pcm,
                                0,
                                pcm.length
                        );

                if (read > 0) {

                    int inputIndex =
                            audioEncoder.dequeueInputBuffer(
                                    10000
                            );

                    if (inputIndex >= 0) {

                        ByteBuffer input =
                                audioEncoder.getInputBuffer(
                                        inputIndex
                                );

                        if (input != null) {

                            input.clear();
                            input.put(
                                    pcm,
                                    0,
                                    read
                            );

                            long pts =
                                    System.nanoTime()
                                            /
                                    1000L;

                            audioEncoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    read,
                                    pts,
                                    0
                            );
                        }
                    }
                }

                drainAudioEncoder(
                        info
                );
            }

        } catch (Exception e) {

            if (
                    running
                            &&
                    !stopping
            ) {

                updateStatus(
                        "Video live; audio error: "
                                +
                                e.getClass()
                                        .getSimpleName()
                );
            }

        } finally {

            releaseAudio();
        }
    }

    private AudioRecord createAudioRecord() {

        int channelMask =
                AudioFormat.CHANNEL_IN_STEREO;

        int minBuffer =
                AudioRecord.getMinBufferSize(
                        AUDIO_SAMPLE_RATE,
                        channelMask,
                        AudioFormat.ENCODING_PCM_16BIT
                );

        int bufferSize =
                Math.max(
                        minBuffer * 2,
                        16384
                );

        if (
                Build.VERSION.SDK_INT
                        >=
                Build.VERSION_CODES.Q
                        &&
                mediaProjection != null
        ) {

            try {

                AudioPlaybackCaptureConfiguration config =
                        new AudioPlaybackCaptureConfiguration
                                .Builder(
                                        mediaProjection
                                )
                                .addMatchingUsage(
                                        AudioAttributes.USAGE_MEDIA
                                )
                                .addMatchingUsage(
                                        AudioAttributes.USAGE_GAME
                                )
                                .build();

                AudioFormat format =
                        new AudioFormat
                                .Builder()
                                .setEncoding(
                                        AudioFormat.ENCODING_PCM_16BIT
                                )
                                .setSampleRate(
                                        AUDIO_SAMPLE_RATE
                                )
                                .setChannelMask(
                                        channelMask
                                )
                                .build();

                AudioRecord playbackRecord =
                        new AudioRecord
                                .Builder()
                                .setAudioFormat(
                                        format
                                )
                                .setBufferSizeInBytes(
                                        bufferSize
                                )
                                .setAudioPlaybackCaptureConfig(
                                        config
                                )
                                .build();

                if (
                        playbackRecord.getState()
                                ==
                        AudioRecord.STATE_INITIALIZED
                ) {

                    updateStatus(
                            "Audio: internal playback capture"
                    );

                    return playbackRecord;
                }

                playbackRecord.release();

            } catch (Exception ignored) {
            }
        }

        try {

            AudioRecord micRecord =
                    new AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            AUDIO_SAMPLE_RATE,
                            channelMask,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize
                    );

            if (
                    micRecord.getState()
                            ==
                    AudioRecord.STATE_INITIALIZED
            ) {

                updateStatus(
                        "Audio: microphone fallback"
                );

                return micRecord;
            }

            micRecord.release();

        } catch (Exception ignored) {
        }

        return null;
    }

    private void drainAudioEncoder(
            MediaCodec.BufferInfo info
    ) throws IOException {

        while (
                running
                        &&
                !stopping
                        &&
                audioEncoder != null
        ) {

            int outputIndex =
                    audioEncoder.dequeueOutputBuffer(
                            info,
                            0
                    );

            if (
                    outputIndex
                            ==
                    MediaCodec.INFO_TRY_AGAIN_LATER
            ) {

                return;
            }

            if (
                    outputIndex
                            ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            ) {

                continue;
            }

            if (outputIndex < 0) {

                return;
            }

            try {

                ByteBuffer output =
                        audioEncoder.getOutputBuffer(
                                outputIndex
                        );

                if (
                        output == null
                                ||
                        info.size <= 0
                ) {

                    continue;
                }

                if (
                        (
                                info.flags
                                        &
                                MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                        )
                                != 0
                ) {

                    continue;
                }

                output.position(
                        info.offset
                );

                output.limit(
                        info.offset
                                +
                                info.size
                );

                byte[] aac =
                        new byte[
                                info.size
                                +
                                7
                                ];

                addAdtsHeader(
                        aac,
                        aac.length
                );

                output.get(
                        aac,
                        7,
                        info.size
                );

                sendAudioData(
                        aac
                );

            } finally {

                audioEncoder.releaseOutputBuffer(
                        outputIndex,
                        false
                );
            }
        }
    }

    private void addAdtsHeader(
            byte[] packet,
            int packetLength
    ) {

        int profile = 2;
        int frequencyIndex = 4;
        int channelConfig = AUDIO_CHANNELS;

        packet[0] = (byte) 0xFF;
        packet[1] = (byte) 0xF1;
        packet[2] =
                (byte) (
                        ((profile - 1) << 6)
                                |
                        (frequencyIndex << 2)
                                |
                        (channelConfig >> 2)
                );

        packet[3] =
                (byte) (
                        ((channelConfig & 3) << 6)
                                |
                        (packetLength >> 11)
                );

        packet[4] =
                (byte) (
                        (packetLength & 0x7FF)
                                >>
                        3
                );

        packet[5] =
                (byte) (
                        (
                                (packetLength & 7)
                                        <<
                                5
                        )
                                |
                        0x1F
                );

        packet[6] =
                (byte) 0xFC;
    }

    private void connectAudioWithRetry() {

        while (
                running
                        &&
                !stopping
                        &&
                !audioSocketConnected
        ) {

            try {

                connectAudioSocket();

                updateStatus(
                        "LIVE: video + audio connected"
                );

                return;

            } catch (IOException e) {

                closeAudioSocket();

                if (!waitBeforeReconnect()) {
                    return;
                }
            }
        }
    }

    private void connectAudioSocket()
            throws IOException {

        closeAudioSocket();

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
                        AUDIO_PORT
                ),
                CONNECT_TIMEOUT_MS
        );

        OutputStream newOutput =
                new BufferedOutputStream(
                        newSocket.getOutputStream(),
                        128 * 1024
                );

        audioSocket =
                newSocket;

        audioOutput =
                newOutput;

        audioSocketConnected =
                true;
    }

    private void sendAudioData(
            byte[] data
    ) {

        if (
                !running
                        ||
                stopping
        ) {

            return;
        }

        try {

            if (
                    !audioSocketConnected
                            ||
                    audioOutput == null
            ) {

                connectAudioWithRetry();
            }

            if (
                    audioSocketConnected
                            &&
                    audioOutput != null
            ) {

                audioOutput.write(
                        data
                );

                audioOutput.flush();
            }

        } catch (IOException e) {

            closeAudioSocket();

            connectAudioWithRetry();
        }
    }

    private void drainVideoEncoder() {

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

                        if (!reconnectVideo()) {
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

                        sendVideoData(
                                data
                        );

                        sent =
                                true;

                    } catch (IOException e) {

                        if (!reconnectVideo()) {

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
                                    audioSocketConnected
                                            ?
                                            "LIVE ✓ video + audio"
                                            :
                                            "LIVE ✓ video; audio reconnecting"
                            );

                        } else if (
                                frameCount % 150 == 0
                        ) {

                            updateStatus(
                                    audioSocketConnected
                                            ?
                                            "LIVE ✓ video + audio"
                                            :
                                            "LIVE ✓ video"
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
                            "Video encoder error: "
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

            sendVideoData(
                    cachedCsd0
            );
        }

        if (
                cachedCsd1 != null
                        &&
                        cachedCsd1.length > 0
        ) {

            sendVideoData(
                    cachedCsd1
            );
        }

        if (videoOutput != null) {

            videoOutput.flush();
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

    private void sendVideoData(
            byte[] data
    ) throws IOException {

        if (
                !videoSocketConnected
                        ||
                videoOutput == null
        ) {

            throw new IOException(
                    "VPS video socket disconnected"
            );
        }

        videoOutput.write(
                data
        );

        videoOutput.flush();
    }

    private synchronized void closeVideoSocket() {

        videoSocketConnected =
                false;

        if (videoOutput != null) {

            try {

                videoOutput.flush();

            } catch (Exception ignored) {
            }

            try {

                videoOutput.close();

            } catch (Exception ignored) {
            }

            videoOutput =
                    null;
        }

        if (videoSocket != null) {

            try {

                videoSocket.close();

            } catch (Exception ignored) {
            }

            videoSocket =
                    null;
        }
    }

    private synchronized void closeAudioSocket() {

        audioSocketConnected =
                false;

        if (audioOutput != null) {

            try {

                audioOutput.flush();

            } catch (Exception ignored) {
            }

            try {

                audioOutput.close();

            } catch (Exception ignored) {
            }

            audioOutput =
                    null;
        }

        if (audioSocket != null) {

            try {

                audioSocket.close();

            } catch (Exception ignored) {
            }

            audioSocket =
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

        closeVideoSocket();
        closeAudioSocket();

        if (audioRecord != null) {

            try {

                audioRecord.stop();

            } catch (Exception ignored) {
            }
        }

        if (audioThread != null) {

            try {

                audioThread.interrupt();

            } catch (Exception ignored) {
            }
        }

        if (encoderHandler != null) {

            encoderHandler.post(
                    this::releaseEverything
            );

        } else {

            releaseEverything();
        }
    }

    private void releaseAudio() {

        closeAudioSocket();

        if (audioRecord != null) {

            try {

                audioRecord.stop();

            } catch (Exception ignored) {
            }

            try {

                audioRecord.release();

            } catch (Exception ignored) {
            }

            audioRecord =
                    null;
        }

        if (audioEncoder != null) {

            try {

                audioEncoder.stop();

            } catch (Exception ignored) {
            }

            try {

                audioEncoder.release();

            } catch (Exception ignored) {
            }

            audioEncoder =
                    null;
        }

        audioThread =
                null;
    }

    private void releaseEverything() {

        closeVideoSocket();
        closeAudioSocket();

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
