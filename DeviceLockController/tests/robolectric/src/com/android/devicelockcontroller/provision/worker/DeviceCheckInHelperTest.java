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

import static com.android.devicelockcontroller.common.DeviceLockConstants.DEVICE_ID_TYPE_IMEI;
import static com.android.devicelockcontroller.common.DeviceLockConstants.DEVICE_ID_TYPE_MEID;
import static com.android.devicelockcontroller.common.DeviceLockConstants.READY_FOR_PROVISION;
import static com.android.devicelockcontroller.common.DeviceLockConstants.RETRY_CHECK_IN;
import static com.android.devicelockcontroller.common.DeviceLockConstants.STOP_CHECK_IN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.annotation.LooperMode.Mode.LEGACY;

import android.content.ComponentName;
import android.telephony.TelephonyManager;
import android.util.ArraySet;

import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.Configuration;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.WorkManagerTestInitHelper;

import com.android.devicelockcontroller.TestDeviceLockControllerApplication;
import com.android.devicelockcontroller.common.DeviceId;
import com.android.devicelockcontroller.common.DeviceLockConstants.DeviceCheckInStatus;
import com.android.devicelockcontroller.policy.DeviceStateController;
import com.android.devicelockcontroller.policy.DeviceStateController.DeviceEvent;
import com.android.devicelockcontroller.policy.StateTransitionException;
import com.android.devicelockcontroller.provision.grpc.GetDeviceCheckInStatusGrpcResponse;
import com.android.devicelockcontroller.provision.grpc.ProvisioningConfiguration;
import com.android.devicelockcontroller.setup.SetupParametersClient;
import com.android.devicelockcontroller.setup.SetupParametersService;
import com.android.devicelockcontroller.setup.UserPreferences;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.testing.TestingExecutors;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowTelephonyManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@LooperMode(LEGACY)
@RunWith(RobolectricTestRunner.class)
public final class DeviceCheckInHelperTest {
    static final Duration TEST_CHECK_RETRY_DURATION = Duration.ofDays(30);
    private TestDeviceLockControllerApplication mTestApplication;
    static final int TOTAL_SLOT_COUNT = 2;
    static final int TOTAL_ID_COUNT = 4;
    static final String IMEI_1 = "IMEI1";
    static final String IMEI_2 = "IMEI2";
    static final String MEID_1 = "MEID1";
    static final String MEID_2 = "MEID2";
    static final ArraySet<DeviceId> ACTUAL_DEVICE_IDs =
            new ArraySet<>(new DeviceId[]{
                    new DeviceId(DEVICE_ID_TYPE_IMEI, IMEI_1),
                    new DeviceId(DEVICE_ID_TYPE_IMEI, IMEI_2),
                    new DeviceId(DEVICE_ID_TYPE_MEID, MEID_1),
                    new DeviceId(DEVICE_ID_TYPE_MEID, MEID_2),
            });
    static final ProvisioningConfiguration TEST_CONFIGURATION = new ProvisioningConfiguration(
            /* kioskAppDownloadUrl= */ "test_url",
            /* kioskAppProviderName= */ "test_provider",
            /* kioskAppPackageName= */ "test_package",
            /* kioskAppSignatureChecksum= */ "test_checksum",
            /* kioskAppMainActivity= */ "test_activity",
            /* kioskAppAllowlistPackages= */ List.of("test_allowed_app1", "test_allowed_app2"),
            /* kioskAppEnableOutgoingCalls= */ false,
            /* kioskAppEnableEnableNotifications= */ true,
            /* disallowInstallingFromUnknownSources= */ false
    );
    static final int DEVICE_ID_TYPE_BITMAP =
            (1 << DEVICE_ID_TYPE_IMEI) | (1 << DEVICE_ID_TYPE_MEID);
    private DeviceCheckInHelper mHelper;

    private ShadowTelephonyManager mTelephonyManager;
    private SetupParametersClient mSetupParametersClient;

    @Before
    public void setUp() {
        mTestApplication = ApplicationProvider.getApplicationContext();

        mTelephonyManager = Shadows.shadowOf(
                mTestApplication.getSystemService(TelephonyManager.class));
        mHelper = new DeviceCheckInHelper(mTestApplication);
        WorkManagerTestInitHelper.initializeTestWorkManager(mTestApplication,
                new Configuration.Builder()
                        .setMinimumLoggingLevel(android.util.Log.DEBUG)
                        .setExecutor(new SynchronousExecutor())
                        .build());
        Shadows.shadowOf(mTestApplication).setComponentNameAndServiceForBindService(
                new ComponentName(mTestApplication, SetupParametersService.class),
                Robolectric.setupService(SetupParametersService.class).onBind(null));
        mSetupParametersClient = SetupParametersClient.getInstance(
                mTestApplication, TestingExecutors.sameThreadScheduledExecutor());
    }

