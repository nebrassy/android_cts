/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.telecom.cts;

import static android.telecom.cts.TestUtils.*;

import static com.android.compatibility.common.util.BlockedNumberUtil.deleteBlockedNumber;
import static com.android.compatibility.common.util.BlockedNumberUtil.insertBlockedNumber;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.os.ParcelUuid;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.CallEndpoint;
import android.telecom.CallEndpointException;
import android.telecom.CallScreeningService;
import android.telecom.Connection;
import android.telecom.ConnectionService;
import android.telecom.InCallService;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.TelephonyManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Extended suite of tests that use {@link CtsConnectionService} and {@link MockInCallService} to
 * verify the functionality of the Telecom service.
 */
public class ExtendedInCallServiceTest extends BaseTelecomTestWithMockServices {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if (mShouldTestTelecom) {
            setupConnectionService(null, FLAG_REGISTER | FLAG_ENABLE);
        }
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    public void testAddNewOutgoingCallAndThenDisconnect() {
        if (!mShouldTestTelecom) {
            return;
        }

        placeAndVerifyCall();
        verifyConnectionForOutgoingCall();

        final MockInCallService inCallService = mInCallCallbacks.getService();
        inCallService.disconnectLastCall();

        assertNumCalls(inCallService, 0);
    }

    public void testMuteAndUnmutePhone() {
        if (!mShouldTestTelecom) {
            return;
        }

        placeAndVerifyCall();
        final MockConnection connection = verifyConnectionForOutgoingCall();

        final MockInCallService inCallService = mInCallCallbacks.getService();

        final Call call = inCallService.getLastCall();

        assertCallState(call, Call.STATE_DIALING);

        assertMuteState(connection, false);

        // Explicitly call super implementation to enable detection of CTS coverage
        ((InCallService) inCallService).setMuted(true);

        assertMuteState(connection, true);
        assertMuteState(inCallService, true);
        assertMuteEndpoint(connection, true);
        assertMuteEndpoint(inCallService, true);

        inCallService.setMuted(false);
        assertMuteState(connection, false);
        assertMuteState(inCallService, false);
        assertMuteEndpoint(connection, false);
        assertMuteEndpoint(inCallService, false);
    }

    public void testSwitchAudioRoutes() {
        if (!mShouldTestTelecom) {
            return;
        }

        placeAndVerifyCall();
        final MockConnection connection = verifyConnectionForOutgoingCall();

        final MockInCallService inCallService = mInCallCallbacks.getService();

        final Call call = inCallService.getLastCall();
        assertCallState(call, Call.STATE_DIALING);

        final int currentInvokeCount = mOnCallAudioStateChangedCounter.getInvokeCount();
        mOnCallAudioStateChangedCounter.waitForCount(WAIT_FOR_STATE_CHANGE_TIMEOUT_MS);
        CallAudioState callAudioState =
                (CallAudioState) mOnCallAudioStateChangedCounter.getArgs(0)[0];

        // We need to check what audio routes are available. If speaker and either headset or
        // earpiece aren't available, then we should skip this test.

        int availableRoutes = callAudioState.getSupportedRouteMask();
        if ((availableRoutes & CallAudioState.ROUTE_SPEAKER) == 0) {
            return;
        }
        if ((availableRoutes & CallAudioState.ROUTE_WIRED_OR_EARPIECE) == 0) {
            return;
        }
        // Determine what the second route to go to after SPEAKER should be, depending on what's
        // supported.
        int secondRoute = (availableRoutes & CallAudioState.ROUTE_EARPIECE) == 0 ?
                CallAudioState.ROUTE_WIRED_HEADSET : CallAudioState.ROUTE_EARPIECE;

        // Explicitly call super implementation to enable detection of CTS coverage
        ((InCallService) inCallService).setAudioRoute(CallAudioState.ROUTE_SPEAKER);
        mOnCallAudioStateChangedCounter.waitForCount(currentInvokeCount + 1,
                WAIT_FOR_STATE_CHANGE_TIMEOUT_MS);
        assertAudioRoute(connection, CallAudioState.ROUTE_SPEAKER);
        assertAudioRoute(inCallService, CallAudioState.ROUTE_SPEAKER);

        inCallService.setAudioRoute(secondRoute);
        mOnCallAudioStateChangedCounter.waitForCount(currentInvokeCount + 2,
                WAIT_FOR_STATE_CHANGE_TIMEOUT_MS);
        assertAudioRoute(connection, secondRoute);
        assertAudioRoute(inCallService, secondRoute);

        // Call requestBluetoothAudio on a device. This will be a noop since no devices are
        // connected.
        if(TestUtils.HAS_BLUETOOTH) {
            ((InCallService) inCallService).requestBluetoothAudio(TestUtils.BLUETOOTH_DEVICE1);
        }
    }

