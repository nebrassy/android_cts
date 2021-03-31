/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.permission.cts;

import static android.Manifest.permission.NFC_SET_CONTROLLER_ALWAYS_ON;

import static org.junit.Assert.fail;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import androidx.test.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RunWith(JUnit4.class)
public final class NfcPermissionTest {

    @Test
    public void testNfcSetControllerAlwaysOnPermission() {
        PackageManager pm = InstrumentationRegistry.getContext().getPackageManager();

        List<Integer> specialUids = Arrays.asList(Process.SYSTEM_UID, Process.NFC_UID);

        List<PackageInfo> holding = pm.getPackagesHoldingPermissions(
                new String[] { NFC_SET_CONTROLLER_ALWAYS_ON },
                PackageManager.MATCH_DISABLED_COMPONENTS);

        List<Integer> nonSpecialPackages = holding.stream()
                .map(pi -> {
                    try {
                        return pm.getPackageUid(pi.packageName, 0);
                    } catch (PackageManager.NameNotFoundException e) {
                        return Process.INVALID_UID;
                    }
                })
                .filter(uid -> !specialUids.contains(uid))
                .collect(Collectors.toList());

        if (holding.size() > 1) {
            fail("Only one app on the device is allowed to hold the "
                     + "NFC_SET_CONTROLLER_ALWAYS_ON permission.");
        }
    }
}
