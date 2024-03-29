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

package com.android.server.devicelock;

import static android.provider.Settings.Secure.USER_SETUP_COMPLETE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.SystemService;

import java.util.Objects;

/**
 * Service implementing DeviceLock functionality. Delegates actual interface
 * implementation to DeviceLockServiceImpl.
 */
public final class DeviceLockService extends SystemService {

    private static final String TAG = "DeviceLockService";

    private final DeviceLockServiceImpl mImpl;

    public DeviceLockService(Context context) {
        super(context);
        Slog.d(TAG, "DeviceLockService constructor");
        mImpl = new DeviceLockServiceImpl(context);
    }

    @Override
    public void onStart() {
        Slog.i(TAG, "Registering " + Context.DEVICE_LOCK_SERVICE);
        publishBinderService(Context.DEVICE_LOCK_SERVICE, mImpl);
    }

    @Override
    public void onBootPhase(int phase) {
        Slog.d(TAG, "onBootPhase: " + phase);
    }

    @NonNull
    private static Context getUserContext(@NonNull Context context, @NonNull UserHandle user) {
        if (Process.myUserHandle().equals(user)) {
            return context;
        } else {
            return context.createContextAsUser(user, 0 /* flags */);
        }
    }

    @Override
    public boolean isUserSupported(@NonNull TargetUser user) {
        final UserManager userManager =
                getUserContext(getContext(),
                        user.getUserHandle()).getSystemService(UserManager.class);
        return !userManager.isProfile();
    }

    @Override
    public void onUserSwitching(@NonNull TargetUser from, @NonNull TargetUser to) {
        Objects.requireNonNull(to);
        Slog.d(TAG, "onUserSwitching from: " + from + " to: " + to);
        final UserHandle userHandle = to.getUserHandle();
        mImpl.enforceDeviceLockControllerPackageEnabledState(userHandle);
        mImpl.onUserSwitching(userHandle);
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        Slog.d(TAG, "onUserUnlocking: " + user);
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        Slog.d(TAG, "onUserUnlocked: " + user);
        final UserHandle userHandle = user.getUserHandle();
        mImpl.onUserUnlocked(userHandle);
        // TODO(b/312521897): Add unit tests for this flow
        registerUserSetupCompleteListener(userHandle);
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        Slog.d(TAG, "onUserStopping: " + user);
    }

    private void registerUserSetupCompleteListener(UserHandle userHandle) {
        final ContentResolver contentResolver = getUserContext(getContext(), userHandle)
                .getContentResolver();
        Uri setupCompleteUri = Settings.Secure.getUriFor(USER_SETUP_COMPLETE);
        contentResolver.registerContentObserver(setupCompleteUri,
                false /* notifyForDescendants */, new ContentObserver(null /* handler */) {
                    @Override
                    public void onChange(boolean selfChange, @Nullable Uri uri) {
                        if (setupCompleteUri.equals(uri)
                                && Settings.Secure.getInt(
                                        contentResolver, USER_SETUP_COMPLETE, 0) != 0) {
                            mImpl.onUserSetupCompleted(userHandle);
                        }
                    }
                });
    }
}
