/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.devicelockcontroller.shadows;

import android.annotation.NonNull;
import android.app.ApplicationPackageManager;
import android.content.pm.PackageManager.ComponentEnabledSetting;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.List;

/**
 * A subclass of ShadowApplicationPackageManager that includes an extra method
 * (setComponentEnabledSettings) not implemented upstream. Used for testing DeviceLockController.
 */
@Implements(value = ApplicationPackageManager.class)
public class ShadowApplicationPackageManager extends
        org.robolectric.shadows.ShadowApplicationPackageManager {

    /**
     * Shadow for ApplicationPackageManager.setComponentEnabledSettings().
     * Note that this implementation is not atomic like the framework API, but it is not relevant
     * to our tests.
     *
     * @param settings List of ComponentEnabledSetting (one for each component to enable/disable).
     */
    @Implementation
    protected void setComponentEnabledSettings(@NonNull List<ComponentEnabledSetting> settings) {
        for (ComponentEnabledSetting setting: settings) {
            setComponentEnabledSetting(setting.getComponentName(), setting.getEnabledState(),
                    setting.getEnabledFlags());
        }
    }
}
