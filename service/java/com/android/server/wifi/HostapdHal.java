/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.wifi;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.wifi.hostapd.V1_0.HostapdStatus;
import android.hardware.wifi.hostapd.V1_0.HostapdStatusCode;
import android.hardware.wifi.hostapd.V1_0.IHostapd;
import android.hardware.wifi.hostapd.V1_2.DebugLevel;
import android.hardware.wifi.hostapd.V1_2.Ieee80211ReasonCode;
import android.hardware.wifi.hostapd.V1_3.Bandwidth;
import android.hardware.wifi.hostapd.V1_3.Generation;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SoftApConfiguration.BandType;
import android.net.wifi.SoftApInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IHwBinder.DeathRecipient;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.WifiNative.HostapdDeathEventHandler;
import com.android.server.wifi.WifiNative.SoftApListener;
import com.android.server.wifi.util.ApConfigUtil;
import com.android.server.wifi.util.NativeUtil;
import com.android.wifi.resources.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.annotation.concurrent.ThreadSafe;

/**
 * To maintain thread-safety, the locking protocol is that every non-static method (regardless of
 * access level) acquires mLock.
 */
@ThreadSafe
public class HostapdHal {
    private static final String TAG = "HostapdHal";
    @VisibleForTesting
    public static final String HAL_INSTANCE_NAME = "default";
    @VisibleForTesting
    public static final long WAIT_FOR_DEATH_TIMEOUT_MS = 50L;

    private final Object mLock = new Object();
    private boolean mVerboseLoggingEnabled = false;
    private final Context mContext;
    private final Handler mEventHandler;
    private boolean mForceApChannel = false;
    private int mForcedApBand;
    private int mForcedApChannel;

    // Hostapd HAL interface objects
    private IServiceManager mIServiceManager = null;
    private IHostapd mIHostapd;
    private HashMap<String, Runnable> mSoftApFailureListeners = new HashMap<>();
    private SoftApListener mSoftApEventListener;
    private HostapdDeathEventHandler mDeathEventHandler;
    private ServiceManagerDeathRecipient mServiceManagerDeathRecipient;
    private HostapdDeathRecipient mHostapdDeathRecipient;
    // Death recipient cookie registered for current supplicant instance.
    private long mDeathRecipientCookie = 0;

    private final IServiceNotification mServiceNotificationCallback =
            new IServiceNotification.Stub() {
        public void onRegistration(String fqName, String name, boolean preexisting) {
            synchronized (mLock) {
                if (mVerboseLoggingEnabled) {
                    Log.i(TAG, "IServiceNotification.onRegistration for: " + fqName
                            + ", " + name + " preexisting=" + preexisting);
                }
                if (!initHostapdService()) {
                    Log.e(TAG, "initalizing IHostapd failed.");
                    hostapdServiceDiedHandler(mDeathRecipientCookie);
                } else {
                    Log.i(TAG, "Completed initialization of IHostapd.");
                }
            }
        }
    };
    private class ServiceManagerDeathRecipient implements DeathRecipient {
        @Override
        public void serviceDied(long cookie) {
            mEventHandler.post(() -> {
                synchronized (mLock) {
                    Log.w(TAG, "IServiceManager died: cookie=" + cookie);
                    hostapdServiceDiedHandler(mDeathRecipientCookie);
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                }
            });
        }
    }
    private class HostapdDeathRecipient implements DeathRecipient {
        @Override
        public void serviceDied(long cookie) {
            mEventHandler.post(() -> {
                synchronized (mLock) {
                    Log.w(TAG, "IHostapd/IHostapd died: cookie=" + cookie);
                    hostapdServiceDiedHandler(cookie);
                }
            });
        }
    }

    public HostapdHal(Context context, Handler handler) {
        mContext = context;
        mEventHandler = handler;
        mServiceManagerDeathRecipient = new ServiceManagerDeathRecipient();
        mHostapdDeathRecipient = new HostapdDeathRecipient();
    }

    /**
     * Enable/Disable verbose logging.
     *
     * @param enable true to enable, false to disable.
     */
    void enableVerboseLogging(boolean enable) {
        synchronized (mLock) {
            mVerboseLoggingEnabled = enable;
            setLogLevel();
        }
    }

    /**
     * Uses the IServiceManager to check if the device is running V1_1 of the HAL from the VINTF for
     * the device.
     * @return true if supported, false otherwise.
     */
    private boolean isV1_1() {
        return checkHalVersionByInterfaceName(
                android.hardware.wifi.hostapd.V1_1.IHostapd.kInterfaceName);
    }

    /**
     * Uses the IServiceManager to check if the device is running V1_2 of the HAL from the VINTF for
     * the device.
     * @return true if supported, false otherwise.
     */
    private boolean isV1_2() {
        return checkHalVersionByInterfaceName(
                android.hardware.wifi.hostapd.V1_2.IHostapd.kInterfaceName);
    }

    /**
     * Uses the IServiceManager to check if the device is running V1_3 of the HAL from the VINTF for
     * the device.
     * @return true if supported, false otherwise.
     */
    private boolean isV1_3() {
        return checkHalVersionByInterfaceName(
                android.hardware.wifi.hostapd.V1_3.IHostapd.kInterfaceName);
    }

    private boolean checkHalVersionByInterfaceName(String interfaceName) {
        if (interfaceName == null) {
            return false;
        }
        synchronized (mLock) {
            if (mIServiceManager == null) {
                Log.e(TAG, "checkHalVersionByInterfaceName called but mServiceManager is null!?");
                return false;
            }
            try {
                return (mIServiceManager.getTransport(
                        interfaceName,
                        HAL_INSTANCE_NAME)
                        != IServiceManager.Transport.EMPTY);
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while operating on IServiceManager: " + e);
                handleRemoteException(e, "getTransport");
                return false;
            }
        }
    }