    /**
     * Tests that DTMF Tones are sent from the {@link InCallService} to the
     * {@link ConnectionService} in the correct sequence.
     *
     * @see {@link Call#playDtmfTone(char)}
     * @see {@link Call#stopDtmfTone()}
     */
    public void testPlayAndStopDtmfTones() {
        if (!mShouldTestTelecom) {
            return;
        }

        placeAndVerifyCall();
        final MockConnection connection = verifyConnectionForOutgoingCall();

        final MockInCallService inCallService = mInCallCallbacks.getService();

        final Call call = inCallService.getLastCall();
        assertCallState(call, Call.STATE_DIALING);

        assertDtmfString(connection, "");

        call.playDtmfTone('1');
        assertDtmfString(connection, "1");

        call.playDtmfTone('2');
        assertDtmfString(connection, "12");

        call.stopDtmfTone();
        assertDtmfString(connection, "12.");

        call.playDtmfTone('3');
        call.playDtmfTone('4');
        call.playDtmfTone('5');
        assertDtmfString(connection, "12.345");

        call.stopDtmfTone();
        assertDtmfString(connection, "12.345.");
    }

    public void testHoldAndUnholdCall() {
        if (!mShouldTestTelecom) {
            return;
        }

        placeAndVerifyCall();
        final MockConnection connection = verifyConnectionForOutgoingCall();

        final MockInCallService inCallService = mInCallCallbacks.getService();

        final Call call = inCallService.getLastCall();

        assertCallState(call, Call.STATE_DIALING);

        connection.setActive();

        assertCallState(call, Call.STATE_ACTIVE);

        call.hold();
        assertCallState(call, Call.STATE_HOLDING);
        assertEquals(Connection.STATE_HOLDING, connection.getState());

        call.unhold();
        assertCallState(call, Call.STATE_ACTIVE);
        assertEquals(Connection.STATE_ACTIVE, connection.getState());
    }

    public void testAnswerIncomingCallAudioOnly() {
        if (!mShouldTestTelecom) {
            return;
        }

        addAndVerifyNewIncomingCall(createTestNumber(), null);
        final MockConnection connection = verifyConnectionForIncomingCall();

        final MockInCallService inCallService = mInCallCallbacks.getService();

        final Call call = inCallService.getLastCall();

        assertCallState(call, Call.STATE_RINGING);
        assertConnectionState(connection, Connection.STATE_RINGING);

        call.answer(VideoProfile.STATE_AUDIO_ONLY);

        assertCallState(call, Call.STATE_ACTIVE);
        assertConnectionState(connection, Connection.STATE_ACTIVE);
    }

    public void testAcceptRingingCall() {
        if (!mShouldTestTelecom) {
            return;
        }

        addAndVerifyNewIncomingCall(createTestNumber(), null);
        MockConnection connection = verifyConnectionForIncomingCall(0);
        final MockInCallService inCallService = mInCallCallbacks.getService();
        final Call call = inCallService.getLastCall();

        assertCallState(call, Call.STATE_RINGING);
        assertConnectionState(connection, Connection.STATE_RINGING);

        mTelecomManager.acceptRingingCall();

        assertCallState(call, Call.STATE_ACTIVE);
        assertConnectionState(connection, Connection.STATE_ACTIVE);
    }

