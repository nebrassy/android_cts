/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.cts.verifier.biometrics;

import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricPrompt.AuthenticationCallback;
import android.hardware.biometrics.BiometricPrompt.AuthenticationResult;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import com.android.cts.verifier.PassFailButtons;
import com.android.cts.verifier.R;

import java.util.concurrent.Executor;

/**
 * Abstract base class for tests in this directory.
 */
public abstract class AbstractBaseTest extends PassFailButtons.Activity {

    private static final int REQUEST_ENROLL_WHEN_NONE_ENROLLED = 1;

    abstract protected String getTag();
    abstract protected boolean isOnPauseAllowed();

    protected final Handler mHandler = new Handler(Looper.getMainLooper());
    protected final Executor mExecutor = mHandler::post;

    protected boolean mCurrentlyEnrolling;

    BiometricManager mBiometricManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBiometricManager = getSystemService(BiometricManager.class);
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Assume we only enable the pass button when all tests pass. There actually  isn't a way
        // to easily do something like `this.isTestPassed()`
        if (!getPassButton().isEnabled() && !isOnPauseAllowed()) {
            showToastAndLog("This test must be completed without pausing the app");
            // Do not allow the test to continue if it loses foreground. Testers must start over.
            // 1) This is to avoid any potential change to the current enrollment / biometric state.
            // 2) The authentication UI must not affect the caller's activity lifecycle.
            finish();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        mCurrentlyEnrolling = false;

        if (requestCode == REQUEST_ENROLL_WHEN_NONE_ENROLLED) {
            onBiometricEnrollFinished();
        }
    }

    void showToastAndLog(String s) {
        Log.d(getTag(), s);
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    protected void onBiometricEnrollFinished() {
    }

    void checkAndEnroll(Button enrollButton, int requestedStrength,
            int[] acceptableConfigStrengths) {
        // Check that no biometrics (of any strength) are enrolled
        int result = mBiometricManager.canAuthenticate(Authenticators.BIOMETRIC_WEAK);
        if (result == BiometricManager.BIOMETRIC_SUCCESS) {
            showToastAndLog("Please ensure that all biometrics are removed before starting"
                    + " this test");
            return;
        }

        result = mBiometricManager.canAuthenticate(requestedStrength);
        if (result == BiometricManager.BIOMETRIC_SUCCESS) {
            showToastAndLog("Please ensure that all biometrics are removed before starting"
                    + " this test");
        } else if (result == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) {
            boolean configContainsRequestedStrength = false;
            for (int strength : acceptableConfigStrengths) {
                if (Utils.deviceConfigContains(this, strength)) {
                    configContainsRequestedStrength = true;
                    break;
                }
            }

            if (configContainsRequestedStrength) {
                showToastAndLog("Your configuration contains the requested biometric strength, but"
                        + " is inconsistent with BiometricManager.");
            } else {
                showToastAndLog("This device does not have a sensor meeting the requested strength,"
                        + " you may pass this test");
                enrollButton.setEnabled(false);
                getPassButton().setEnabled(true);
            }
        } else if (result == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            startBiometricEnroll(REQUEST_ENROLL_WHEN_NONE_ENROLLED, requestedStrength);
        } else {
            showToastAndLog("Unexpected result: " + result + ". Please ensure you have removed"
                    + "all biometric enrollments.");
        }
    }

    private void startBiometricEnroll(int requestCode, int requestedStrength) {
        mCurrentlyEnrolling = true;
        final Intent enrollIntent = new Intent(Settings.ACTION_BIOMETRIC_ENROLL);
        enrollIntent.putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                requestedStrength);

        startActivityForResult(enrollIntent, requestCode);
    }

    private boolean isPublicAuthenticatorConstant(int authenticator) {
        final int[] publicConstants =  {
                Authenticators.BIOMETRIC_STRONG,
                Authenticators.BIOMETRIC_WEAK,
                Authenticators.DEVICE_CREDENTIAL
        };
        for (int constant : publicConstants) {
            if (authenticator == constant) {
                return true;
            }
        }
        return false;
    }

    void testInvalidInputs(Runnable successRunnable) {
        for (int i = 0; i < 32; i++) {
            final int authenticator = 1 << i;
            // If it's a public constant, no need to test
            if (isPublicAuthenticatorConstant(authenticator)) {
                continue;
            }

            // Test canAuthenticate(int)
            boolean exceptionCaught = false;
            try {
                mBiometricManager.canAuthenticate(authenticator);
            } catch (Exception e) {
                exceptionCaught = true;
            }

            if (!exceptionCaught) {
                showToastAndLog("Non-public constants provided to canAuthenticate(int) must throw an"
                        + " exception");
                return;
            }

            // Test setAllowedAuthenticators(int)
            exceptionCaught = false;
            try {
                final BiometricPrompt.Builder builder = new BiometricPrompt.Builder(this);
                builder.setAllowedAuthenticators(authenticator);
                builder.setTitle("This should never be shown");
                builder.setNegativeButton("Cancel", mExecutor,
                        (dialog, which) -> {
                            // Do nothing
                        });
                final BiometricPrompt prompt = builder.build();
                prompt.authenticate(new CancellationSignal(), mExecutor,
                        new AuthenticationCallback() {

                });
            } catch (Exception e) {
                exceptionCaught = true;
            }

            if (!exceptionCaught) {
                showToastAndLog("Non-public constants provided to setAllowedAuthenticators(int) must"
                        + " throw an exception");
                return;
            }
        }

        successRunnable.run();
    }

    void testBiometricRejectDoesNotEndAuthentication(Runnable successRunnable) {
        Utils.showInstructionDialog(this,
                R.string.biometric_test_reject_continues_instruction_title,
                R.string.biometric_test_reject_continues_instruction_contents,
                R.string.biometric_test_reject_continues_instruction_continue,
                (dialog, which) -> {
                    if (which == DialogInterface.BUTTON_POSITIVE) {
                        final BiometricPrompt.Builder builder = new BiometricPrompt.Builder(this);
                        builder.setTitle("Reject, then authenticate");
                        builder.setDescription("Please present a non-enrolled biometric to trigger"
                                + " onAuthenticationFailed. Then present an enrolled biometric"
                                + " to finish this test.");
                        builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK);
                        builder.setNegativeButton("Cancel", mExecutor, (dialog1, which1) -> {
                            // Do nothing
                        });

                        final BiometricPrompt prompt = builder.build();
                        prompt.authenticate(new CancellationSignal(), mExecutor,
                                new AuthenticationCallback() {
                                    boolean rejectReceived;
                                    @Override
                                    public void onAuthenticationSucceeded(AuthenticationResult
                                            result) {
                                        if (rejectReceived) {
                                            successRunnable.run();
                                        } else {
                                            showToastAndLog("Please present a non-enrolled"
                                                    + " biometric first");
                                        }
                                    }

                                    @Override
                                    public void onAuthenticationFailed() {
                                        rejectReceived = true;
                                    }
                                });
                    }
                });
    }
}
