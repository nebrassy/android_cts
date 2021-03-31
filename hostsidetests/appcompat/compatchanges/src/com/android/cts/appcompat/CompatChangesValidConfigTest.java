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

package com.android.cts.appcompat;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.compat.cts.Change;
import android.compat.cts.CompatChangeGatingTestCase;

import com.google.common.collect.ImmutableSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class CompatChangesValidConfigTest extends CompatChangeGatingTestCase {

    private static final Set<String> OVERRIDES_ALLOWLIST = ImmutableSet.of(
        // This change id will sometimes remain enabled if an instrumentation test fails.
        "ALLOW_TEST_API_ACCESS"
    );

    /**
     * Check that there are no overrides.
     */
    public void testNoOverrides() throws Exception {
        for (Change c : getOnDeviceCompatConfig()) {
            if (!OVERRIDES_ALLOWLIST.contains(c.changeName) && !c.overridable) {
                assertWithMessage("Change should not have overrides: " + c)
                        .that(c.hasOverrides).isFalse();
            }
        }
    }

    /**
     * Check that the on device config contains all the expected change ids defined in the platform.
     * The device may contain extra changes, but none may be removed.
     */
    public void testDeviceContainsExpectedConfig() throws Exception {
        assertThat(getOnDeviceCompatConfig()).containsAtLeastElementsIn(getExpectedCompatConfig());
    }


    /**
     * Parse the expected (i.e. defined in platform) config xml.
     */
    private List<Change> getExpectedCompatConfig() throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document dom = db.parse(getClass().getResourceAsStream("/cts_all_compat_config.xml"));
        Element root = dom.getDocumentElement();
        NodeList changeNodes = root.getElementsByTagName("compat-change");
        List<Change> changes = new ArrayList<>();
        for (int nodeIdx = 0; nodeIdx < changeNodes.getLength(); ++nodeIdx) {
            Change change = Change.fromNode(changeNodes.item(nodeIdx));
            // Exclude logging only changes from the expected config. See b/155264388.
            if (!change.loggingOnly) {
                changes.add(change);
            }
        }
        return changes;
    }

}
