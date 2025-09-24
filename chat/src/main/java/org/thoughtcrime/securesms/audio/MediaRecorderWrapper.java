package org.thoughtcrime.securesms.audio;

import android.media.MediaRecorder;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.difft.android.base.log.lumberjack.L;

import util.StreamUtil;

import java.io.IOException;

/**
 * Wrap Android's {@link MediaRecorder} for use with voice notes.
 */
public class MediaRecorderWrapper implements Recorder {

    private static final String TAG = L.INSTANCE.tag(MediaRecorderWrapper.class);

    public static final int SAMPLE_RATE = 44100;
    public static final int CHANNELS = 1;
    public static final int BIT_RATE = 128000;

    private MediaRecorder recorder = null;

    private ParcelFileDescriptor outputFileDescriptor;

    @Override
    public void start(ParcelFileDescriptor fileDescriptor) throws IOException {
        L.i(() -> "Recording voice note using MediaRecorderWrapper.");
        this.outputFileDescriptor = fileDescriptor;

        recorder = new MediaRecorder();

        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
            recorder.setOutputFile(fileDescriptor.getFileDescriptor());
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(getSampleRate());
            recorder.setAudioEncodingBitRate(BIT_RATE);
            recorder.setAudioChannels(CHANNELS);
            recorder.prepare();
            recorder.start();
        } catch (RuntimeException e) {
            L.w(() -> "Unable to start recording" + e);
            recorder.release();
            recorder = null;
            StreamUtil.close(outputFileDescriptor);
            outputFileDescriptor = null;
            throw new IOException(e);
        }
    }

    @Override
    public void stop() {
        if (recorder == null) {
            return;
        }

        try {
            recorder.stop();
        } catch (RuntimeException e) {
            if (e.getClass() != RuntimeException.class) {
                throw e;
            } else {
                L.d(() -> "Recording stopped with no data captured.");
            }
        } finally {
            recorder.release();
            recorder = null;
            StreamUtil.close(outputFileDescriptor);
            outputFileDescriptor = null;
        }
    }

    public static int getSampleRate() {
        if ("Xiaomi".equals(Build.MANUFACTURER) && "Mi 9T".equals(Build.MODEL)) {
            // Recordings sound robotic with the standard sample rate.
            return 44000;
        }
        return SAMPLE_RATE;
    }
}
