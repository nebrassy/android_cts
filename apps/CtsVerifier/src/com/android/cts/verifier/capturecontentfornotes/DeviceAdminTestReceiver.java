/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.cts.verifier.capturecontentfornotes;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;

/**
 * This is used as a test device admin for CaptureContentForNotesVerifierActivity.
 */
public class DeviceAdminTestReceiver extends DeviceAdminReceiver {

    static final String DEVICE_OWNER_PKG =
            "com.android.cts.verifier";
    private static final String ADMIN_RECEIVER_TEST_CLASS =
            DEVICE_OWNER_PKG + ".capturecontentfornotes.DeviceAdminTestReceiver";
    static final ComponentName RECEIVER_COMPONENT_NAME = new ComponentName(
            DEVICE_OWNER_PKG, ADMIN_RECEIVER_TEST_CLASS);
}
