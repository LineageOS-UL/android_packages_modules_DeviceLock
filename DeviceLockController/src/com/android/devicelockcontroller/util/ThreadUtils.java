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

package com.android.devicelockcontroller.util;

import com.google.common.util.concurrent.MoreExecutors;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** An utility class related to multi-threading */
public final class ThreadUtils {
    private static volatile Executor sSequentialSchedulerExecutor;

    // Prevent instantiation
    private ThreadUtils() {
    }

    /**
     * Get the sequential executor for DeviceLockControllerScheduler.
     */
    public static synchronized Executor getSequentialSchedulerExecutor() {
        if (sSequentialSchedulerExecutor == null) {
            sSequentialSchedulerExecutor = MoreExecutors.newSequentialExecutor(
                    Executors.newSingleThreadExecutor());
        }
        return sSequentialSchedulerExecutor;
    }
}
