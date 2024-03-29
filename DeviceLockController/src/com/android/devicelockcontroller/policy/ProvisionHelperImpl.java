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

package com.android.devicelockcontroller.policy;

import static androidx.work.WorkInfo.State.FAILED;
import static androidx.work.WorkInfo.State.SUCCEEDED;

import static com.android.devicelockcontroller.common.DeviceLockConstants.EXTRA_KIOSK_PACKAGE;
import static com.android.devicelockcontroller.policy.ProvisionStateController.ProvisionEvent.PROVISION_KIOSK;
import static com.android.devicelockcontroller.policy.ProvisionStateController.ProvisionEvent.PROVISION_PAUSE;
import static com.android.devicelockcontroller.provision.worker.IsDeviceInApprovedCountryWorker.BACKOFF_DELAY;
import static com.android.devicelockcontroller.provision.worker.ReportDeviceProvisionStateWorker.KEY_PROVISION_FAILURE_REASON;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.telephony.TelephonyManager;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LifecycleOwner;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ListenableWorker;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.android.devicelockcontroller.PlayInstallPackageTaskClassProvider;
import com.android.devicelockcontroller.activities.DeviceLockNotificationManager;
import com.android.devicelockcontroller.activities.ProvisioningProgress;
import com.android.devicelockcontroller.activities.ProvisioningProgressController;
import com.android.devicelockcontroller.common.DeviceLockConstants.ProvisionFailureReason;
import com.android.devicelockcontroller.provision.worker.IsDeviceInApprovedCountryWorker;
import com.android.devicelockcontroller.provision.worker.PauseProvisioningWorker;
import com.android.devicelockcontroller.provision.worker.ReportDeviceProvisionStateWorker;
import com.android.devicelockcontroller.receivers.ResumeProvisionReceiver;
import com.android.devicelockcontroller.schedule.DeviceLockControllerScheduler;
import com.android.devicelockcontroller.schedule.DeviceLockControllerSchedulerProvider;
import com.android.devicelockcontroller.storage.GlobalParametersClient;
import com.android.devicelockcontroller.storage.SetupParametersClient;
import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * An implementation of {@link ProvisionHelper}.
 */
public final class ProvisionHelperImpl implements ProvisionHelper {
    private static final String TAG = "ProvisionHelperImpl";
    private static final String FILENAME = "device-lock-controller-provisioning-preferences";
    private static final String USE_PREINSTALLED_KIOSK_PREF =
            "debug.devicelock.usepreinstalledkiosk";
    private static volatile SharedPreferences sSharedPreferences;
    private static synchronized SharedPreferences getSharedPreferences(
            Context context) {
        if (sSharedPreferences == null) {
            sSharedPreferences = context.createDeviceProtectedStorageContext().getSharedPreferences(
                    FILENAME,
                    Context.MODE_PRIVATE);
        }
        return sSharedPreferences;
    }
    @VisibleForTesting
    static final String INSTALLATION_TASKS_NAME = "Installation Tasks";

    private final Context mContext;
    private final ProvisionStateController mStateController;
    private final Executor mExecutor;
    private final DeviceLockControllerScheduler mScheduler;

    public ProvisionHelperImpl(Context context, ProvisionStateController stateController) {
        this(context, stateController, Executors.newCachedThreadPool());
    }

    @VisibleForTesting
    ProvisionHelperImpl(Context context, ProvisionStateController stateController,
            Executor executor) {
        mContext = context;
        mStateController = stateController;
        DeviceLockControllerSchedulerProvider schedulerProvider =
                (DeviceLockControllerSchedulerProvider) mContext.getApplicationContext();
        mScheduler = schedulerProvider.getDeviceLockControllerScheduler();
        mExecutor = executor;
    }

