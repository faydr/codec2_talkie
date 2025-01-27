package com.radio.codec2talkie.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;

import com.radio.codec2talkie.connect.TcpIpSocketHandler;
import com.radio.codec2talkie.protocol.Callback;
import com.radio.codec2talkie.protocol.Protocol;
import com.radio.codec2talkie.protocol.ProtocolFactory;
import com.radio.codec2talkie.settings.PreferenceKeys;
import com.radio.codec2talkie.tools.AudioTools;
import com.radio.codec2talkie.transport.Transport;
import com.radio.codec2talkie.transport.TransportFactory;
import com.ustadmobile.codec2.Codec2;

public class AudioProcessor extends Thread {

    private static final String TAG = AudioProcessor.class.getSimpleName();

    public static final int PROCESSOR_DISCONNECTED = 1;
    public static final int PROCESSOR_CONNECTED = 2;
    public static final int PROCESSOR_LISTENING = 3;
    public static final int PROCESSOR_RECORDING = 4;
    public static final int PROCESSOR_RECEIVING = 5;
    public static final int PROCESSOR_PLAYING = 6;
    public static final int PROCESSOR_RX_LEVEL = 7;
    public static final int PROCESSOR_TX_LEVEL = 8;
    public static final int PROCESSOR_CODEC_ERROR = 9;
    public static final int PROCESSOR_RX_RADIO_LEVEL = 10;

    public static final int PROCESSOR_PROCESS = 11;
    public static final int PROCESSOR_QUIT = 12;

    private static final int AUDIO_MIN_LEVEL = -70;
    private static final int AUDIO_MAX_LEVEL = 0;
    private final int AUDIO_SAMPLE_SIZE = 8000;

    private final int PROCESS_INTERVAL_MS = 20;
    private final int LISTEN_AFTER_MS = 1500;

    private final int SIGNAL_LEVEL_EVENT_SIZE = 4;

    private long _codec2Con;

    private int _audioBufferSize;
    private int _codec2FrameSize;

    private boolean _needsRecording = false;
    private int _currentStatus = PROCESSOR_DISCONNECTED;

    private final Protocol _protocol;
    private final Transport _transport;

    // input data, bt -> audio
    private AudioTrack _systemAudioPlayer;
    private short[] _playbackAudioBuffer;

    // output data., mic -> bt
    private AudioRecord _systemAudioRecorder;
    private short[] _recordAudioBuffer;
    private char[] _recordAudioEncodedBuffer;

    // callbacks
    private final Handler _onPlayerStateChanged;
    private Handler _onMessageReceived;
    private final Timer _processPeriodicTimer;

    // listen timer
    private Timer _listenTimer;

    private final Context _context;
    private final SharedPreferences _sharedPreferences;

    public AudioProcessor(TransportFactory.TransportType transportType, ProtocolFactory.ProtocolType protocolType, int codec2Mode,
                          Handler onPlayerStateChanged, Context context) throws IOException {
        _onPlayerStateChanged = onPlayerStateChanged;

        _context = context;
        _sharedPreferences = PreferenceManager.getDefaultSharedPreferences(_context);

        _transport  = TransportFactory.create(transportType);
        _protocol = ProtocolFactory.create(protocolType, codec2Mode, context);

        _processPeriodicTimer = new Timer();

        constructCodec2(codec2Mode);
        constructSystemAudioDevices();
    }

