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

package com.android.devicelockcontroller.provision.worker;

import static com.android.devicelockcontroller.common.DeviceLockConstants.DeviceIdType.DEVICE_ID_TYPE_IMEI;
import static com.android.devicelockcontroller.common.DeviceLockConstants.DeviceIdType.DEVICE_ID_TYPE_MEID;
import static com.android.devicelockcontroller.common.DeviceLockConstants.EXTRA_MANDATORY_PROVISION;
import static com.android.devicelockcontroller.common.DeviceLockConstants.EXTRA_PROVISIONING_TYPE;
import static com.android.devicelockcontroller.common.DeviceLockConstants.READY_FOR_PROVISION;
import static com.android.devicelockcontroller.common.DeviceLockConstants.RETRY_CHECK_IN;
import static com.android.devicelockcontroller.common.DeviceLockConstants.STATUS_UNSPECIFIED;
import static com.android.devicelockcontroller.common.DeviceLockConstants.STOP_CHECK_IN;
import static com.android.devicelockcontroller.common.DeviceLockConstants.TOTAL_DEVICE_ID_TYPES;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceEvent.PROVISION_READY;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.util.ArraySet;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.android.devicelockcontroller.AbstractDeviceLockControllerScheduler;
import com.android.devicelockcontroller.R;
import com.android.devicelockcontroller.common.DeviceId;
import com.android.devicelockcontroller.policy.DeviceStateController;
import com.android.devicelockcontroller.policy.DeviceStateController.DeviceEvent;
import com.android.devicelockcontroller.policy.PolicyObjectsInterface;
import com.android.devicelockcontroller.provision.grpc.GetDeviceCheckInStatusGrpcResponse;
import com.android.devicelockcontroller.provision.grpc.ProvisioningConfiguration;
import com.android.devicelockcontroller.storage.GlobalParametersClient;
import com.android.devicelockcontroller.storage.SetupParametersClient;
import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.time.DateTimeException;
import java.time.Duration;
import java.util.Locale;

/**
 * Helper class to perform the device check-in process with device lock backend server
 */
public final class DeviceCheckInHelper extends AbstractDeviceCheckInHelper {
    private static final String TAG = "DeviceCheckInHelper";
    private final Context mAppContext;
    private final TelephonyManager mTelephonyManager;

    public DeviceCheckInHelper(Context appContext) {
        mAppContext = appContext;
        mTelephonyManager = mAppContext.getSystemService(TelephonyManager.class);
    }

    @Override
    ArraySet<DeviceId> getDeviceUniqueIds() {
        final int deviceIdTypeBitmap = mAppContext.getResources().getInteger(
                R.integer.device_id_type_bitmap);
        if (deviceIdTypeBitmap < 0) {
            LogUtil.e(TAG, "getDeviceId: Cannot get device_id_type_bitmap");
            return new ArraySet<>();
        }

        return getDeviceAvailableUniqueIds(deviceIdTypeBitmap);
    }

    @VisibleForTesting
    ArraySet<DeviceId> getDeviceAvailableUniqueIds(int deviceIdTypeBitmap) {

        final int totalSlotCount = mTelephonyManager.getActiveModemCount();
        final int maximumIdCount = TOTAL_DEVICE_ID_TYPES * totalSlotCount;
        final ArraySet<DeviceId> deviceIds = new ArraySet<>(maximumIdCount);
        if (maximumIdCount == 0) return deviceIds;

        for (int i = 0; i < totalSlotCount; i++) {
            if ((deviceIdTypeBitmap & (1 << DEVICE_ID_TYPE_IMEI)) != 0) {
                final String imei = mTelephonyManager.getImei(i);

                if (imei != null) {
                    deviceIds.add(new DeviceId(DEVICE_ID_TYPE_IMEI, imei));
                }
            }

            if ((deviceIdTypeBitmap & (1 << DEVICE_ID_TYPE_MEID)) != 0) {
                final String meid = mTelephonyManager.getMeid(i);

                if (meid != null) {
                    deviceIds.add(new DeviceId(DEVICE_ID_TYPE_MEID, meid));
                }
            }
        }

        return deviceIds;
    }

