package com.radio.codec2talkie;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Message;

import java.io.IOException;
import java.util.Arrays;

import com.radio.codec2talkie.kiss.KissCallback;
import com.radio.codec2talkie.kiss.KissProcessor;
import com.radio.codec2talkie.tools.AudioTools;
import com.radio.codec2talkie.transport.Transport;
import com.ustadmobile.codec2.Codec2;

public class Codec2Player extends Thread {

    private static final String TAG = Codec2Player.class.getSimpleName();

    public static int PLAYER_DISCONNECT = 1;
    public static int PLAYER_LISTENING = 2;
    public static int PLAYER_RECORDING = 3;
    public static int PLAYER_PLAYING = 4;
    public static int PLAYER_RX_LEVEL = 5;
    public static int PLAYER_TX_LEVEL = 6;

    private static int AUDIO_MIN_LEVEL = -60;
    private static int AUDIO_MAX_LEVEL = -5;

    private final int AUDIO_SAMPLE_SIZE = 8000;
    private final int SLEEP_IDLE_DELAY_MS = 20;
    private final int POST_PLAY_DELAY_MS = 1000;

    private final byte CSMA_PERSISTENCE = (byte)0xff;
    private final byte CSMA_SLOT_TIME = (byte)0x00;
    private final byte TX_DELAY_10MS_UNITS = (byte)(250 / 10);
    private final byte TX_TAIL_10MS_UNITS = (byte)(500 / 10);

    private final int RX_BUFFER_SIZE = 8192;

    private long _codec2Con;

    private int _audioBufferSize;
    private int _audioEncodedBufferSize;

    private boolean _isRunning = true;
    private boolean _needsRecording = false;
    private int _currentStatus = PLAYER_DISCONNECT;

    private Transport _transport;

    // input data, bt -> audio
    private final AudioTrack _audioPlayer;
    private short[] _playbackAudioBuffer;

    // output data., mic -> bt
    private final AudioRecord _audioRecorder;
    private final byte[] _rxDataBuffer;
    private short[] _recordAudioBuffer;
    private char[] _recordAudioEncodedBuffer;

    // callbacks
    private KissProcessor _kissProcessor;
    private final Handler _onPlayerStateChanged;

    public Codec2Player(Transport transport, Handler onPlayerStateChanged, int codec2Mode) {
        _transport = transport;
        _onPlayerStateChanged = onPlayerStateChanged;
        _rxDataBuffer = new byte[RX_BUFFER_SIZE];

        setCodecModeInternal(codec2Mode);

        int _audioRecorderMinBufferSize = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_SIZE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        _audioRecorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_SIZE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                10 * _audioRecorderMinBufferSize);
        _audioRecorder.startRecording();

        int _audioPlayerMinBufferSize = AudioTrack.getMinBufferSize(
                AUDIO_SAMPLE_SIZE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        _audioPlayer = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AUDIO_SAMPLE_SIZE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(10 * _audioPlayerMinBufferSize)
                .build();
        _audioPlayer.play();
    }

    public static int getAudioMinLevel() {
        return AUDIO_MIN_LEVEL;
    }

    public static int getAudioMaxLevel() {
        return AUDIO_MAX_LEVEL;
    }

    public void startPlayback() {
        _needsRecording = false;
    }

    public void startRecording() {
        _needsRecording = true;
    }

    public void stopRunning() {
        _isRunning = false;
    }

    private void setCodecModeInternal(int codecMode) {
        _codec2Con = Codec2.create(codecMode);

        _audioBufferSize = Codec2.getSamplesPerFrame(_codec2Con);
        _audioEncodedBufferSize = Codec2.getBitsSize(_codec2Con); // returns number of bytes

        _recordAudioBuffer = new short[_audioBufferSize];
        _recordAudioEncodedBuffer = new char[_audioEncodedBufferSize];

        _playbackAudioBuffer = new short[_audioBufferSize];

        _kissProcessor = new KissProcessor(CSMA_PERSISTENCE, CSMA_SLOT_TIME,
                TX_DELAY_10MS_UNITS, TX_TAIL_10MS_UNITS, _kissCallback);
    }