    /**
     * Verifies that the {@link TelecomManager#endCall()} API is able to end a ringing call.
     */
    public void testEndRingingCall() {
        if (!mShouldTestTelecom) {
            return;
        }

        addAndVerifyNewIncomingCall(createTestNumber(), null);
        MockConnection connection = verifyConnectionForIncomingCall(0);
        final MockInCallService inCallService = mInCallCallbacks.getService();
        final Call call = inCallService.getLastCall();

        assertCallState(call, Call.STATE_RINGING);
        assertConnectionState(connection, Connection.STATE_RINGING);

        mTelecomManager.endCall();

        assertCallState(call, Call.STATE_DISCONNECTED);
        assertConnectionState(connection, Connection.STATE_DISCONNECTED);
    }

    /**
     * Verifies that the {@link TelecomManager#endCall()} API is able to end an active call.
     */
    public void testEndCall() {
        if (!mShouldTestTelecom) {
            return;
        }

        addAndVerifyNewIncomingCall(createTestNumber(), null);
        MockConnection connection = verifyConnectionForIncomingCall(0);
        final MockInCallService inCallService = mInCallCallbacks.getService();
        final Call call = inCallService.getLastCall();

        assertCallState(call, Call.STATE_RINGING);
        assertConnectionState(connection, Connection.STATE_RINGING);

        mTelecomManager.acceptRingingCall();

        assertCallState(call, Call.STATE_ACTIVE);
        assertConnectionState(connection, Connection.STATE_ACTIVE);

        mTelecomManager.endCall();

        assertCallState(call, Call.STATE_DISCONNECTED);
        assertConnectionState(connection, Connection.STATE_DISCONNECTED);
    }


    /**
     * Tests that if there is the device is in a call and a second call comes in,
     * answering the call immediately answers second call without blocking.
     */
    public void testAcceptRingingCallTwoCalls() {
        if (!mShouldTestTelecom) {
            return;
        }

        addAndVerifyNewIncomingCall(createTestNumber(), null);
        MockConnection connection1 = verifyConnectionForIncomingCall(0);
        final MockInCallService inCallService = mInCallCallbacks.getService();
        final Call call1 = inCallService.getLastCall();

        call1.answer(VideoProfile.STATE_AUDIO_ONLY);

        assertCallState(call1, Call.STATE_ACTIVE);

        addAndVerifyNewIncomingCall(createTestNumber(), null);
        final MockConnection connection2 = verifyConnectionForIncomingCall(1);
        final Call call2 = inCallService.getLastCall();

        assertCallState(call2, Call.STATE_RINGING);
        assertConnectionState(connection2, Connection.STATE_RINGING);

        mTelecomManager.acceptRingingCall();

        // The second call must now be active
        assertCallState(call2, Call.STATE_ACTIVE);
        assertConnectionState(connection2, Connection.STATE_ACTIVE);
    }

    /**
     * Tests that if there is the device is in a call and a second call comes in,
     * answering the call immediately answers second call while in carMode.
     */
    public void testAcceptRingingCallTwoCallsCarMode() {
        if (!mShouldTestTelecom) {
            return;
        }

        addAndVerifyNewIncomingCall(createTestNumber(), null);
        MockConnection connection1 = verifyConnectionForIncomingCall(0);
        final MockInCallService inCallService = mInCallCallbacks.getService();
        final Call call1 = inCallService.getLastCall();

        call1.answer(VideoProfile.STATE_AUDIO_ONLY);

        assertCallState(call1, Call.STATE_ACTIVE);

        addAndVerifyNewIncomingCall(createTestNumber(), null);
        final MockConnection connection2 = verifyConnectionForIncomingCall(1);
        final Call call2 = inCallService.getLastCall();

        assertCallState(call2, Call.STATE_RINGING);
        assertConnectionState(connection2, Connection.STATE_RINGING);

        UiModeManager manager = (UiModeManager) mContext.getSystemService(Context.UI_MODE_SERVICE);
        try {
            manager.enableCarMode(0);

            mTelecomManager.acceptRingingCall();

            // The second call should now be active
            assertCallState(call2, Call.STATE_ACTIVE);
            assertConnectionState(connection2, Connection.STATE_ACTIVE);
        } finally {
            // Cleanup the call explicitly before exiting car mode -- there's a potential race
            // between disconnecting the calls from CTS and disabling car mode where if Telecom
            // sees the car mode change first, it'll try and rebind the incall services while
            // the calls are disconnecting.
            cleanupCalls();
            // Set device back to normal
            manager.disableCarMode(0);
            if (!TestUtils.hasAutomotiveFeature()) {
                // Make sure the UI mode has been set back
                assertUiMode(Configuration.UI_MODE_TYPE_NORMAL);
            } else {
                assertUiMode(Configuration.UI_MODE_TYPE_CAR);
            }
        }
    }