    /**
     * Link to death for IServiceManager object.
     * @return true on success, false otherwise.
     */
    private boolean linkToServiceManagerDeath() {
        synchronized (mLock) {
            if (mIServiceManager == null) return false;
            try {
                if (!mIServiceManager.linkToDeath(mServiceManagerDeathRecipient, 0)) {
                    Log.wtf(TAG, "Error on linkToDeath on IServiceManager");
                    hostapdServiceDiedHandler(mDeathRecipientCookie);
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "IServiceManager.linkToDeath exception", e);
                mIServiceManager = null; // Will need to register a new ServiceNotification
                return false;
            }
            return true;
        }
    }

    /**
     * Registers a service notification for the IHostapd service, which triggers intialization of
     * the IHostapd
     * @return true if the service notification was successfully registered
     */
    public boolean initialize() {
        synchronized (mLock) {
            if (mVerboseLoggingEnabled) {
                Log.i(TAG, "Registering IHostapd service ready callback.");
            }
            mIHostapd = null;
            if (mIServiceManager != null) {
                // Already have an IServiceManager and serviceNotification registered, don't
                // don't register another.
                return true;
            }
            try {
                mIServiceManager = getServiceManagerMockable();
                if (mIServiceManager == null) {
                    Log.e(TAG, "Failed to get HIDL Service Manager");
                    return false;
                }
                if (!linkToServiceManagerDeath()) {
                    return false;
                }
                /* TODO(b/33639391) : Use the new IHostapd.registerForNotifications() once it
                   exists */
                if (!mIServiceManager.registerForNotifications(
                        IHostapd.kInterfaceName, "", mServiceNotificationCallback)) {
                    Log.e(TAG, "Failed to register for notifications to "
                            + IHostapd.kInterfaceName);
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while trying to register a listener for IHostapd service: "
                        + e);
                hostapdServiceDiedHandler(mDeathRecipientCookie);
                mIServiceManager = null; // Will need to register a new ServiceNotification
                return false;
            }
            return true;
        }
    }

    /**
     * Link to death for IHostapd object.
     * @return true on success, false otherwise.
     */
    private boolean linkToHostapdDeath(DeathRecipient deathRecipient, long cookie) {
        synchronized (mLock) {
            if (mIHostapd == null) return false;
            try {
                if (!mIHostapd.linkToDeath(deathRecipient, cookie)) {
                    Log.wtf(TAG, "Error on linkToDeath on IHostapd");
                    hostapdServiceDiedHandler(mDeathRecipientCookie);
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "IHostapd.linkToDeath exception", e);
                return false;
            }
            return true;
        }
    }