    private final KissCallback _kissCallback = new KissCallback() {
        @Override
        protected void onSend(byte[] data) throws IOException {
            _transport.write(data);
        }

        @Override
        protected void onReceive(byte[] data) {
            // split by audio frame and play
            byte [] audioFrame = new byte[_audioEncodedBufferSize];
            for (int i = 0; i < data.length; i += _audioEncodedBufferSize) {
                for (int j = 0; j < _audioEncodedBufferSize && (j + i) < data.length; j++)
                    audioFrame[j] = data[i + j];
                decodeAndPlayAudioFrame(audioFrame);
            }
        }
    };

    private void sendStatusUpdate(int status, int delayMs) {
        if (status == _currentStatus) return;

        _currentStatus = status;
        Message msg = Message.obtain();
        msg.what = status;

        _onPlayerStateChanged.sendMessageDelayed(msg, delayMs);
    }

    private void sendAudioLevelUpdate(short [] pcmAudioSamples, boolean isTx) {
        Message msg = Message.obtain();
        if (isTx)
            msg.what = PLAYER_TX_LEVEL;
        else
            msg.what = PLAYER_RX_LEVEL;
        msg.arg1 = AudioTools.getSampleLevelDb(pcmAudioSamples);
        _onPlayerStateChanged.sendMessage(msg);
    }

    private void decodeAndPlayAudioFrame(byte[] data) {
        Codec2.decode(_codec2Con, _playbackAudioBuffer, data);

        _audioPlayer.write(_playbackAudioBuffer, 0, _audioBufferSize);
        sendAudioLevelUpdate(_playbackAudioBuffer, false);
    }

    private void recordAndSendAudioFrame() throws IOException {
        sendStatusUpdate(PLAYER_RECORDING, 0);

        _audioRecorder.read(_recordAudioBuffer, 0, _audioBufferSize);
        sendAudioLevelUpdate(_recordAudioBuffer, true);

        Codec2.encode(_codec2Con, _recordAudioBuffer, _recordAudioEncodedBuffer);

        byte [] frame = new byte[_recordAudioEncodedBuffer.length];

        for (int i = 0; i < _recordAudioEncodedBuffer.length; i++) {
            frame[i] = (byte)_recordAudioEncodedBuffer[i];
        }
        _kissProcessor.send(frame);
    }

    private boolean receiveAndPlayAudioFrame() throws IOException {
        int bytesRead = _transport.read(_rxDataBuffer);
        if (bytesRead > 0) {
            sendStatusUpdate(PLAYER_PLAYING, 0);
            _kissProcessor.receive(Arrays.copyOf(_rxDataBuffer, bytesRead));
            return true;
        }
        return false;
    }

    private void processRecordPlaybackToggle() throws IOException {
        // playback -> recording
        if (_needsRecording && _audioRecorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            _audioPlayer.stop();
            _audioRecorder.startRecording();
            sendAudioLevelUpdate(null, false);
        }
        // recording -> playback
        if (!_needsRecording && _audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            _kissProcessor.flush();
            _audioRecorder.stop();
            _audioPlayer.play();
            sendAudioLevelUpdate(null, true);
        }
    }

    private void cleanup() {
        _audioRecorder.stop();
        _audioRecorder.release();

        _audioPlayer.stop();
        _audioPlayer.release();

        Codec2.destroy(_codec2Con);

        try {
            _kissProcessor.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            _transport.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        setPriority(Thread.MAX_PRIORITY);
        try {
            sendStatusUpdate(PLAYER_LISTENING, 0);
            _kissProcessor.initialize();

            while (_isRunning) {
                processRecordPlaybackToggle();

                // recording
                if (_audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    recordAndSendAudioFrame();
                } else {
                    // playback
                    if (!receiveAndPlayAudioFrame()) {
                        // idling
                        try {
                            if (_currentStatus != PLAYER_LISTENING) {
                                sendAudioLevelUpdate(null, false);
                                sendAudioLevelUpdate(null, true);
                            }
                            sendStatusUpdate(PLAYER_LISTENING, POST_PLAY_DELAY_MS);
                            Thread.sleep(SLEEP_IDLE_DELAY_MS);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        sendStatusUpdate(PLAYER_DISCONNECT, 0);
        cleanup();
    }
}