    private void constructSystemAudioDevices() {
        int _audioRecorderMinBufferSize = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_SIZE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        boolean isVoiceCommunication = _sharedPreferences.getBoolean(PreferenceKeys.APP_AUDIO_INPUT_VOICE_COMMUNICATION, false);
        int audioSource = MediaRecorder.AudioSource.MIC;
        if (isVoiceCommunication) {
            audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        }
        _systemAudioRecorder = new AudioRecord(
                audioSource,
                AUDIO_SAMPLE_SIZE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                10 * _audioRecorderMinBufferSize);

        int _audioPlayerMinBufferSize = AudioTrack.getMinBufferSize(
                AUDIO_SAMPLE_SIZE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        boolean isSpeakerOutput = _sharedPreferences.getBoolean(PreferenceKeys.APP_AUDIO_OUTPUT_SPEAKER, true);
        int usage = AudioAttributes.USAGE_VOICE_COMMUNICATION;
        if (isSpeakerOutput) {
            usage = AudioAttributes.USAGE_MEDIA;
        }
        _systemAudioPlayer = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(usage)
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
    }

    private void constructCodec2(int codecMode) {
        _codec2Con = Codec2.create(codecMode);

        _audioBufferSize = Codec2.getSamplesPerFrame(_codec2Con);
        _codec2FrameSize = Codec2.getBitsSize(_codec2Con); // returns number of bytes

        _recordAudioBuffer = new short[_audioBufferSize];
        _recordAudioEncodedBuffer = new char[_codec2FrameSize];

        _playbackAudioBuffer = new short[_audioBufferSize];
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
        if (_currentStatus != PROCESSOR_DISCONNECTED) {
            Log.i(TAG, "stopRunning()");
            Message msg = new Message();
            msg.what = PROCESSOR_QUIT;
            _onMessageReceived.sendMessage(msg);
        }
    }

    private void sendStatusUpdate(int newStatus) {
        if (newStatus != PROCESSOR_LISTENING) {
            restartListening();
        }
        if (newStatus != _currentStatus) {
            _currentStatus = newStatus;
            Message msg = Message.obtain();
            msg.what = newStatus;

            _onPlayerStateChanged.sendMessage(msg);
        }
    }

    private void sendRxRadioLevelUpdate(int rssi, int snr) {
        Message msg = Message.obtain();
        msg.what = PROCESSOR_RX_RADIO_LEVEL;
        msg.arg1 = rssi;
        msg.arg2 = snr;
        _onPlayerStateChanged.sendMessage(msg);
    }

    private void sendRxAudioLevelUpdate(short [] pcmAudioSamples) {
        Message msg = Message.obtain();
        msg.what = PROCESSOR_RX_LEVEL;
        msg.arg1 = AudioTools.getSampleLevelDb(pcmAudioSamples);
        _onPlayerStateChanged.sendMessage(msg);
    }

    private void sendTxAudioLevelUpdate(short [] pcmAudioSamples) {
        Message msg = Message.obtain();
        msg.what = PROCESSOR_TX_LEVEL;
        msg.arg1 = AudioTools.getSampleLevelDb(pcmAudioSamples);
        _onPlayerStateChanged.sendMessage(msg);
    }

    private void recordAndSendAudioFrame() throws IOException {
        sendStatusUpdate(PROCESSOR_RECORDING);

        _systemAudioRecorder.read(_recordAudioBuffer, 0, _audioBufferSize);
        sendTxAudioLevelUpdate(_recordAudioBuffer);

        Codec2.encode(_codec2Con, _recordAudioBuffer, _recordAudioEncodedBuffer);

        byte [] frame = new byte[_recordAudioEncodedBuffer.length];

        for (int i = 0; i < _recordAudioEncodedBuffer.length; i++) {
            frame[i] = (byte)_recordAudioEncodedBuffer[i];
        }
        _protocol.send(frame);
    }

    private void decodeAndPlayAudioFrame(byte[] data) {
        Codec2.decode(_codec2Con, _playbackAudioBuffer, data);
        sendRxAudioLevelUpdate(_playbackAudioBuffer);
        _systemAudioPlayer.write(_playbackAudioBuffer, 0, _audioBufferSize);
    }

    private final Callback _protocolReceiveCallback = new Callback() {
        @Override
        protected void onReceiveAudioFrames(byte[] audioFrame) {
            sendStatusUpdate(PROCESSOR_PLAYING);
            decodeAndPlayAudioFrame(audioFrame);
        }

        @Override
        protected void onReceiveSignalLevel(byte [] rawData) {
            ByteBuffer data = ByteBuffer.wrap(rawData);
            if (rawData.length == SIGNAL_LEVEL_EVENT_SIZE) {
                short rssi = data.getShort();
                short snr = data.getShort();
                sendRxRadioLevelUpdate(rssi, snr);
            } else {
                Log.e(TAG, "Signal event of wrong size");
            }
        }

        @Override
        protected void onProtocolRxError() {
            sendStatusUpdate(PROCESSOR_CODEC_ERROR);
            Log.e(TAG, "Protocol RX error");
        }
    };

    private void restartListening() {
        cancelListening();
        startListening();
    }

    private void startListening() {
        if (_currentStatus == PROCESSOR_LISTENING) {
            return;
        }
        _listenTimer = new Timer();
        _listenTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                onListening();
            }
        }, LISTEN_AFTER_MS);
    }

    private void cancelListening() {
        try {
            if (_listenTimer != null) {
                _listenTimer.cancel();
                _listenTimer.purge();
            }
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }

    private void onListening() {
        sendRxAudioLevelUpdate(null);
        sendTxAudioLevelUpdate(null);
        sendRxRadioLevelUpdate(0, 0);
        sendStatusUpdate(PROCESSOR_LISTENING);
    }

    private void processRecordPlaybackToggle() throws IOException {
        // playback -> recording
        if (_needsRecording && _systemAudioRecorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            _systemAudioPlayer.stop();
            _systemAudioRecorder.startRecording();
            sendRxAudioLevelUpdate(null);
        }
        // recording -> playback
        if (!_needsRecording && _systemAudioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            _protocol.flush();
            _systemAudioRecorder.stop();
            _systemAudioPlayer.play();
            sendTxAudioLevelUpdate(null);
        }
    }

    private void cleanup() {
        Log.i(TAG, "cleanup() started");
        _systemAudioRecorder.stop();
        _systemAudioRecorder.release();

        _systemAudioPlayer.stop();
        _systemAudioPlayer.release();

        Codec2.destroy(_codec2Con);

        try {
            _protocol.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
        _protocol.close();
        try {
            _transport.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        Log.i(TAG, "cleanup() completed");
    }

    private void processRxTx() throws IOException {
        processRecordPlaybackToggle();

        // recording
        if (_systemAudioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            recordAndSendAudioFrame();
        } else {
            // playback
            if (_protocol.receive(_protocolReceiveCallback)) {
                sendStatusUpdate(PROCESSOR_RECEIVING);
            }
        }
    }

    private void quitProcessing() {
        Log.i(TAG, "quitProcessing()");
        _processPeriodicTimer.cancel();
        _processPeriodicTimer.purge();
        Looper.myLooper().quitSafely();
    }

    private void onProcessorIncomingMessage(Message msg) {
        switch (msg.what) {
            case PROCESSOR_PROCESS:
                try {
                    processRxTx();
                } catch (IOException e) {
                    e.printStackTrace();
                    quitProcessing();
                }
                break;
            case PROCESSOR_QUIT:
                quitProcessing();
                break;
            default:
                break;
        }
    }

    private void startProcessorMessageHandler() {
        _onMessageReceived = new Handler(Looper.myLooper()) {
            @Override
            public void handleMessage(Message msg) {
                onProcessorIncomingMessage(msg);
            }
        };
        _processPeriodicTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Message msg = new Message();
                msg.what = PROCESSOR_PROCESS;
                _onMessageReceived.sendMessage(msg);
            }
        }, 0, PROCESS_INTERVAL_MS);
    }

    @Override
    public void run() {
        Log.i(TAG, "Starting message loop");
        setPriority(Thread.MAX_PRIORITY);
        Looper.prepare();

        sendStatusUpdate(PROCESSOR_CONNECTED);
        _systemAudioPlayer.play();

        try {
            _protocol.initialize(_transport, _context);
            startProcessorMessageHandler();
            Looper.loop();
        } catch (IOException e) {
            e.printStackTrace();
        }

        cleanup();
        sendStatusUpdate(PROCESSOR_DISCONNECTED);
        Log.i(TAG, "Exiting message loop");
    }
}