    private boolean registerCallback(
            android.hardware.wifi.hostapd.V1_1.IHostapdCallback callback) {
        synchronized (mLock) {
            String methodStr = "registerCallback_1_1";
            try {
                android.hardware.wifi.hostapd.V1_1.IHostapd iHostapdV1_1 = getHostapdMockableV1_1();
                if (iHostapdV1_1 == null) return false;
                HostapdStatus status =  iHostapdV1_1.registerCallback(callback);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    private boolean registerCallback_1_3(
            android.hardware.wifi.hostapd.V1_3.IHostapdCallback callback) {
        synchronized (mLock) {
            String methodStr = "registerCallback_1_3";
            try {
                android.hardware.wifi.hostapd.V1_3.IHostapd iHostapdV1_3 = getHostapdMockableV1_3();
                if (iHostapdV1_3 == null) return false;
                android.hardware.wifi.hostapd.V1_2.HostapdStatus status =
                        iHostapdV1_3.registerCallback_1_3(callback);
                return checkStatusAndLogFailure12(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Initialize the IHostapd object.
     * @return true on success, false otherwise.
     */
    private boolean initHostapdService() {
        synchronized (mLock) {
            try {
                mIHostapd = getHostapdMockable();
            } catch (RemoteException e) {
                Log.e(TAG, "IHostapd.getService exception: " + e);
                return false;
            } catch (NoSuchElementException e) {
                Log.e(TAG, "IHostapd.getService exception: " + e);
                return false;
            }
            if (mIHostapd == null) {
                Log.e(TAG, "Got null IHostapd service. Stopping hostapd HIDL startup");
                return false;
            }
            if (!linkToHostapdDeath(mHostapdDeathRecipient, ++mDeathRecipientCookie)) {
                Log.e(TAG, "Fail to link to Hostapd Death, Stopping hostapd HIDL startup");
                mIHostapd = null;
                return false;
            }
            // Register for callbacks for 1.1 hostapd.
            if (isV1_3()) {
                if (!registerCallback_1_3(new HostapdCallback_1_3())) {
                    Log.e(TAG, "Fail to regiester Callback 1_3, Stopping hostapd HIDL startup");
                    mIHostapd = null;
                    return false;
                }
            } else if (isV1_1() && !registerCallback(new HostapdCallback())) {
                Log.e(TAG, "Fail to regiester Callback, Stopping hostapd HIDL startup");
                mIHostapd = null;
                return false;
            }

            // Setup log level
            setLogLevel();
        }
        return true;
    }

    /**
     * Enable force-soft-AP-channel mode which takes effect when soft AP starts next time
     * @param forcedApChannel The forced IEEE channel number
     */
    void enableForceSoftApChannel(int forcedApChannel, int forcedApBand) {
        mForceApChannel = true;
        mForcedApChannel = forcedApChannel;
        mForcedApBand = forcedApBand;
    }

    /**
     * Disable force-soft-AP-channel mode which take effect when soft AP starts next time
     */
    void disableForceSoftApChannel() {
        mForceApChannel = false;
    }

    /**
     * Register the provided callback handler for SoftAp events.
     * <p>
     * Note that only one callback can be registered at a time - any registration overrides previous
     * registrations.
     *
     * @param ifaceName Name of the interface.
     * @param listener Callback listener for AP events.
     * @return true on success, false on failure.
     */
    public boolean registerApCallback(@NonNull String ifaceName,
            @NonNull SoftApListener listener) {
        if (listener == null) {
            Log.e(TAG, "registerApCallback called with a null callback");
            return false;
        }

        if (!isV1_3()) {
            Log.d(TAG, "The current HAL doesn't support event callback.");
            return false;
        }
        mSoftApEventListener = listener;
        Log.i(TAG, "registerApCallback Successful in " + ifaceName);
        return true;
    }

    /**
     * Add and start a new access point.
     *
     * @param ifaceName Name of the interface.
     * @param config Configuration to use for the AP.
     * @param isMetered Indicates the network is metered or not.
     * @param onFailureListener A runnable to be triggered on failure.
     * @return true on success, false otherwise.
     */
    public boolean addAccessPoint(@NonNull String ifaceName, @NonNull SoftApConfiguration config,
                                  boolean isMetered, @NonNull Runnable onFailureListener) {
        synchronized (mLock) {
            final String methodStr = "addAccessPoint";
            if (mForceApChannel) {
                config = new SoftApConfiguration.Builder(config)
                        .setChannel(mForcedApChannel, mForcedApBand).build();
            }
            IHostapd.IfaceParams ifaceParamsV1_0 = prepareIfaceParamsV1_0(ifaceName, config);
            android.hardware.wifi.hostapd.V1_2.IHostapd.NetworkParams nwParamsV1_2 =
                    prepareNetworkParamsV1_2(config);
            if (nwParamsV1_2 == null) return false;
            if (!checkHostapdAndLogFailure(methodStr)) return false;
            try {
                HostapdStatus status;
                if (!isV1_1()) {
                    // V1_0 case
                    status = mIHostapd.addAccessPoint(ifaceParamsV1_0, nwParamsV1_2.V1_0);
                    if (!checkStatusAndLogFailure(status, methodStr)) {
                        return false;
                    }
                } else {
                    android.hardware.wifi.hostapd.V1_1.IHostapd.IfaceParams ifaceParamsV1_1 =
                            prepareIfaceParamsV1_1(ifaceParamsV1_0, config);
                    if (!isV1_2()) {
                        // V1_1 case
                        android.hardware.wifi.hostapd.V1_1.IHostapd iHostapdV1_1 =
                                getHostapdMockableV1_1();
                        if (iHostapdV1_1 == null) return false;
                        status = iHostapdV1_1.addAccessPoint_1_1(ifaceParamsV1_1,
                                nwParamsV1_2.V1_0);
                        if (!checkStatusAndLogFailure(status, methodStr)) {
                            return false;
                        }
                    } else {
                        // V1_2 & V1_3 case
                        android.hardware.wifi.hostapd.V1_2.HostapdStatus status12;
                        android.hardware.wifi.hostapd.V1_2.IHostapd.IfaceParams ifaceParamsV1_2 =
                                prepareIfaceParamsV1_2(ifaceParamsV1_1, config);
                        if (!isV1_3()) {
                            // V1_2 case
                            android.hardware.wifi.hostapd.V1_2.IHostapd iHostapdV1_2 =
                                    getHostapdMockableV1_2();
                            if (iHostapdV1_2 == null) return false;
                            status12 = iHostapdV1_2.addAccessPoint_1_2(ifaceParamsV1_2,
                                    nwParamsV1_2);
                        } else {
                            // V1_3 case
                            android.hardware.wifi.hostapd.V1_3
                                    .IHostapd.NetworkParams nwParamsV1_3 =
                                    new android.hardware.wifi.hostapd.V1_3
                                    .IHostapd.NetworkParams();
                            nwParamsV1_3.V1_2 = nwParamsV1_2;
                            nwParamsV1_3.isMetered = isMetered;
                            android.hardware.wifi.hostapd.V1_3.IHostapd.IfaceParams ifaceParams1_3 =
                                    prepareIfaceParamsV1_3(ifaceParamsV1_2, config);
                            android.hardware.wifi.hostapd.V1_3.IHostapd iHostapdV1_3 =
                                    getHostapdMockableV1_3();
                            if (iHostapdV1_3 == null) return false;
                            status12 = iHostapdV1_3.addAccessPoint_1_3(ifaceParams1_3,
                                    nwParamsV1_3);
                        }
                        if (!checkStatusAndLogFailure12(status12, methodStr)) {
                            return false;
                        }
                    }
                }

                mSoftApFailureListeners.put(ifaceName, onFailureListener);
                return true;
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Unrecognized apBand: " + config.getBand());
                return false;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Remove a previously started access point.
     *
     * @param ifaceName Name of the interface.
     * @return true on success, false otherwise.
     */
    public boolean removeAccessPoint(@NonNull String ifaceName) {
        synchronized (mLock) {
            final String methodStr = "removeAccessPoint";
            if (!checkHostapdAndLogFailure(methodStr)) return false;
            try {
                HostapdStatus status = mIHostapd.removeAccessPoint(ifaceName);
                if (!checkStatusAndLogFailure(status, methodStr)) {
                    return false;
                }
                mSoftApFailureListeners.remove(ifaceName);
                mSoftApEventListener = null;
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Remove a previously connected client.
     *
     * @param ifaceName Name of the interface.
     * @param client Mac Address of the client.
     * @param reasonCode One of disconnect reason code which defined in {@link WifiManager}.
     * @return true on success, false otherwise.
     */
    public boolean forceClientDisconnect(@NonNull String ifaceName,
            @NonNull MacAddress client, int reasonCode) {
        final String methodStr = "forceClientDisconnect";
        if (isV1_2()) {
            try {
                android.hardware.wifi.hostapd.V1_2.IHostapd iHostapdV1_2 =
                        getHostapdMockableV1_2();
                if (iHostapdV1_2 == null) return false;
                byte[] clientMacByteArray = client.toByteArray();
                short disconnectReason;
                switch (reasonCode) {
                    case WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER:
                        disconnectReason = Ieee80211ReasonCode.WLAN_REASON_PREV_AUTH_NOT_VALID;
                        break;
                    case WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_NO_MORE_STAS:
                        disconnectReason = Ieee80211ReasonCode.WLAN_REASON_DISASSOC_AP_BUSY;
                        break;
                    case WifiManager.SAP_CLIENT_DISCONNECT_REASON_CODE_UNSPECIFIED:
                        disconnectReason = Ieee80211ReasonCode.WLAN_REASON_UNSPECIFIED;
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "Unknown disconnect reason code:" + reasonCode);
                }
                android.hardware.wifi.hostapd.V1_2.HostapdStatus status =
                        iHostapdV1_2.forceClientDisconnect(ifaceName,
                        clientMacByteArray, disconnectReason);
                if (status.code == HostapdStatusCode.SUCCESS) {
                    return true;
                }
                Log.d(TAG, "Error when call forceClientDisconnect, status.code = " + status.code);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            }
        } else {
            Log.d(TAG, "HIDL doesn't support forceClientDisconnect");
        }
        return false;
    }

    /**
     * Registers a death notification for hostapd.
     * @return Returns true on success.
     */
    public boolean registerDeathHandler(@NonNull HostapdDeathEventHandler handler) {
        if (mDeathEventHandler != null) {
            Log.e(TAG, "Death handler already present");
        }
        mDeathEventHandler = handler;
        return true;
    }

    /**
     * Deregisters a death notification for hostapd.
     * @return Returns true on success.
     */
    public boolean deregisterDeathHandler() {
        if (mDeathEventHandler == null) {
            Log.e(TAG, "No Death handler present");
        }
        mDeathEventHandler = null;
        return true;
    }

    /**
     * Clear internal state.
     */
    private void clearState() {
        synchronized (mLock) {
            mIHostapd = null;
        }
    }

    /**
     * Handle hostapd death.
     */
    private void hostapdServiceDiedHandler(long cookie) {
        synchronized (mLock) {
            if (mDeathRecipientCookie != cookie) {
                Log.i(TAG, "Ignoring stale death recipient notification");
                return;
            }
            clearState();
            if (mDeathEventHandler != null) {
                mDeathEventHandler.onDeath();
            }
        }
    }

    /**
     * Signals whether Initialization completed successfully.
     */
    public boolean isInitializationStarted() {
        synchronized (mLock) {
            return mIServiceManager != null;
        }
    }

    /**
     * Signals whether Initialization completed successfully.
     */
    public boolean isInitializationComplete() {
        synchronized (mLock) {
            return mIHostapd != null;
        }
    }

    /**
     * Start the hostapd daemon.
     *
     * @return true on success, false otherwise.
     */
    public boolean startDaemon() {
        synchronized (mLock) {
            try {
                // This should startup hostapd daemon using the lazy start HAL mechanism.
                getHostapdMockable();
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while trying to start hostapd: "
                        + e);
                hostapdServiceDiedHandler(mDeathRecipientCookie);
                return false;
            } catch (NoSuchElementException e) {
                // We're starting the daemon, so expect |NoSuchElementException|.
                Log.d(TAG, "Successfully triggered start of hostapd using HIDL");
            }
            return true;
        }
    }

    /**
     * Terminate the hostapd daemon & wait for it's death.
     */
    public void terminate() {
        synchronized (mLock) {
            // Register for a new death listener to block until hostapd is dead.
            final long waitForDeathCookie = new Random().nextLong();
            final CountDownLatch waitForDeathLatch = new CountDownLatch(1);
            linkToHostapdDeath((cookie) -> {
                Log.d(TAG, "IHostapd died: cookie=" + cookie);
                if (cookie != waitForDeathCookie) return;
                waitForDeathLatch.countDown();
            }, waitForDeathCookie);

            final String methodStr = "terminate";
            if (!checkHostapdAndLogFailure(methodStr)) return;
            try {
                mIHostapd.terminate();
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            }

            // Now wait for death listener callback to confirm that it's dead.
            try {
                if (!waitForDeathLatch.await(WAIT_FOR_DEATH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "Timed out waiting for confirmation of hostapd death");
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "Failed to wait for hostapd death");
            }
        }
    }

    /**
     * Wrapper functions to access static HAL methods, created to be mockable in unit tests
     */
    @VisibleForTesting
    protected IServiceManager getServiceManagerMockable() throws RemoteException {
        synchronized (mLock) {
            return IServiceManager.getService();
        }
    }

    @VisibleForTesting
    protected IHostapd getHostapdMockable() throws RemoteException {
        synchronized (mLock) {
            return IHostapd.getService();
        }
    }

    @VisibleForTesting
    protected android.hardware.wifi.hostapd.V1_1.IHostapd getHostapdMockableV1_1()
            throws RemoteException {
        synchronized (mLock) {
            try {
                return android.hardware.wifi.hostapd.V1_1.IHostapd.castFrom(mIHostapd);
            } catch (NoSuchElementException e) {
                Log.e(TAG, "Failed to get IHostapd", e);
                return null;
            }
        }
    }

    @VisibleForTesting
    protected android.hardware.wifi.hostapd.V1_2.IHostapd getHostapdMockableV1_2()
            throws RemoteException {
        synchronized (mLock) {
            try {
                return android.hardware.wifi.hostapd.V1_2.IHostapd.castFrom(mIHostapd);
            } catch (NoSuchElementException e) {
                Log.e(TAG, "Failed to get IHostapd", e);
                return null;
            }
        }
    }

    @VisibleForTesting
    protected android.hardware.wifi.hostapd.V1_3.IHostapd getHostapdMockableV1_3()
            throws RemoteException {
        synchronized (mLock) {
            try {
                return android.hardware.wifi.hostapd.V1_3.IHostapd.castFrom(mIHostapd);
            } catch (NoSuchElementException e) {
                Log.e(TAG, "Failed to get IHostapd", e);
                return null;
            }
        }
    }

    private void updateIfaceParams_1_2FromResource(
            android.hardware.wifi.hostapd.V1_2.IHostapd.IfaceParams ifaceParams12) {
        ifaceParams12.hwModeParams.enable80211AX =
                mContext.getResources().getBoolean(
                R.bool.config_wifiSoftapIeee80211axSupported);
        ifaceParams12.hwModeParams.enable6GhzBand =
                mContext.getResources().getBoolean(
                R.bool.config_wifiSoftap6ghzSupported);
        ifaceParams12.hwModeParams.enableHeSingleUserBeamformer =
                mContext.getResources().getBoolean(
                R.bool.config_wifiSoftapHeSuBeamformerSupported);
        ifaceParams12.hwModeParams.enableHeSingleUserBeamformee =
                mContext.getResources().getBoolean(
                R.bool.config_wifiSoftapHeSuBeamformeeSupported);
        ifaceParams12.hwModeParams.enableHeMultiUserBeamformer =
                mContext.getResources().getBoolean(
                R.bool.config_wifiSoftapHeMuBeamformerSupported);
        ifaceParams12.hwModeParams.enableHeTargetWakeTime =
                mContext.getResources().getBoolean(R.bool.config_wifiSoftapHeTwtSupported);
    }

    private android.hardware.wifi.hostapd.V1_0.IHostapd.IfaceParams
            prepareIfaceParamsV1_0(String ifaceName, SoftApConfiguration config) {
        IHostapd.IfaceParams ifaceParamsV1_0 = new IHostapd.IfaceParams();
        ifaceParamsV1_0.ifaceName = ifaceName;
        ifaceParamsV1_0.hwModeParams.enable80211N = true;
        ifaceParamsV1_0.hwModeParams.enable80211AC = mContext.getResources().getBoolean(
                R.bool.config_wifi_softap_ieee80211ac_supported);
        boolean enableAcs = ApConfigUtil.isAcsSupported(mContext) && config.getChannel() == 0;
        if (enableAcs) {
            ifaceParamsV1_0.channelParams.enableAcs = true;
            ifaceParamsV1_0.channelParams.acsShouldExcludeDfs = !mContext.getResources()
                    .getBoolean(R.bool.config_wifiSoftapAcsIncludeDfs);
        }
        ifaceParamsV1_0.channelParams.channel = config.getChannel();
        ifaceParamsV1_0.channelParams.band = getHalBand(config.getBand());
        return ifaceParamsV1_0;
    }

    private android.hardware.wifi.hostapd.V1_1.IHostapd.IfaceParams
            prepareIfaceParamsV1_1(
            android.hardware.wifi.hostapd.V1_0.IHostapd.IfaceParams ifaceParamsV10,
            SoftApConfiguration config) {
        android.hardware.wifi.hostapd.V1_1.IHostapd.IfaceParams ifaceParamsV1_1 =
                new android.hardware.wifi.hostapd.V1_1.IHostapd.IfaceParams();
        ifaceParamsV1_1.V1_0 = ifaceParamsV10;
        ifaceParamsV10.channelParams.band = getHalBand(config.getBand());

        if (ifaceParamsV10.channelParams.enableAcs) {
            if ((config.getBand() & SoftApConfiguration.BAND_2GHZ) != 0) {
                ifaceParamsV1_1.channelParams.acsChannelRanges.addAll(
                        toAcsChannelRanges(mContext.getResources().getString(
                        R.string.config_wifiSoftap2gChannelList)));
            }
            if ((config.getBand() & SoftApConfiguration.BAND_5GHZ) != 0) {
                ifaceParamsV1_1.channelParams.acsChannelRanges.addAll(
                        toAcsChannelRanges(mContext.getResources().getString(
                        R.string.config_wifiSoftap5gChannelList)));
            }
        }
        return ifaceParamsV1_1;
    }

    private android.hardware.wifi.hostapd.V1_2.IHostapd.IfaceParams
            prepareIfaceParamsV1_2(
            android.hardware.wifi.hostapd.V1_1.IHostapd.IfaceParams ifaceParamsV11,
            SoftApConfiguration config) {
        android.hardware.wifi.hostapd.V1_2.IHostapd.IfaceParams ifaceParamsV1_2 =
                new android.hardware.wifi.hostapd.V1_2.IHostapd.IfaceParams();
        ifaceParamsV1_2.V1_1 = ifaceParamsV11;
        updateIfaceParams_1_2FromResource(ifaceParamsV1_2);
        ifaceParamsV1_2.channelParams.bandMask = getHalBandMask(config.getBand());

        // Prepare freq ranges/lists if needed
        if (ifaceParamsV11.V1_0.channelParams.enableAcs && ApConfigUtil.isSendFreqRangesNeeded(
                config.getBand(), mContext)) {
            prepareAcsChannelFreqRangesMhz(ifaceParamsV1_2.channelParams, config.getBand());
        }
        return ifaceParamsV1_2;
    }

    private android.hardware.wifi.hostapd.V1_3.IHostapd.IfaceParams
            prepareIfaceParamsV1_3(
            android.hardware.wifi.hostapd.V1_2.IHostapd.IfaceParams ifaceParamsV12,
            SoftApConfiguration config) {
        android.hardware.wifi.hostapd.V1_3.IHostapd.IfaceParams ifaceParamsV1_3 =
                new android.hardware.wifi.hostapd.V1_3.IHostapd.IfaceParams();
        ifaceParamsV1_3.V1_2 = ifaceParamsV12;
        ArrayList<android.hardware.wifi.hostapd.V1_3.IHostapd.ChannelParams>
                channelParams1_3List = new ArrayList<>();
        for (int i = 0; i < config.getChannels().size(); i++) {
            android.hardware.wifi.hostapd.V1_3.IHostapd.ChannelParams channelParam13 =
                    new android.hardware.wifi.hostapd.V1_3.IHostapd.ChannelParams();
            // Prepare channel
            channelParam13.channel = config.getChannels().valueAt(i);
            // Prepare enableAcs
            channelParam13.enableAcs = ApConfigUtil.isAcsSupported(mContext)
                    && channelParam13.channel == 0;
            // Prepare the bandMask
            channelParam13.V1_2.bandMask = getHalBandMask(config.getChannels().keyAt(i));
            // Prepare  AcsChannelFreqRangesMhz
            if (channelParam13.enableAcs && ApConfigUtil.isSendFreqRangesNeeded(
                    config.getChannels().keyAt(i), mContext)) {
                prepareAcsChannelFreqRangesMhz(channelParam13.V1_2, config.getChannels().keyAt(i));
            }
            channelParams1_3List.add(channelParam13);
        }
        ifaceParamsV1_3.channelParamsList = channelParams1_3List;
        return ifaceParamsV1_3;
    }

    private android.hardware.wifi.hostapd.V1_2.IHostapd.NetworkParams
            prepareNetworkParamsV1_2(SoftApConfiguration config) {
        android.hardware.wifi.hostapd.V1_2.IHostapd.NetworkParams nwParamsV1_2 =
                new android.hardware.wifi.hostapd.V1_2.IHostapd.NetworkParams();
        nwParamsV1_2.V1_0.ssid.addAll(NativeUtil.stringToByteArrayList(config.getSsid()));
        nwParamsV1_2.V1_0.isHidden = config.isHiddenSsid();
        int encryptionType = getEncryptionType(config);
        nwParamsV1_2.encryptionType = encryptionType;
        nwParamsV1_2.passphrase = (config.getPassphrase() != null)
                    ? config.getPassphrase() : "";
        if (encryptionType
                == android.hardware.wifi.hostapd.V1_2.IHostapd.EncryptionType.WPA3_SAE
                || encryptionType == android.hardware.wifi.hostapd.V1_2.IHostapd
                .EncryptionType.WPA3_SAE_TRANSITION) {
            if (!isV1_2()) {
                // It should not happen since we should reject configuration in SoftApManager
                Log.e(TAG, "Unsupported Configuration found: " + config);
                return null;
            }
        } else {
            // Fill old parameter for old hidl.
            nwParamsV1_2.V1_0.encryptionType = encryptionType;
            nwParamsV1_2.V1_0.pskPassphrase = (config.getPassphrase() != null)
                    ? config.getPassphrase() : "";
        }
        return nwParamsV1_2;
    }

    private static int getEncryptionType(SoftApConfiguration localConfig) {
        int encryptionType;
        switch (localConfig.getSecurityType()) {
            case SoftApConfiguration.SECURITY_TYPE_OPEN:
                encryptionType = IHostapd.EncryptionType.NONE;
                break;
            case SoftApConfiguration.SECURITY_TYPE_WPA2_PSK:
                encryptionType = IHostapd.EncryptionType.WPA2;
                break;
            case SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION:
                encryptionType = android.hardware.wifi.hostapd.V1_2
                        .IHostapd.EncryptionType.WPA3_SAE_TRANSITION;
                break;
            case SoftApConfiguration.SECURITY_TYPE_WPA3_SAE:
                encryptionType = android.hardware.wifi.hostapd.V1_2
                        .IHostapd.EncryptionType.WPA3_SAE;
                break;
            default:
                // We really shouldn't default to None, but this was how NetworkManagementService
                // used to do this.
                encryptionType = IHostapd.EncryptionType.NONE;
                break;
        }
        return encryptionType;
    }

    private static int getHalBandMask(int apBand) {
        int bandMask = 0;

        if (!ApConfigUtil.isBandValid(apBand)) {
            throw new IllegalArgumentException();
        }

        if (ApConfigUtil.containsBand(apBand, SoftApConfiguration.BAND_2GHZ)) {
            bandMask |= android.hardware.wifi.hostapd.V1_2.IHostapd.BandMask.BAND_2_GHZ;
        }
        if (ApConfigUtil.containsBand(apBand, SoftApConfiguration.BAND_5GHZ)) {
            bandMask |= android.hardware.wifi.hostapd.V1_2.IHostapd.BandMask.BAND_5_GHZ;
        }
        if (ApConfigUtil.containsBand(apBand, SoftApConfiguration.BAND_6GHZ)) {
            bandMask |= android.hardware.wifi.hostapd.V1_2.IHostapd.BandMask.BAND_6_GHZ;
        }

        return bandMask;
    }

    private static int getHalBand(int apBand) {
        if (!ApConfigUtil.isBandValid(apBand)) {
            throw new IllegalArgumentException();
        }

        switch (apBand) {
            case SoftApConfiguration.BAND_2GHZ:
                return IHostapd.Band.BAND_2_4_GHZ;
            case SoftApConfiguration.BAND_5GHZ:
                return IHostapd.Band.BAND_5_GHZ;
            default:
                return IHostapd.Band.BAND_ANY;
        }
    }

    /**
     * Convert channel list string like '1-6,11' to list of AcsChannelRanges
     */
    private List<android.hardware.wifi.hostapd.V1_1.IHostapd.AcsChannelRange>
            toAcsChannelRanges(String channelListStr) {
        ArrayList<android.hardware.wifi.hostapd.V1_1.IHostapd.AcsChannelRange> acsChannelRanges =
                new ArrayList<>();

        for (String channelRange : channelListStr.split(",")) {
            android.hardware.wifi.hostapd.V1_1.IHostapd.AcsChannelRange acsChannelRange =
                    new android.hardware.wifi.hostapd.V1_1.IHostapd.AcsChannelRange();
            try {
                if (channelRange.contains("-")) {
                    String[] channels  = channelRange.split("-");
                    if (channels.length != 2) {
                        Log.e(TAG, "Unrecognized channel range, length is " + channels.length);
                        continue;
                    }
                    int start = Integer.parseInt(channels[0].trim());
                    int end = Integer.parseInt(channels[1].trim());
                    if (start > end) {
                        Log.e(TAG, "Invalid channel range, from " + start + " to " + end);
                        continue;
                    }
                    acsChannelRange.start = start;
                    acsChannelRange.end = end;
                } else {
                    acsChannelRange.start = Integer.parseInt(channelRange.trim());
                    acsChannelRange.end = acsChannelRange.start;
                }
            } catch (NumberFormatException e) {
                // Ignore malformed value
                Log.e(TAG, "Malformed channel value detected: " + e);
                continue;
            }
            acsChannelRanges.add(acsChannelRange);
        }
        return acsChannelRanges;
    }


    /**
     * Prepare the acsChannelFreqRangesMhz in V1_2.IHostapd.ChannelParams.
     */
    private void prepareAcsChannelFreqRangesMhz(
            android.hardware.wifi.hostapd.V1_2.IHostapd.ChannelParams channelParams12,
            @BandType int band) {
        if ((band & SoftApConfiguration.BAND_2GHZ) != 0) {
            channelParams12.acsChannelFreqRangesMhz.addAll(
                    toAcsFreqRanges(SoftApConfiguration.BAND_2GHZ));
        }
        if ((band & SoftApConfiguration.BAND_5GHZ) != 0) {
            channelParams12.acsChannelFreqRangesMhz.addAll(
                    toAcsFreqRanges(SoftApConfiguration.BAND_5GHZ));
        }
        if ((band & SoftApConfiguration.BAND_6GHZ) != 0) {
            channelParams12.acsChannelFreqRangesMhz.addAll(
                    toAcsFreqRanges(SoftApConfiguration.BAND_6GHZ));
        }
    }

    /**
     * Convert channel list string like '1-6,11' to list of AcsFreqRange
     */
    private List<android.hardware.wifi.hostapd.V1_2.IHostapd.AcsFrequencyRange>
            toAcsFreqRanges(@BandType int band) {
        List<android.hardware.wifi.hostapd.V1_2.IHostapd.AcsFrequencyRange>
                acsFrequencyRanges = new ArrayList<>();

        if (!ApConfigUtil.isBandValid(band) || ApConfigUtil.isMultiband(band)) {
            Log.e(TAG, "Invalid band : " + band);
            return acsFrequencyRanges;
        }

        String channelListStr;
        switch (band) {
            case SoftApConfiguration.BAND_2GHZ:
                channelListStr = mContext.getResources().getString(
                        R.string.config_wifiSoftap2gChannelList);
                if (TextUtils.isEmpty(channelListStr)) {
                    channelListStr = "1-14";
                }
                break;
            case SoftApConfiguration.BAND_5GHZ:
                channelListStr = mContext.getResources().getString(
                        R.string.config_wifiSoftap5gChannelList);
                if (TextUtils.isEmpty(channelListStr)) {
                    channelListStr = "34-173";
                }
                break;
            case SoftApConfiguration.BAND_6GHZ:
                channelListStr = mContext.getResources().getString(
                        R.string.config_wifiSoftap6gChannelList);
                if (TextUtils.isEmpty(channelListStr)) {
                    channelListStr = "1-254";
                }
                break;
            default:
                return acsFrequencyRanges;
        }

        for (String channelRange : channelListStr.split(",")) {
            android.hardware.wifi.hostapd.V1_2.IHostapd.AcsFrequencyRange acsFrequencyRange =
                    new android.hardware.wifi.hostapd.V1_2.IHostapd.AcsFrequencyRange();
            try {
                if (channelRange.contains("-")) {
                    String[] channels  = channelRange.split("-");
                    if (channels.length != 2) {
                        Log.e(TAG, "Unrecognized channel range, length is " + channels.length);
                        continue;
                    }
                    int start = Integer.parseInt(channels[0].trim());
                    int end = Integer.parseInt(channels[1].trim());
                    if (start > end) {
                        Log.e(TAG, "Invalid channel range, from " + start + " to " + end);
                        continue;
                    }
                    acsFrequencyRange.start = ApConfigUtil.convertChannelToFrequency(start, band);
                    acsFrequencyRange.end = ApConfigUtil.convertChannelToFrequency(end, band);
                } else {
                    int channel = Integer.parseInt(channelRange.trim());
                    acsFrequencyRange.start = ApConfigUtil.convertChannelToFrequency(channel, band);
                    acsFrequencyRange.end = acsFrequencyRange.start;
                }
            } catch (NumberFormatException e) {
                // Ignore malformed value
                Log.e(TAG, "Malformed channel value detected: " + e);
                continue;
            }
            acsFrequencyRanges.add(acsFrequencyRange);
        }
        return acsFrequencyRanges;
    }

    /**
     * Returns false if Hostapd is null, and logs failure to call methodStr
     */
    private boolean checkHostapdAndLogFailure(String methodStr) {
        synchronized (mLock) {
            if (mIHostapd == null) {
                Log.e(TAG, "Can't call " + methodStr + ", IHostapd is null");
                return false;
            }
            return true;
        }
    }

    /**
     * Returns true if provided status code is SUCCESS, logs debug message and returns false
     * otherwise
     */
    private boolean checkStatusAndLogFailure(HostapdStatus status,
            String methodStr) {
        synchronized (mLock) {
            if (status.code != HostapdStatusCode.SUCCESS) {
                Log.e(TAG, "IHostapd." + methodStr + " failed: " + status.code
                        + ", " + status.debugMessage);
                return false;
            } else {
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "IHostapd." + methodStr + " succeeded");
                }
                return true;
            }
        }
    }

    /**
     * Returns true if provided status code is SUCCESS, logs debug message and returns false
     * otherwise
     */
    private boolean checkStatusAndLogFailure12(
            android.hardware.wifi.hostapd.V1_2.HostapdStatus status, String methodStr) {
        synchronized (mLock) {
            if (status.code != HostapdStatusCode.SUCCESS) {
                Log.e(TAG, "IHostapd." + methodStr + " failed: " + status.code
                        + ", " + status.debugMessage);
                return false;
            } else {
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "IHostapd." + methodStr + " succeeded");
                }
                return true;
            }
        }
    }

    private void handleRemoteException(RemoteException e, String methodStr) {
        synchronized (mLock) {
            hostapdServiceDiedHandler(mDeathRecipientCookie);
            Log.e(TAG, "IHostapd." + methodStr + " failed with exception", e);
        }
    }

    private class HostapdCallback extends
            android.hardware.wifi.hostapd.V1_1.IHostapdCallback.Stub {
        @Override
        public void onFailure(String ifaceName) {
            Log.w(TAG, "Failure on iface " + ifaceName);
            Runnable onFailureListener = mSoftApFailureListeners.get(ifaceName);
            if (onFailureListener != null) {
                onFailureListener.run();
            }
        }
    }

    /**
     * Map hal bandwidth to SoftApInfo.
     *
     * @param bandwidth The channel bandwidth of the AP which is defined in the HAL.
     * @return The channel bandwidth in the SoftApinfo.
     */
    @VisibleForTesting
    public int mapHalBandwidthToSoftApInfo(int bandwidth) {
        switch (bandwidth) {
            case Bandwidth.WIFI_BANDWIDTH_20_NOHT:
                return SoftApInfo.CHANNEL_WIDTH_20MHZ_NOHT;
            case Bandwidth.WIFI_BANDWIDTH_20:
                return SoftApInfo.CHANNEL_WIDTH_20MHZ;
            case Bandwidth.WIFI_BANDWIDTH_40:
                return SoftApInfo.CHANNEL_WIDTH_40MHZ;
            case Bandwidth.WIFI_BANDWIDTH_80:
                return SoftApInfo.CHANNEL_WIDTH_80MHZ;
            case Bandwidth.WIFI_BANDWIDTH_80P80:
                return SoftApInfo.CHANNEL_WIDTH_80MHZ_PLUS_MHZ;
            case Bandwidth.WIFI_BANDWIDTH_160:
                return SoftApInfo.CHANNEL_WIDTH_160MHZ;
            default:
                return SoftApInfo.CHANNEL_WIDTH_INVALID;
        }
    }

    /**
     * Map hal generation to wifi standard.
     *
     * @param generation The operation mode of the AP which is defined in HAL.
     * @return The wifi standard in the ScanResult.
     */
    @VisibleForTesting
    public int mapHalGenerationToWifiStandard(int generation) {
        switch (generation) {
            case Generation.WIFI_STANDARD_LEGACY:
                return ScanResult.WIFI_STANDARD_LEGACY;
            case Generation.WIFI_STANDARD_11N:
                return ScanResult.WIFI_STANDARD_11N;
            case Generation.WIFI_STANDARD_11AC:
                return ScanResult.WIFI_STANDARD_11AC;
            case Generation.WIFI_STANDARD_11AX:
                return ScanResult.WIFI_STANDARD_11AX;
            default:
                return ScanResult.WIFI_STANDARD_UNKNOWN;
        }
    }

    private class HostapdCallback_1_3 extends
            android.hardware.wifi.hostapd.V1_3.IHostapdCallback.Stub {
        @Override
        public void onFailure(String ifaceName) {
            Log.w(TAG, "Failure on iface " + ifaceName);
            Runnable onFailureListener = mSoftApFailureListeners.get(ifaceName);
            if (onFailureListener != null) {
                onFailureListener.run();
            }
        }

        @Override
        public void onApInstanceInfoChanged(String ifaceName, String apIfaceInstance,
                int frequency, int bandwidth, int generation, byte[] apIfaceInstanceMacAddress) {
            Log.d(TAG, "onApInstanceInfoChanged on " + ifaceName + " / " + apIfaceInstance);
            try {
                if (mSoftApEventListener != null) {
                    mSoftApEventListener.onInfoChanged(apIfaceInstance, frequency,
                            mapHalBandwidthToSoftApInfo(bandwidth),
                            mapHalGenerationToWifiStandard(generation),
                            MacAddress.fromBytes(apIfaceInstanceMacAddress));
                }
            } catch (IllegalArgumentException iae) {
                Log.e(TAG, " Invalid apIfaceInstanceMacAddress, " + iae);
            }
        }

        @Override
        public void onConnectedClientsChanged(String ifaceName, String apIfaceInstance,
                    byte[] clientAddress, boolean isConnected) {
            try {
                Log.d(TAG, "onConnectedClientsChanged on " + ifaceName + " / " + apIfaceInstance
                        + " and Mac is " + MacAddress.fromBytes(clientAddress).toString()
                        + " isConnected: " + isConnected);
                if (mSoftApEventListener != null) {
                    mSoftApEventListener.onConnectedClientsChanged(apIfaceInstance,
                            MacAddress.fromBytes(clientAddress), isConnected);
                }
            } catch (IllegalArgumentException iae) {
                Log.e(TAG, " Invalid clientAddress, " + iae);
            }
        }
    }

    /**
     * Set the debug log level for hostapd.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setLogLevel() {
        synchronized (mLock) {
            final String methodStr = "setDebugParams";
            if (!checkHostapdAndLogFailure(methodStr)) return false;
            if (isV1_2()) {
                try {
                    android.hardware.wifi.hostapd.V1_2.IHostapd iHostapdV1_2 =
                            getHostapdMockableV1_2();
                    if (iHostapdV1_2 == null) return false;
                    android.hardware.wifi.hostapd.V1_2.HostapdStatus status =
                            iHostapdV1_2.setDebugParams(mVerboseLoggingEnabled
                                    ? DebugLevel.DEBUG
                                    : DebugLevel.INFO);
                    return checkStatusAndLogFailure12(status, methodStr);
                } catch (RemoteException e) {
                    handleRemoteException(e, methodStr);
                }
            } else {
                Log.d(TAG, "HIDL doesn't support setDebugParams");
            }
            return false;
        }
    }
}