    @Test
    public void getDeviceAvailableUniqueIds_shouldReturnAllAvailableUniqueIds() {
        mTelephonyManager.setActiveModemCount(TOTAL_SLOT_COUNT);
        mTelephonyManager.setImei(/* slotIndex= */ 0, IMEI_1);
        mTelephonyManager.setImei(/* slotIndex= */ 1, IMEI_2);
        mTelephonyManager.setMeid(/* slotIndex= */ 0, MEID_1);
        mTelephonyManager.setMeid(/* slotIndex= */ 1, MEID_2);
        final ArraySet<DeviceId> deviceIds = mHelper.getDeviceAvailableUniqueIds(
                DEVICE_ID_TYPE_BITMAP);
        assertThat(Objects.requireNonNull(deviceIds).size()).isEqualTo(TOTAL_ID_COUNT);
        assertThat(deviceIds).containsExactlyElementsIn(ACTUAL_DEVICE_IDs);
    }

    @Test
    public void testHandleGetDeviceCheckInStatusResponse_stopCheckIn_shouldSetNeedCheckInFalse() {
        final GetDeviceCheckInStatusGrpcResponse response = createStopResponse();

        assertThat(mHelper.handleGetDeviceCheckInStatusResponse(response)).isTrue();

        assertThat(UserPreferences.needCheckIn(mTestApplication)).isFalse();
    }

    @Test
    public void testHandleProvisionReadyResponse_validConfiguration_shouldSetState()
            throws StateTransitionException {
        GetDeviceCheckInStatusGrpcResponse response = createReadyResponse(TEST_CONFIGURATION);
        DeviceStateController stateController = mTestApplication.getStateController();
        when(stateController.setNextStateForEvent(DeviceEvent.PROVISIONING_SUCCESS)).thenReturn(
                Futures.immediateVoidFuture());

        assertThat(mHelper.handleProvisionReadyResponse(response, stateController)).isTrue();

        verify(stateController).setNextStateForEvent(eq(DeviceEvent.PROVISIONING_SUCCESS));
        assertThat(UserPreferences.needCheckIn(mTestApplication)).isFalse();
    }

    @Test
    public void testHandleProvisionReadyResponse_invalidConfiguration_shouldNotSetState()
            throws StateTransitionException {
        GetDeviceCheckInStatusGrpcResponse response = createReadyResponse(
                /* configuration= */ null);
        DeviceStateController stateController = mTestApplication.getStateController();

        assertThat(mHelper.handleProvisionReadyResponse(response, stateController)).isFalse();

        verify(stateController, never()).setNextStateForEvent(eq(DeviceEvent.PROVISIONING_SUCCESS));
    }

    @Test
    public void testHandleGetDeviceCheckInStatusResponse_retryCheckIn_shouldEnqueueNewCheckInWork()
            throws ExecutionException, InterruptedException, TimeoutException {
        final GetDeviceCheckInStatusGrpcResponse response = createRetryResponse(
                Instant.now().plus(TEST_CHECK_RETRY_DURATION));

        assertThat(mHelper.handleGetDeviceCheckInStatusResponse(response)).isTrue();

        WorkManager workManager = WorkManager.getInstance(mTestApplication);

        List<WorkInfo> workInfo = workManager.getWorkInfosForUniqueWork(
                DeviceCheckInHelper.CHECK_IN_WORK_NAME).get(500, TimeUnit.MILLISECONDS);
        assertThat(workInfo.size()).isEqualTo(1);
    }

    private static GetDeviceCheckInStatusGrpcResponse createStopResponse() {
        return createMockResponse(STOP_CHECK_IN, /* nextCheckInDate= */ null, /* config= */ null);
    }


    private static GetDeviceCheckInStatusGrpcResponse createRetryResponse(Instant nextCheckInTime) {
        return createMockResponse(RETRY_CHECK_IN, nextCheckInTime, /* config= */ null);
    }

    private static GetDeviceCheckInStatusGrpcResponse createReadyResponse(
            ProvisioningConfiguration configuration) {
        return createMockResponse(READY_FOR_PROVISION, /* nextCheckInTime= */ null, configuration);
    }

    private static GetDeviceCheckInStatusGrpcResponse createMockResponse(
            @DeviceCheckInStatus int checkInStatus,
            @Nullable Instant nextCheckInTime, @Nullable ProvisioningConfiguration config) {
        GetDeviceCheckInStatusGrpcResponse response = Mockito.mock(
                GetDeviceCheckInStatusGrpcResponse.class);
        Mockito.when(response.getDeviceCheckInStatus()).thenReturn(checkInStatus);
        if (nextCheckInTime != null) {
            Mockito.when(response.getNextCheckInTime()).thenReturn(nextCheckInTime);
        }
        if (config != null) {
            Mockito.when(response.getProvisioningConfig()).thenReturn(config);
        }
        return response;
    }
}
