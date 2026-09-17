package gh.nusashell.nusadesk.infrastructure.service;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaDefaults;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaError;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaStartException;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LoopbackRtspServer;

/**
 * Microphone + AAC-LC hardware encoder source for the live media pipeline.
 *
 * <p>Reads 44.1 kHz mono 16-bit PCM with {@link AudioRecord} and feeds a
 * {@code audio/mp4a-latm} encoder; the pump thread wraps every raw AAC frame
 * (no ADTS) and publishes it to the {@link LoopbackRtspServer}. The
 * encoder's AudioSpecificConfig is captured from the output format and
 * exposed through {@link #awaitFormat(long)} so the pipeline only reports
 * ready once the SDP can be built.</p>
 *
 * <p>This class is device-only: JVM tests cannot open the microphone or run
 * a hardware encoder, so its behavior is verified on physical devices, not
 * faked on the JVM.</p>
 */
public final class AudioEncoderSource {
    private static final String TAG = "AudioEncoderSource";

    /** Ready encoder metadata: the AudioSpecificConfig and its layout. */
    public static final class AudioFormatInfo {
        public final byte[] config;
        public final int sampleRate;
        public final int channels;

        AudioFormatInfo(byte[] config, int sampleRate, int channels) {
            this.config = config;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }
    }

    private final LoopbackRtspServer server;
    private final Object formatLock = new Object();
    private final long startNanos;
    private volatile AudioFormatInfo format;

    private AudioRecord audioRecord;
    private MediaCodec codec;
    private Thread pumpThread;
    private volatile boolean running;

    public AudioEncoderSource(Context context, LoopbackRtspServer server) {
        if (context == null || server == null) {
            throw new IllegalArgumentException("context and server must not be null");
        }
        this.server = server;
        // One shared time base for video and audio PTS, rebased at pipeline
        // start so both RTP clocks describe the same live instant.
        this.startNanos = System.nanoTime();
    }