    @Override
    public void pauseProvision() {
        Futures.addCallback(
                Futures.transformAsync(
                        GlobalParametersClient.getInstance().setProvisionForced(true),
                        unused -> mStateController.setNextStateForEvent(PROVISION_PAUSE),
                        mExecutor),
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(Void unused) {
                        createNotification();
                        WorkManager workManager = WorkManager.getInstance(mContext);
                        PauseProvisioningWorker.reportProvisionPausedByUser(workManager);
                        mScheduler.scheduleResumeProvisionAlarm();
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        throw new RuntimeException("Failed to delay setup", t);
                    }
                }, mExecutor);
    }

    @Override
    public void scheduleKioskAppInstallation(LifecycleOwner owner,
            ProvisioningProgressController progressController, boolean isMandatory) {
        LogUtil.v(TAG, "Trigger setup flow");
        progressController.setProvisioningProgress(ProvisioningProgress.GETTING_DEVICE_READY);
        Futures.addCallback(SetupParametersClient.getInstance().getKioskPackage(),
                new FutureCallback<>() {

                    @Override
                    public void onSuccess(String kioskPackage) {
                        progressController.setProvisioningProgress(
                                ProvisioningProgress.INSTALLING_KIOSK_APP);
                        if (getPreinstalledKioskAllowed(mContext)) {
                            try {
                                mContext.getPackageManager().getPackageInfo(
                                        kioskPackage,
                                        ApplicationInfo.FLAG_INSTALLED);
                                LogUtil.i(TAG, "Kiosk app is pre-installed");
                                progressController.setProvisioningProgress(
                                        ProvisioningProgress.OPENING_KIOSK_APP);
                                mStateController.postSetNextStateForEventRequest(PROVISION_KIOSK);
                            } catch (NameNotFoundException e) {
                                LogUtil.i(TAG, "Kiosk app is not pre-installed");
                                installFromPlay(kioskPackage);
                            }
                        } else {
                            installFromPlay(kioskPackage);
                        }
                    }

                    private void installFromPlay(String kioskPackage) {
                        Context applicationContext = mContext.getApplicationContext();
                        final Class<? extends ListenableWorker> playInstallTaskClass =
                                ((PlayInstallPackageTaskClassProvider) applicationContext)
                                        .getPlayInstallPackageTaskClass();
                        WorkManager workManager = WorkManager.getInstance(mContext);
                        if (playInstallTaskClass == null) {
                            LogUtil.w(TAG, "Play installation not supported!");
                            handleFailure(ProvisionFailureReason.PLAY_TASK_UNAVAILABLE);
                            return;
                        }
                        String carrierInfo = Objects.requireNonNull(
                                applicationContext.getSystemService(
                                        TelephonyManager.class)).getSimOperator();
                        OneTimeWorkRequest isDeviceInApprovedCountryWork =
                                getIsDeviceInApprovedCountryWork(carrierInfo);
                        OneTimeWorkRequest playInstallPackageTask =
                                getPlayInstallPackageTask(playInstallTaskClass, kioskPackage);
                        workManager.beginUniqueWork(INSTALLATION_TASKS_NAME,
                                ExistingWorkPolicy.REPLACE, isDeviceInApprovedCountryWork).then(
                                playInstallPackageTask).enqueue();
                        mContext.getMainExecutor().execute(
                                () -> workManager.getWorkInfoByIdLiveData(
                                                playInstallPackageTask.getId())
                                        .observe(owner, workInfo -> {
                                            if (workInfo == null) return;
                                            WorkInfo.State state = workInfo.getState();
                                            LogUtil.d(TAG, "WorkInfo changed: " + workInfo);
                                            if (state == SUCCEEDED) {
                                                progressController.setProvisioningProgress(
                                                        ProvisioningProgress.OPENING_KIOSK_APP);
                                                ReportDeviceProvisionStateWorker
                                                        .reportSetupCompleted(workManager);
                                                mStateController.postSetNextStateForEventRequest(
                                                        PROVISION_KIOSK);
                                            } else if (state == FAILED) {
                                                int reason = workInfo.getOutputData().getInt(
                                                        KEY_PROVISION_FAILURE_REASON,
                                                        ProvisionFailureReason.UNKNOWN_REASON);
                                                LogUtil.w(TAG, "Play installation failed!");
                                                handleFailure(reason);
                                            }
                                        }));
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        LogUtil.w(TAG, "Failed to install kiosk app!", t);
                        handleFailure(ProvisionFailureReason.UNKNOWN_REASON);
                    }

                    private void handleFailure(@ProvisionFailureReason int reason) {
                        if (isMandatory) {
                            ReportDeviceProvisionStateWorker.reportSetupFailed(
                                    WorkManager.getInstance(mContext), reason);
                            progressController.setProvisioningProgress(
                                    ProvisioningProgress.MANDATORY_FAILED_PROVISION);
                            mScheduler.scheduleMandatoryResetDeviceAlarm();
                        } else {
                            // For non-mandatory provisioning, failure should only be reported after
                            // user exits the provisioning UI; otherwise, it could be reported
                            // multiple times if user choose to retry, which can break the
                            // 7-days failure flow.
                            progressController.setProvisioningProgress(
                                    ProvisioningProgress.getNonMandatoryProvisioningFailedProgress(
                                            reason));
                        }
                    }
                }, mExecutor);
    }

    @NonNull
    static OneTimeWorkRequest getIsDeviceInApprovedCountryWork(String carrierInfo) {
        return new OneTimeWorkRequest.Builder(
                IsDeviceInApprovedCountryWorker.class)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(
                        NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY)
                .setInputData(new Data.Builder().putString(
                        IsDeviceInApprovedCountryWorker.KEY_CARRIER_INFO,
                        carrierInfo).build()).build();
    }

    @NonNull
    static OneTimeWorkRequest getPlayInstallPackageTask(
            Class<? extends ListenableWorker> playInstallTaskClass, String kioskPackageName) {
        return new OneTimeWorkRequest.Builder(playInstallTaskClass)
                .setInputData(new Data.Builder().putString(
                        EXTRA_KIOSK_PACKAGE, kioskPackageName).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(
                        NetworkType.CONNECTED).build())
                .build();
    }

    private void createNotification() {
        LogUtil.d(TAG, "createNotification");
        Context context = mContext;

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                /* requestCode= */ 0,
                new Intent(context, ResumeProvisionReceiver.class),
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        LocalDateTime resumeDateTime = LocalDateTime.now().plusHours(1);
        DeviceLockNotificationManager.sendDeferredProvisioningNotification(context, resumeDateTime,
                pendingIntent);
    }

    /**
     * Sets whether provisioning should skip play install if there is already a preinstalled kiosk
     * app.
     */
    public static void setPreinstalledKioskAllowed(Context context, boolean enabled) {
        getSharedPreferences(context).edit().putBoolean(USE_PREINSTALLED_KIOSK_PREF, enabled)
                .apply();
    }

    /**
     * Returns true if provisioning should skip play install if there is already a preinstalled
     * kiosk app. By default, this returns true for debuggable build.
     */
    public static boolean getPreinstalledKioskAllowed(Context context) {
        return getSharedPreferences(context).getBoolean(
                USE_PREINSTALLED_KIOSK_PREF, Build.isDebuggable());
    }
}
