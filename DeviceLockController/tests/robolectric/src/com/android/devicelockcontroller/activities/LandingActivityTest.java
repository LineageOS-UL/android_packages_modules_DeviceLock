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

package com.android.devicelockcontroller.activities;

import static com.google.common.truth.Truth.assertThat;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;

import com.android.devicelockcontroller.R;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class LandingActivityTest {

    @Ignore("http://b/269463682")
    @Test
    public void landingActivity_setsProvisionInfoFragment() {
        LandingActivity activity = Robolectric.buildActivity(LandingActivity.class).setup().get();
        FragmentContainerView fragmentContainerView = activity.findViewById(
                R.id.provision_info_fragment);

        assertThat((Fragment) fragmentContainerView.getFragment()).isInstanceOf(
                ProvisionInfoFragment.class);
    }
}