    /** Open the microphone and start the encoder and pump. */
    public void start() throws LiveMediaStartException {
        AudioRecord record = openMicrophone();
        MediaCodec encoder = createAacEncoder();
        try {
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("recording did not start");
            }
            encoder.start();
        } catch (IllegalStateException e) {
            releaseQuietly(record);
            encoder.release();
            throw new LiveMediaStartException(LiveMediaError.UNAVAILABLE,
                    "microphone could not start");
        }
        audioRecord = record;
        codec = encoder;
        running = true;
        pumpThread = new Thread(this::pumpLoop, "live-media-audio-pump");
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    /**
     * Wait up to {@code timeoutMillis} for the encoder metadata. A timeout or
     * interruption returns {@code null}: the pipeline must not declare a live
     * stream ready without metadata actually observed from the encoder.
     */
    public AudioFormatInfo awaitFormat(long timeoutMillis) {
        synchronized (formatLock) {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (format == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null;
                }
                try {
                    formatLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return format;
        }
    }

    /** Stop the microphone, encoder, and pump. Idempotent. */
    public void stop() {
        running = false;
        if (audioRecord != null) {
            releaseQuietly(audioRecord);
            audioRecord = null;
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
                // Already stopped or never started.
            }
            codec.release();
            codec = null;
        }
        Thread pump = pumpThread;
        if (pump != null) {
            try {
                pump.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pumpThread = null;
        }
    }

    @SuppressLint("MissingPermission")
    private AudioRecord openMicrophone() throws LiveMediaStartException {
        // Permission is enforced by the controller before start; the lint
        // suppression records that this call is never reached without a grant.
        int minBuffer = AudioRecord.getMinBufferSize(
                LiveMediaDefaults.AUDIO_SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            throw new LiveMediaStartException(LiveMediaError.UNAVAILABLE,
                    "microphone input not available");
        }
        AudioRecord record;
        try {
            record = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(LiveMediaDefaults.AUDIO_SAMPLE_RATE_HZ)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuffer * 2, 4096))
                    .build();
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            throw new LiveMediaStartException(LiveMediaError.UNAVAILABLE,
                    "microphone configuration rejected");
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new LiveMediaStartException(LiveMediaError.UNAVAILABLE,
                    "microphone is not available");
        }
        return record;
    }

    private MediaCodec createAacEncoder() throws LiveMediaStartException {
        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                LiveMediaDefaults.AUDIO_SAMPLE_RATE_HZ, LiveMediaDefaults.AUDIO_CHANNELS);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, LiveMediaDefaults.AUDIO_BITRATE_BPS);
        try {
            MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            return encoder;
        } catch (IllegalArgumentException | MediaCodec.CodecException | IOException e) {
            throw new LiveMediaStartException(LiveMediaError.ENCODER_UNAVAILABLE,
                    "AAC encoder could not be configured");
        }
    }

    private void pumpLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] pcm = new byte[8192];
        while (running) {
            try {
                int inputIndex = codec.dequeueInputBuffer(10_000);
                if (inputIndex < 0) {
                    continue;
                }
                ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                int frameBytes = inputBuffer == null ? 0 : inputBuffer.capacity();
                if (frameBytes == 0) {
                    continue;
                }
                if (pcm.length < frameBytes) {
                    pcm = new byte[frameBytes];
                }
                int offset = 0;
                while (offset < frameBytes && running) {
                    AudioRecord currentRecord = audioRecord;
                    if (currentRecord == null) {
                        return;
                    }
                    int read = currentRecord.read(pcm, offset, frameBytes - offset);
                    if (read < 0) {
                        throw new IllegalStateException("audio read failed: " + read);
                    }
                    if (read == 0) {
                        // AudioRecord may legally report that no samples are
                        // ready yet. Retry without feeding a short buffer to
                        // AAC; a stop clears running and exits this loop.
                        android.os.SystemClock.sleep(5L);
                        continue;
                    }
                    offset += read;
                }
                if (!running) {
                    return;
                }
                inputBuffer.clear();
                inputBuffer.put(pcm, 0, frameBytes);
                long ptsUs = (System.nanoTime() - startNanos) / 1000L;
                codec.queueInputBuffer(inputIndex, 0, frameBytes, ptsUs, 0);
                drainEncoder(info);
            } catch (IllegalStateException e) {
                Log.w(TAG, "audio pump failed", e);
                return;
            } catch (RuntimeException e) {
                Log.w(TAG, "audio pump failed", e);
                return;
            }
        }
    }

    private void drainEncoder(MediaCodec.BufferInfo info) {
        while (true) {
            int outputIndex = codec.dequeueOutputBuffer(info, 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                captureConfig(codec.getOutputFormat());
                continue;
            }
            if (outputIndex < 0) {
                continue;
            }
            ByteBuffer buffer = codec.getOutputBuffer(outputIndex);
            byte[] frame = new byte[info.size];
            if (buffer != null) {
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                buffer.get(frame);
            }
            codec.releaseOutputBuffer(outputIndex, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                captureConfig(frame);
                continue;
            }
            if (frame.length > 0) {
                server.publishAudioFrame(frame, info.presentationTimeUs);
            }
        }
    }

    private void captureConfig(MediaFormat outputFormat) {
        ByteBuffer configBuffer = outputFormat.getByteBuffer("csd-0");
        if (configBuffer == null) {
            return;
        }
        byte[] config = new byte[configBuffer.remaining()];
        configBuffer.duplicate().get(config);
        if (config.length == 0) {
            return;
        }
        synchronized (formatLock) {
            if (format == null) {
                format = new AudioFormatInfo(config,
                        outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                formatLock.notifyAll();
            }
        }
    }

    private void captureConfig(byte[] codecConfigBytes) {
        // CODEC_CONFIG output buffers carry the AudioSpecificConfig on some
        // devices instead of (or before) the output-format csd.
        synchronized (formatLock) {
            if (format == null && codecConfigBytes.length > 0) {
                format = new AudioFormatInfo(codecConfigBytes,
                        LiveMediaDefaults.AUDIO_SAMPLE_RATE_HZ,
                        LiveMediaDefaults.AUDIO_CHANNELS);
                formatLock.notifyAll();
            }
        }
    }

    private static void releaseQuietly(AudioRecord record) {
        try {
            record.stop();
        } catch (IllegalStateException ignored) {
            // Not recording.
        }
        record.release();
    }
}
