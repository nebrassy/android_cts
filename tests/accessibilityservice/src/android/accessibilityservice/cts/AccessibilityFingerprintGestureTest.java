/**
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.accessibilityservice.cts;

import static android.content.pm.PackageManager.FEATURE_FINGERPRINT;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.accessibility.cts.common.AccessibilityDumpOnFailureRule;
import android.accessibility.cts.common.InstrumentedAccessibilityServiceTestRule;
import android.accessibilityservice.FingerprintGestureController;
import android.accessibilityservice.FingerprintGestureController.FingerprintGestureCallback;
import android.accessibilityservice.cts.activities.AccessibilityEndToEndActivity;
import android.app.Instrumentation;
import android.hardware.fingerprint.FingerprintManager;
import android.os.CancellationSignal;
import android.platform.test.annotations.AppModeFull;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.CddTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Verify that a service listening for fingerprint gestures gets called back when apps
 * use the fingerprint sensor to authenticate.
 */
@AppModeFull
@RunWith(AndroidJUnit4.class)
@CddTest(requirements = {"3.10/C-1-1,C-1-2"})
@Presubmit
public class AccessibilityFingerprintGestureTest {
    private static final int FINGERPRINT_CALLBACK_TIMEOUT = 3000;

    FingerprintManager mFingerprintManager;
    StubFingerprintGestureService mFingerprintGestureService;
    FingerprintGestureController mFingerprintGestureController;
    CancellationSignal mCancellationSignal = new CancellationSignal();

    private ActivityTestRule<AccessibilityEndToEndActivity> mActivityRule =
            new ActivityTestRule<>(AccessibilityEndToEndActivity.class, false, false);

    private InstrumentedAccessibilityServiceTestRule<StubFingerprintGestureService> mServiceRule =
            new InstrumentedAccessibilityServiceTestRule<>(StubFingerprintGestureService.class);

    private AccessibilityDumpOnFailureRule mDumpOnFailureRule =
            new AccessibilityDumpOnFailureRule();

    @Rule
    public final RuleChain mRuleChain = RuleChain
            .outerRule(mActivityRule)
            .around(mServiceRule)
            .around(mDumpOnFailureRule);

    @Mock FingerprintManager.AuthenticationCallback mMockAuthenticationCallback;
    @Mock FingerprintGestureCallback mMockFingerprintGestureCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        mFingerprintManager = instrumentation.getContext().getPackageManager()
                .hasSystemFeature(FEATURE_FINGERPRINT)
                ? instrumentation.getContext().getSystemService(FingerprintManager.class) : null;
        mFingerprintGestureService = mServiceRule.getService();
        mFingerprintGestureController =
                mFingerprintGestureService.getFingerprintGestureController();
    }

    @Test
    public void testGestureDetectionListener_whenAuthenticationStartsAndStops_calledBack() {
        assumeTrue("Fingerprint gesture detection is not available",
                mFingerprintGestureController.isGestureDetectionAvailable());
        assumeTrue("No enrolled fingerprints; cannot open fingerprint prompt",
                mFingerprintManager.hasEnrolledFingerprints());
        // Launch an activity to make sure we're in the foreground
        mActivityRule.launchActivity(null);
        mFingerprintGestureController.registerFingerprintGestureCallback(
                mMockFingerprintGestureCallback, null);
        try {
            mFingerprintManager.authenticate(
                    null, mCancellationSignal, 0, mMockAuthenticationCallback, null);

            verify(mMockFingerprintGestureCallback,
                    timeout(FINGERPRINT_CALLBACK_TIMEOUT).atLeastOnce())
                    .onGestureDetectionAvailabilityChanged(false);
            assertFalse(mFingerprintGestureController.isGestureDetectionAvailable());
            reset(mMockFingerprintGestureCallback);
        } finally {
            mCancellationSignal.cancel();
        }
        verify(mMockFingerprintGestureCallback, timeout(FINGERPRINT_CALLBACK_TIMEOUT).atLeastOnce())
                .onGestureDetectionAvailabilityChanged(true);
        assertTrue(mFingerprintGestureController.isGestureDetectionAvailable());
        mFingerprintGestureController.unregisterFingerprintGestureCallback(
                mMockFingerprintGestureCallback);
    }
}