    public void testIncomingCallFromBlockedNumber_IsRejected() throws Exception {
        if (!mShouldTestTelecom || !TestUtils.hasTelephonyFeature(mContext)) {
            return;
        }

        Uri blockedUri = null;

        try {
            TestUtils.executeShellCommand(getInstrumentation(), "telecom stop-block-suppression");
            Uri testNumberUri = createTestNumber();
            blockedUri = blockNumber(testNumberUri);

            final Bundle extras = new Bundle();
            extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, testNumberUri);
            mTelecomManager.addNewIncomingCall(TEST_PHONE_ACCOUNT_HANDLE, extras);

            final MockConnection connection = verifyConnectionForIncomingCall();
            assertConnectionState(connection, Connection.STATE_DISCONNECTED);
            assertNull(mInCallCallbacks.getService());
        } finally {
            if (blockedUri != null) {
                unblockNumber(blockedUri);
            }
        }
    }

    public void testCallComposerAttachmentsStrippedCorrectly() throws Exception {
        if (!mShouldTestTelecom) {
            return;
        }
        Bundle extras = new Bundle();
        extras.putParcelable(TelecomManager.EXTRA_LOCATION, new Location(""));
        extras.putInt(TelecomManager.EXTRA_PRIORITY, TelecomManager.PRIORITY_URGENT);
        extras.putString(TelecomManager.EXTRA_CALL_SUBJECT, "blah blah blah");

        TestUtils.setSystemDialerOverride(getInstrumentation());
        MockCallScreeningService.enableService(mContext);
        try {
            CallScreeningService.CallResponse response =
                    new CallScreeningService.CallResponse.Builder()
                            .setDisallowCall(false)
                            .setRejectCall(false)
                            .setSilenceCall(false)
                            .setSkipCallLog(false)
                            .setSkipNotification(false)
                            .setShouldScreenCallViaAudioProcessing(false)
                            .setCallComposerAttachmentsToShow(0)
                            .build();

            MockCallScreeningService.setCallbacks(
                    new MockCallScreeningService.CallScreeningServiceCallbacks() {
                        @Override
                        public void onScreenCall(Call.Details callDetails) {
                            getService().respondToCall(callDetails, response);
                        }
                    });

            addAndVerifyNewIncomingCall(createTestNumber(), extras);
            verifyConnectionForIncomingCall(0);
            MockInCallService inCallService = mInCallCallbacks.getService();
            Call call = inCallService.getLastCall();

            assertFalse(call.getDetails().getExtras().containsKey(TelecomManager.EXTRA_LOCATION));
            assertFalse(call.getDetails().getExtras().containsKey(TelecomManager.EXTRA_PRIORITY));
            assertFalse(call.getDetails().getExtras()
                    .containsKey(TelecomManager.EXTRA_CALL_SUBJECT));

            assertFalse(call.getDetails().getIntentExtras()
                    .containsKey(TelecomManager.EXTRA_LOCATION));
            assertFalse(call.getDetails().getIntentExtras()
                    .containsKey(TelecomManager.EXTRA_PRIORITY));
            assertFalse(call.getDetails().getIntentExtras()
                    .containsKey(TelecomManager.EXTRA_CALL_SUBJECT));
        } finally {
            MockCallScreeningService.disableService(mContext);
            TestUtils.clearSystemDialerOverride(getInstrumentation());
        }
    }

    public void testSwitchCallEndpoint() {
        if (!mShouldTestTelecom) {
            return;
        }

        placeAndVerifyCall();
        final MockConnection connection = verifyConnectionForOutgoingCall();

        final MockInCallService inCallService = mInCallCallbacks.getService();

        final Call call = inCallService.getLastCall();
        assertCallState(call, Call.STATE_DIALING);

        final int currentInvokeCount = mOnCallEndpointChangedCounter.getInvokeCount();
        mOnCallEndpointChangedCounter.waitForCount(WAIT_FOR_STATE_CHANGE_TIMEOUT_MS);
        CallEndpoint currentEndpoint = (CallEndpoint) mOnCallEndpointChangedCounter.getArgs(0)[0];
        int currentEndpointType = currentEndpoint.getEndpointType();

        mOnAvailableEndpointsChangedCounter.waitForCount(WAIT_FOR_STATE_CHANGE_TIMEOUT_MS);
        List<CallEndpoint> availableEndpoints =
                (List<CallEndpoint>) mOnAvailableEndpointsChangedCounter.getArgs(0)[0];
        CallEndpoint anotherEndpoint = null;
        for (CallEndpoint endpoint : availableEndpoints) {
            if (endpoint.getEndpointType() != currentEndpointType) {
                anotherEndpoint = endpoint;
                break;
            }
        }

        Executor executor = mContext.getMainExecutor();
        if (anotherEndpoint != null) {
            final int anotherEndpointType = anotherEndpoint.getEndpointType();
            ((InCallService) inCallService).requestCallEndpointChange(anotherEndpoint, executor,
                    new OutcomeReceiver<>() {
                        @Override
                        public void onResult(Void result) {}
                        @Override
                        public void onError(CallEndpointException exception) {}
                    });
            mOnCallEndpointChangedCounter.waitForCount(currentInvokeCount + 1,
                    WAIT_FOR_STATE_CHANGE_TIMEOUT_MS);
            assertEndpointType(connection, anotherEndpointType);
            assertEndpointType(inCallService, anotherEndpointType);

            inCallService.requestCallEndpointChange(currentEndpoint, executor,
                    new OutcomeReceiver<>() {
                        @Override
                        public void onResult(Void result) {}
                        @Override
                        public void onError(CallEndpointException exception) {}
                    });
            mOnCallEndpointChangedCounter.waitForCount(currentInvokeCount + 1,
                    WAIT_FOR_STATE_CHANGE_TIMEOUT_MS);
            assertEndpointType(connection, currentEndpointType);
            assertEndpointType(inCallService, currentEndpointType);
        }

        CharSequence name = "unavailableCallEndpoint";
        ParcelUuid identifier = new ParcelUuid(UUID.randomUUID());
        CallEndpoint cep = new CallEndpoint(name, CallEndpoint.TYPE_BLUETOOTH, identifier);
        CallEndpointException expected = new CallEndpointException(
                "Requested CallEndpoint does not exist",
                CallEndpointException.ERROR_ENDPOINT_DOES_NOT_EXIST);
        inCallService.requestCallEndpointChange(cep, executor,
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(Void result) {}
                    @Override
                    public void onError(CallEndpointException exception) {
                        assertEquals(expected.getCode(), exception.getCode());
                        assertEquals(expected.getMessage(), exception.getMessage());
                    }
                });
    }

    private Uri blockNumber(Uri phoneNumberUri) {
        Uri number = insertBlockedNumber(mContext, phoneNumberUri.getSchemeSpecificPart());
        if (number == null) {
            fail("Failed to insert into blocked number provider");
        }
        return number;
    }

    private int unblockNumber(Uri uri) {
        return deleteBlockedNumber(mContext, uri);
    }

    public void testAnswerIncomingCallAsVideo_SendsCorrectVideoState() {
        if (!mShouldTestTelecom) {
            return;
        }

        addAndVerifyNewIncomingCall(createTestNumber(), null);
        final MockConnection connection = verifyConnectionForIncomingCall();

        final MockInCallService inCallService = mInCallCallbacks.getService();

        final Call call = inCallService.getLastCall();

        assertCallState(call, Call.STATE_RINGING);
        assertConnectionState(connection, Connection.STATE_RINGING);

        call.answer(VideoProfile.STATE_BIDIRECTIONAL);

        assertCallState(call, Call.STATE_ACTIVE);
        assertConnectionState(connection, Connection.STATE_ACTIVE);
        assertEquals("Connection did not receive VideoState for answered call",
                VideoProfile.STATE_BIDIRECTIONAL, connection.videoState);
    }

    public void testRejectIncomingCall() {
        if (!mShouldTestTelecom) {
            return;
        }

        addAndVerifyNewIncomingCall(createTestNumber(), null);
        final MockConnection connection = verifyConnectionForIncomingCall();

        final MockInCallService inCallService = mInCallCallbacks.getService();

        final Call call = inCallService.getLastCall();

        assertCallState(call, Call.STATE_RINGING);
        assertConnectionState(connection, Connection.STATE_RINGING);

        call.reject(false, null);

        assertCallState(call, Call.STATE_DISCONNECTED);
        assertConnectionState(connection, Connection.STATE_DISCONNECTED);
    }

    public void testRejectIncomingCallWithUnwantedReason() {
        if (!mShouldTestTelecom) {
            return;
        }

        addAndVerifyNewIncomingCall(createTestNumber(), null);
        final MockConnection connection = verifyConnectionForIncomingCall();

        final MockInCallService inCallService = mInCallCallbacks.getService();

        final Call call = inCallService.getLastCall();

        assertCallState(call, Call.STATE_RINGING);
        assertConnectionState(connection, Connection.STATE_RINGING);

        call.reject(Call.REJECT_REASON_UNWANTED);

        assertCallState(call, Call.STATE_DISCONNECTED);
        assertConnectionState(connection, Connection.STATE_DISCONNECTED);
        // The mock connection just stashes the reject reason in the disconnect cause string reason
        // for tracking purposes.
        assertEquals(Integer.toString(Call.REJECT_REASON_UNWANTED),
                connection.getDisconnectCause().getReason());
    }

    public void testRejectIncomingCallWithDeclinedReason() {
        if (!mShouldTestTelecom) {
            return;
        }

        addAndVerifyNewIncomingCall(createTestNumber(), null);
        final MockConnection connection = verifyConnectionForIncomingCall();

        final MockInCallService inCallService = mInCallCallbacks.getService();

        final Call call = inCallService.getLastCall();

        assertCallState(call, Call.STATE_RINGING);
        assertConnectionState(connection, Connection.STATE_RINGING);

        call.reject(Call.REJECT_REASON_DECLINED);

        assertCallState(call, Call.STATE_DISCONNECTED);
        assertConnectionState(connection, Connection.STATE_DISCONNECTED);
        // The mock connection just stashes the reject reason in the disconnect cause string reason
        // for tracking purposes.
        assertEquals(Integer.toString(Call.REJECT_REASON_DECLINED),
                connection.getDisconnectCause().getReason());
    }

    public void testRejectIncomingCallWithMessage() {
        if (!mShouldTestTelecom) {
            return;
        }
        String disconnectReason = "Test reason for disconnect";

        addAndVerifyNewIncomingCall(createTestNumber(), null);
        final MockConnection connection = verifyConnectionForIncomingCall();

        final MockInCallService inCallService = mInCallCallbacks.getService();

        final Call call = inCallService.getLastCall();

        assertCallState(call, Call.STATE_RINGING);
        assertConnectionState(connection, Connection.STATE_RINGING);

        call.reject(true, disconnectReason);

        assertCallState(call, Call.STATE_DISCONNECTED);
        assertConnectionState(connection, Connection.STATE_DISCONNECTED);
        assertDisconnectReason(connection, disconnectReason);
    }

    public void testCanAddCall_CannotAddForExistingDialingCall() {
        if (!mShouldTestTelecom) {
            return;
        }

        placeAndVerifyCall();
        verifyConnectionForOutgoingCall();

        final MockInCallService inCallService = mInCallCallbacks.getService();

        final Call call = inCallService.getLastCall();
        assertCallState(call, Call.STATE_DIALING);

        assertCanAddCall(inCallService, false,
                "Should not be able to add call with existing dialing call");
    }

    public void testCanAddCall_CanAddForExistingActiveCall() {
        if (!mShouldTestTelecom  || !TestUtils.hasTelephonyFeature(mContext)) {
            return;
        }

        placeAndVerifyCall();
        final MockConnection connection = verifyConnectionForOutgoingCall();

        final MockInCallService inCallService = mInCallCallbacks.getService();

        final Call call = inCallService.getLastCall();
        assertCallState(call, Call.STATE_DIALING);

        connection.setActive();

        assertCallState(call, Call.STATE_ACTIVE);

        assertCanAddCall(inCallService, true,
                "Should be able to add call with only one active call");
    }

    public void testCanAddCall_CanAddForExistingActiveCallWithoutHoldCapability() {
        if (!mShouldTestTelecom  || !TestUtils.hasTelephonyFeature(mContext)) {
            return;
        }

        placeAndVerifyCall();
        final MockConnection connection = verifyConnectionForOutgoingCall();
        final int capabilities = connection.getConnectionCapabilities();
        connection.setConnectionCapabilities(capabilities & ~Connection.CAPABILITY_SUPPORT_HOLD);
        connection.setConnectionCapabilities(capabilities & ~Connection.CAPABILITY_HOLD);

        final MockInCallService inCallService = mInCallCallbacks.getService();

        final Call call = inCallService.getLastCall();
        assertCallState(call, Call.STATE_DIALING);
        connection.setActive();
        assertCallState(call, Call.STATE_ACTIVE);

        assertCanAddCall(inCallService, true,
                "Should be able to add call with only one active call without hold capability");
    }

    public void testCanAddCall_CannotAddIfTooManyCalls() {
        if (!mShouldTestTelecom) {
            return;
        }

        placeAndVerifyCall();
        final MockConnection connection1 = verifyConnectionForOutgoingCall(0);
        final MockInCallService inCallService = mInCallCallbacks.getService();
        final Call call1 = inCallService.getLastCall();
        assertCallState(call1, Call.STATE_DIALING);

        connection1.setActive();

        assertCallState(call1, Call.STATE_ACTIVE);

        placeAndVerifyCall();
        final MockConnection connection2 = verifyConnectionForOutgoingCall(1);

        final Call call2 = inCallService.getLastCall();
        assertCallState(call2, Call.STATE_DIALING);
        connection2.setActive();
        assertCallState(call2, Call.STATE_ACTIVE);

        assertEquals("InCallService should have 2 calls", 2, inCallService.getCallCount());

        assertCanAddCall(inCallService, false,
                "Should not be able to add call with two calls already present");

        call1.hold();
        assertCallState(call1, Call.STATE_HOLDING);

        assertCanAddCall(inCallService, false,
                "Should not be able to add call with two calls already present");
    }

    public void testOnBringToForeground() {
        if (!mShouldTestTelecom) {
            return;
        }

        placeAndVerifyCall();
        verifyConnectionForOutgoingCall();

        final MockInCallService inCallService = mInCallCallbacks.getService();

        final Call call = inCallService.getLastCall();

        assertCallState(call, Call.STATE_DIALING);

        assertEquals(0, mOnBringToForegroundCounter.getInvokeCount());

        final TelecomManager tm =
            (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);

        tm.showInCallScreen(false);

        mOnBringToForegroundCounter.waitForCount(1, WAIT_FOR_STATE_CHANGE_TIMEOUT_MS);

        assertFalse((Boolean) mOnBringToForegroundCounter.getArgs(0)[0]);

        tm.showInCallScreen(true);

        mOnBringToForegroundCounter.waitForCount(2, WAIT_FOR_STATE_CHANGE_TIMEOUT_MS);

        assertTrue((Boolean) mOnBringToForegroundCounter.getArgs(1)[0]);
    }

    public void testSilenceRinger() {
        if (!mShouldTestTelecom) {
            return;
        }
        addAndVerifyNewIncomingCall(createTestNumber(), null);
        final MockConnection connection = verifyConnectionForIncomingCall();
        final InvokeCounter counter = connection.getInvokeCounter(MockConnection.ON_SILENCE);
        final MockInCallService inCallService = mInCallCallbacks.getService();

        final TelecomManager telecomManager =
            (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
        telecomManager.silenceRinger();

        // Both the InCallService and Connection will be notified of a request to silence:
        mOnSilenceRingerCounter.waitForCount(1);
        counter.waitForCount(1);
    }

    public void testOnPostDialWaitAndContinue() {
        if (!mShouldTestTelecom) {
            return;
        }

        placeAndVerifyCall();
        final MockConnection connection = verifyConnectionForOutgoingCall();
        final MockInCallService inCallService = mInCallCallbacks.getService();
        final Call call = inCallService.getLastCall();
        assertCallState(call, Call.STATE_DIALING);

        connection.setActive();
        assertCallState(call, Call.STATE_ACTIVE);

        final String postDialString = "12345";
        ((Connection) connection).setPostDialWait(postDialString);
        mOnPostDialWaitCounter.waitForCount(1, WAIT_FOR_STATE_CHANGE_TIMEOUT_MS);

        assertEquals(postDialString, mOnPostDialWaitCounter.getArgs(0)[1]);
        assertEquals(postDialString, call.getRemainingPostDialSequence());

        final InvokeCounter counter = connection.getInvokeCounter(MockConnection.ON_POST_DIAL_WAIT);

        call.postDialContinue(true);
        counter.waitForCount(1);
        assertTrue((Boolean) counter.getArgs(0)[0]);

        call.postDialContinue(false);
        counter.waitForCount(2);
        assertFalse((Boolean) counter.getArgs(1)[0]);
    }

    public void testOnCannedTextResponsesLoaded() {
        if (!mShouldTestTelecom) {
            return;
        }

        TelephonyManager tm = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null && !tm.isSmsCapable()) {
            return ;
        }

        addAndVerifyNewIncomingCall(createTestNumber(), null);
        verifyConnectionForIncomingCall();
        final MockInCallService inCallService = mInCallCallbacks.getService();

        final Call call = inCallService.getLastCall();

        assertCallState(call, Call.STATE_RINGING);

        // We can't do much to enforce the number and type of responses that are preloaded on
        // device, so the best we can do is to make sure that the call back is called and
        // that the returned list is non-empty.

        // This test should also verify that the callback is called as well, but unfortunately it
        // is never called right now (b/22952515).
        // mOnCannedTextResponsesLoadedCounter.waitForCount(1);

        assertGetCannedTextResponsesNotEmpty(call);
    }

    public void testGetCalls() {
        if (!mShouldTestTelecom) {
            return;
        }

        placeAndVerifyCall();
        final MockConnection connection1 = verifyConnectionForOutgoingCall(0);
        final MockInCallService inCallService = mInCallCallbacks.getService();
        final Call call1 = inCallService.getLastCall();
        assertCallState(call1, Call.STATE_DIALING);

        connection1.setActive();

        assertCallState(call1, Call.STATE_ACTIVE);

        List<Call> calls = inCallService.getCalls();
        assertEquals("InCallService.getCalls() should return list with 1 call.", 1, calls.size());
        assertEquals(call1, calls.get(0));

        addAndVerifyNewIncomingCall(createTestNumber(), null);
        verifyConnectionForIncomingCall();

        final Call call2 = inCallService.getLastCall();
        calls = inCallService.getCalls();
        assertEquals("InCallService.getCalls() should return list with 2 calls.", 2, calls.size());
        assertEquals(call1, calls.get(0));
        assertEquals(call2, calls.get(1));
    }

    private void assertGetCannedTextResponsesNotEmpty(final Call call) {
        waitUntilConditionIsTrueOrTimeout(
                new Condition() {
                    @Override
                    public Object expected() {
                        return true;
                    }

                    @Override
                    public Object actual() {
                        return call.getCannedTextResponses() != null
                                && !call.getCannedTextResponses().isEmpty();
                    }

                },
                WAIT_FOR_STATE_CHANGE_TIMEOUT_MS,
                "Call.getCannedTextResponses should not be empty");
    }

    private void assertCanAddCall(final InCallService inCallService, final boolean canAddCall,
            String message) {
        waitUntilConditionIsTrueOrTimeout(
                new Condition() {
                    @Override
                    public Object expected() {
                        return canAddCall;
                    }

                    @Override
                    public Object actual() {
                        return inCallService.canAddCall();
                    }
                },
                WAIT_FOR_STATE_CHANGE_TIMEOUT_MS,
                message
        );
    }
}