    @Override
    String getCarrierInfo() {
        return mTelephonyManager.getSimOperator();
    }

    @Override
    @WorkerThread
    boolean handleGetDeviceCheckInStatusResponse(
            GetDeviceCheckInStatusGrpcResponse response,
            AbstractDeviceLockControllerScheduler scheduler) {
        Futures.getUnchecked(GlobalParametersClient.getInstance().setRegisteredDeviceId(
                response.getRegisteredDeviceIdentifier()));
        LogUtil.d(TAG, "check in response: " + response.getDeviceCheckInStatus());
        switch (response.getDeviceCheckInStatus()) {
            case READY_FOR_PROVISION:
                PolicyObjectsInterface policies =
                        (PolicyObjectsInterface) mAppContext.getApplicationContext();
                return handleProvisionReadyResponse(
                        response,
                        policies.getStateController());
            case RETRY_CHECK_IN:
                try {
                    Duration delay = Duration.between(
                            SystemClock.currentNetworkTimeClock().instant(),
                            response.getNextCheckInTime());
                    // Retry immediately if next check in time is in the past.
                    delay = delay.isNegative() ? Duration.ZERO : delay;
                    scheduler.scheduleRetryCheckInWork(delay);
                    return true;
                } catch (DateTimeException e) {
                    LogUtil.e(TAG, "No network time is available!");
                    return false;
                }
            case STOP_CHECK_IN:
                Futures.getUnchecked(GlobalParametersClient.getInstance().setNeedCheckIn(false));
                return true;
            case STATUS_UNSPECIFIED:
            default:
                return false;
        }
    }

    @VisibleForTesting
    @WorkerThread
    boolean handleProvisionReadyResponse(
            @NonNull GetDeviceCheckInStatusGrpcResponse response,
            DeviceStateController stateController) {
        Futures.getUnchecked(GlobalParametersClient.getInstance().setProvisionForced(
                response.isProvisionForced()));
        final ProvisioningConfiguration configuration = response.getProvisioningConfig();
        if (configuration == null) {
            LogUtil.e(TAG, "Provisioning Configuration is not provided by server!");
            return false;
        }
        final Bundle provisionBundle = configuration.toBundle();
        provisionBundle.putInt(EXTRA_PROVISIONING_TYPE, response.getProvisioningType());
        provisionBundle.putBoolean(EXTRA_MANDATORY_PROVISION,
                response.isProvisioningMandatory());
        Futures.getUnchecked(
                SetupParametersClient.getInstance().createPrefs(provisionBundle));
        setProvisionReady(stateController, mAppContext);
        return true;
    }

    /**
     * Helper method to set the state for {@link DeviceEvent#PROVISION_READY} event.
     */
    public static void setProvisionReady(DeviceStateController stateController,
            Context mAppContext) {
        FutureCallback<Void> futureCallback = new FutureCallback<>() {
            @Override
            public void onSuccess(Void result) {
                LogUtil.i(TAG,
                        String.format(Locale.US,
                                "State transition succeeded for event: %s",
                                DeviceStateController.eventToString(PROVISION_READY)));
            }

            @Override
            public void onFailure(Throwable t) {
                //TODO: Reset the state to where it can successfully transition.
                LogUtil.e(TAG,
                        String.format(Locale.US,
                                "State transition failed for event: %s",
                                DeviceStateController.eventToString(PROVISION_READY)), t);
            }
        };
        mAppContext.getMainExecutor().execute(
                () -> {
                    ListenableFuture<Void> tasks = Futures.whenAllSucceed(
                                    GlobalParametersClient.getInstance().setNeedCheckIn(false),
                                    stateController.setNextStateForEvent(PROVISION_READY))
                            .call(() -> null, MoreExecutors.directExecutor());
                    Futures.addCallback(tasks, futureCallback, MoreExecutors.directExecutor());
                });
    }
}
