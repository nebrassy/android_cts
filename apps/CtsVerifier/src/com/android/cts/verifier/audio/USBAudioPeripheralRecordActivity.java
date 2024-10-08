/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.cts.verifier.audio;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.android.compatibility.common.util.CddTest;
import com.android.cts.verifier.R;
import com.android.cts.verifier.audio.audiolib.WaveScopeView;

import org.hyphonate.megaaudio.common.BuilderBase;
import org.hyphonate.megaaudio.common.StreamBase;
import org.hyphonate.megaaudio.duplex.DuplexAudioManager;
import org.hyphonate.megaaudio.player.sources.SinAudioSourceProvider;
import org.hyphonate.megaaudio.recorder.sinks.AppCallback;
import org.hyphonate.megaaudio.recorder.sinks.AppCallbackAudioSinkProvider;

@CddTest(requirement = "7.8.2.2/H-1-1|7.7.2/C-2-1,C-2-2")
public class USBAudioPeripheralRecordActivity extends USBAudioPeripheralActivity {
    private static final String TAG = "USBAudioPeripheralRecordActivity";

    // MegaAudio
    private static final int NUM_CHANNELS = 2;
    private DuplexAudioManager   mDuplexManager;

    private boolean mIsPlaying = false;
    private boolean mIsRecording = false;

    // Widgets
    private Button mRecordBtn;
    private Button mRecordLoopbackBtn;

    private LocalClickListener mButtonClickListener = new LocalClickListener();

    private WaveScopeView mWaveView = null;

    public USBAudioPeripheralRecordActivity() {
        super(false); // Mandated peripheral is NOT required
    }

    public boolean startRecording(boolean withLoopback) {
        if (mInputDevInfo == null) {
            return false;
        }

        int systemSampleRate = StreamBase.getSystemSampleRate();
        int numBufferFrames = StreamBase.getNumBurstFrames(BuilderBase.TYPE_NONE);

        mDuplexManager = new DuplexAudioManager(
                withLoopback ? new SinAudioSourceProvider() : null,
                new AppCallbackAudioSinkProvider(new ScopeRefreshCallback()));

        if (mDuplexManager.setupStreams(
                withLoopback ? BuilderBase.TYPE_JAVA : BuilderBase.TYPE_NONE,
                BuilderBase.TYPE_JAVA) != StreamBase.OK) {
            Toast.makeText(
                    this, "Couldn't create recorder. Please check permissions.", Toast.LENGTH_LONG)
                    .show();
            return mIsRecording = false;
        }

        if (mDuplexManager.start() != StreamBase.OK) {
            Toast.makeText(
                    this, "Couldn't start recording. Please check permissions.", Toast.LENGTH_LONG)
                    .show();
            return mIsRecording = false;
        } else {
            mIsRecording = true;
            mIsPlaying = withLoopback;
        }
        return mIsRecording;
    }

    public int stopRecording() {
        int result = StreamBase.OK;
        if (mDuplexManager != null) {
            result = mDuplexManager.stop();
        }
        mIsRecording = false;

        return result;
    }

    public boolean isRecording() {
        return mIsRecording;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.uap_record_panel);

        // MegaAudio Initialization
        StreamBase.setup(this);

        connectPeripheralStatusWidgets();

        // Local widgets
        mRecordBtn = (Button)findViewById(R.id.uap_recordRecordBtn);
        mRecordBtn.setOnClickListener(mButtonClickListener);
        mRecordLoopbackBtn = (Button)findViewById(R.id.uap_recordRecordLoopBtn);
        mRecordLoopbackBtn.setOnClickListener(mButtonClickListener);

        mWaveView = (WaveScopeView)findViewById(R.id.uap_recordWaveView);
        mWaveView.setBackgroundColor(Color.DKGRAY);
        mWaveView.setTraceColor(Color.WHITE);

        setPassFailButtonClickListeners();
        setInfoResources(R.string.usbaudio_record_test, R.string.usbaudio_record_info, -1);

        connectUSBPeripheralUI();
    }

    //
    // USBAudioPeripheralActivity
    //
    void enableTestUI(boolean enable) {
        mRecordBtn.setEnabled(enable);
        mRecordLoopbackBtn.setEnabled(enable);
    }

    public void updateConnectStatus() {
        mRecordBtn.setEnabled(mIsPeripheralAttached);
        mRecordLoopbackBtn.setEnabled(mIsPeripheralAttached);
        getPassButton().setEnabled(mIsPeripheralAttached);
    }

    public class LocalClickListener implements View.OnClickListener {
        @Override
        public void onClick(View view) {
            int id = view.getId();
            if (id == R.id.uap_recordRecordBtn) {
                if (!isRecording()) {
                    if (startRecording(false)) {
                        mRecordBtn.setText(getString(R.string.audio_uap_record_stopBtn));
                        mRecordLoopbackBtn.setEnabled(false);
                    }
                } else {
                    stopRecording();
                    mRecordBtn.setText(getString(R.string.audio_uap_record_recordBtn));
                    mRecordLoopbackBtn.setEnabled(true);
                }
            } else if (id == R.id.uap_recordRecordLoopBtn) {
                if (!isRecording()) {
                    if (startRecording(true)) {
                        mRecordLoopbackBtn.setText(getString(R.string.audio_uap_record_stopBtn));
                        mRecordBtn.setEnabled(false);
                    }
                } else {
                    stopRecording();
                    mRecordLoopbackBtn.setText(
                        getString(R.string.audio_uap_record_recordLoopbackBtn));
                    mRecordBtn.setEnabled(true);
                }
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        stopRecording();
    }

    public class ScopeRefreshCallback implements AppCallback {
        @Override
        public void onDataReady(float[] audioData, int numFrames) {
            mWaveView.setPCMFloatBuff(audioData, NUM_CHANNELS, numFrames);
        }
    }
}
