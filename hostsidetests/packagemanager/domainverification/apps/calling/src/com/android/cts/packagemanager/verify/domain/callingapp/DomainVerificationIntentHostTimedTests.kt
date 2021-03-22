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

package com.android.cts.packagemanager.verify.domain.callingapp

import com.android.cts.packagemanager.verify.domain.android.DomainUtils.DECLARING_PKG_1_COMPONENT
import com.android.cts.packagemanager.verify.domain.android.DomainUtils.DECLARING_PKG_2_COMPONENT
import com.android.cts.packagemanager.verify.domain.android.DomainVerificationIntentTestBase
import com.android.cts.packagemanager.verify.domain.java.DomainUtils
import com.android.cts.packagemanager.verify.domain.java.DomainUtils.DECLARING_PKG_NAME_2
import com.android.cts.packagemanager.verify.domain.java.DomainUtils.DOMAIN_1
import com.android.cts.packagemanager.verify.domain.java.DomainUtils.DOMAIN_2
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DomainVerificationIntentHostTimedTests : DomainVerificationIntentTestBase() {

    @Test
    fun multipleVerifiedTakeLastFirstInstall() {
        setAppLinks(DECLARING_PKG_NAME_2, true, DOMAIN_1, DOMAIN_2)

        assertResolvesTo(DECLARING_PKG_2_COMPONENT)

        setAppLinks(DomainUtils.DECLARING_PKG_NAME_1, true, DOMAIN_1, DOMAIN_2)

        assertResolvesTo(DECLARING_PKG_1_COMPONENT)

        // Re-approve 2 and ensure this doesn't affect anything
        setAppLinks(DECLARING_PKG_NAME_2, true, DOMAIN_1, DOMAIN_2)

        assertResolvesTo(DECLARING_PKG_1_COMPONENT)
    }
}
