/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.telecom;

import static android.Manifest.permission.CALL_PHONE;
import static android.Manifest.permission.CALL_PRIVILEGED;
import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.MODIFY_PHONE_STATE;
import static android.Manifest.permission.READ_PHONE_STATE;
import static android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE;
import static android.Manifest.permission.REGISTER_SIM_SUBSCRIPTION;
import static android.Manifest.permission.WRITE_SECURE_SETTINGS;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.telecom.Log;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.StatusHints;
import android.telecom.TelecomAnalytics;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.EventLog;

import com.android.internal.telecom.ITelecomService;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.telecom.components.UserCallIntentProcessorFactory;
import com.android.server.telecom.settings.BlockedNumbersActivity;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// TODO: Needed for move to system service: import com.android.internal.R;

/**
 * Implementation of the ITelecom interface.
 */
public class TelecomServiceImpl {

    public interface SubscriptionManagerAdapter {
        int getDefaultVoiceSubId();
    }

    static class SubscriptionManagerAdapterImpl implements SubscriptionManagerAdapter {
        @Override
        public int getDefaultVoiceSubId() {
            return SubscriptionManager.getDefaultVoiceSubscriptionId();
        }
    }

    public interface SettingsSecureAdapter {
        void putStringForUser(ContentResolver resolver, String name, String value, int userHandle);

        String getStringForUser(ContentResolver resolver, String name, int userHandle);
    }

    static class SettingsSecureAdapterImpl implements SettingsSecureAdapter {
        @Override
        public void putStringForUser(ContentResolver resolver, String name, String value,
            int userHandle) {
            Settings.Secure.putStringForUser(resolver, name, value, userHandle);
        }

        @Override
        public String getStringForUser(ContentResolver resolver, String name, int userHandle) {
            return Settings.Secure.getStringForUser(resolver, name, userHandle);
        }
    }

    private static final String TIME_LINE_ARG = "timeline";
    private static final int DEFAULT_VIDEO_STATE = -1;
    private static final String PERMISSION_HANDLE_CALL_INTENT =
            "android.permission.HANDLE_CALL_INTENT";

    private final ITelecomService.Stub mBinderImpl = new ITelecomService.Stub() {
        @Override
        public PhoneAccountHandle getDefaultOutgoingPhoneAccount(String uriScheme,
                String callingPackage) {
            try {
                Log.startSession("TSI.gDOPA");
                synchronized (mLock) {
                    if (!canReadPhoneState(callingPackage, "getDefaultOutgoingPhoneAccount")) {
                        return null;
                    }

                    final UserHandle callingUserHandle = Binder.getCallingUserHandle();
                    long token = Binder.clearCallingIdentity();
                    try {
                        return mPhoneAccountRegistrar
                                .getOutgoingPhoneAccountForScheme(uriScheme, callingUserHandle);
                    } catch (Exception e) {
                        Log.e(this, e, "getDefaultOutgoingPhoneAccount");
                        throw e;
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        @Override
        public PhoneAccountHandle getUserSelectedOutgoingPhoneAccount(String callingPackage) {
            synchronized (mLock) {
                try {
                    Log.startSession("TSI.gUSOPA");
                    if (!isDialerOrPrivileged(callingPackage, "getDefaultOutgoingPhoneAccount")) {
                        throw new SecurityException("Only the default dialer, or caller with "
                                + "READ_PRIVILEGED_PHONE_STATE can call this method.");
                    }
                    final UserHandle callingUserHandle = Binder.getCallingUserHandle();
                    return mPhoneAccountRegistrar.getUserSelectedOutgoingPhoneAccount(
                            callingUserHandle);
                } catch (Exception e) {
                    Log.e(this, e, "getUserSelectedOutgoingPhoneAccount");
                    throw e;
                } finally {
                    Log.endSession();
                }
            }
        }

        @Override
        public void setUserSelectedOutgoingPhoneAccount(PhoneAccountHandle accountHandle) {
            try {
                Log.startSession("TSI.sUSOPA");
                synchronized (mLock) {
                    enforceModifyPermission();
                    UserHandle callingUserHandle = Binder.getCallingUserHandle();
                    long token = Binder.clearCallingIdentity();
                    try {
                        mPhoneAccountRegistrar.setUserSelectedOutgoingPhoneAccount(
                                accountHandle, callingUserHandle);
                    } catch (Exception e) {
                        Log.e(this, e, "setUserSelectedOutgoingPhoneAccount");
                        throw e;
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        @Override
        public ParceledListSlice<PhoneAccountHandle> getCallCapablePhoneAccounts(
                boolean includeDisabledAccounts, String callingPackage) {
            try {
                Log.startSession("TSI.gCCPA");
                if (!canReadPhoneState(callingPackage, "getDefaultOutgoingPhoneAccount")) {
                    return ParceledListSlice.emptyList();
                }
                synchronized (mLock) {
                    final UserHandle callingUserHandle = Binder.getCallingUserHandle();
                    long token = Binder.clearCallingIdentity();
                    try {
                        return new ParceledListSlice<>(
                                mPhoneAccountRegistrar.getCallCapablePhoneAccounts(null,
                                includeDisabledAccounts, callingUserHandle));
                    } catch (Exception e) {
                        Log.e(this, e, "getCallCapablePhoneAccounts");
                        throw e;
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        @Override
        public ParceledListSlice<PhoneAccountHandle> getSelfManagedPhoneAccounts(
                String callingPackage) {
            try {
                Log.startSession("TSI.gSMPA");
                if (!canReadPhoneState(callingPackage, "Requires READ_PHONE_STATE permission.")) {
                    throw new SecurityException("Requires READ_PHONE_STATE permission.");
                }
                synchronized (mLock) {
                    final UserHandle callingUserHandle = Binder.getCallingUserHandle();
                    long token = Binder.clearCallingIdentity();
                    try {
                        return new ParceledListSlice<>(mPhoneAccountRegistrar
                                .getSelfManagedPhoneAccounts(callingUserHandle));
                    } catch (Exception e) {
                        Log.e(this, e, "getSelfManagedPhoneAccounts");
                        throw e;
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }


        @Override
        public ParceledListSlice<PhoneAccountHandle> getPhoneAccountsSupportingScheme(
                String uriScheme, String callingPackage) {
             try {
                Log.startSession("TSI.gPASS");
                try {
                    enforceModifyPermission(
                            "getPhoneAccountsSupportingScheme requires MODIFY_PHONE_STATE");
                } catch (SecurityException e) {
                    EventLog.writeEvent(0x534e4554, "62347125", Binder.getCallingUid(),
                            "getPhoneAccountsSupportingScheme: " + callingPackage);
                    return ParceledListSlice.emptyList();
                }

                synchronized (mLock) {
                    final UserHandle callingUserHandle = Binder.getCallingUserHandle();
                    long token = Binder.clearCallingIdentity();
                    try {
                        return new ParceledListSlice<>(mPhoneAccountRegistrar
                                .getCallCapablePhoneAccounts(uriScheme, false,
                                callingUserHandle));
                    } catch (Exception e) {
                        Log.e(this, e, "getPhoneAccountsSupportingScheme %s", uriScheme);
                        throw e;
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        @Override
        public ParceledListSlice<PhoneAccountHandle> getPhoneAccountsForPackage(
                String packageName) {
            //TODO: Deprecate this in S
            try {
                enforceCallingPackage(packageName);
            } catch (SecurityException se1) {
                EventLog.writeEvent(0x534e4554, "153995334", Binder.getCallingUid(),
                        "getPhoneAccountsForPackage: invalid calling package");
                throw se1;
            }

            try {
                enforcePermission(READ_PRIVILEGED_PHONE_STATE);
            } catch (SecurityException se2) {
                EventLog.writeEvent(0x534e4554, "153995334", Binder.getCallingUid(),
                        "getPhoneAccountsForPackage: no permission");
                throw se2;
            }

            synchronized (mLock) {
                final UserHandle callingUserHandle = Binder.getCallingUserHandle();
                long token = Binder.clearCallingIdentity();
                try {
                    Log.startSession("TSI.gPAFP");
                    return new ParceledListSlice<>(mPhoneAccountRegistrar
                            .getAllPhoneAccountHandlesForPackage(callingUserHandle, packageName));
                } catch (Exception e) {
                    Log.e(this, e, "getPhoneAccountsForPackage %s", packageName);
                    throw e;
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }
        }

        @Override
        public PhoneAccount getPhoneAccount(PhoneAccountHandle accountHandle) {
            synchronized (mLock) {
                final UserHandle callingUserHandle = Binder.getCallingUserHandle();
                long token = Binder.clearCallingIdentity();
                try {
                    Log.startSession("TSI.gPA");
                    // In ideal case, we should not resolve the handle across profiles. But given
                    // the fact that profile's call is handled by its parent user's in-call UI,
                    // parent user's in call UI need to be able to get phone account from the
                    // profile's phone account handle.
                    return mPhoneAccountRegistrar
                            .getPhoneAccount(accountHandle, callingUserHandle,
                            /* acrossProfiles */ true);
                } catch (Exception e) {
                    Log.e(this, e, "getPhoneAccount %s", accountHandle);
                    throw e;
                } finally {
                    Binder.restoreCallingIdentity(token);
                    Log.endSession();
                }
            }
        }

        @Override
        public int getAllPhoneAccountsCount() {
            try {
                Log.startSession("TSI.gAPAC");
                try {
                    enforceModifyPermission(
                            "getAllPhoneAccountsCount requires MODIFY_PHONE_STATE permission.");
                } catch (SecurityException e) {
                    EventLog.writeEvent(0x534e4554, "62347125", Binder.getCallingUid(),
                            "getAllPhoneAccountsCount");
                    throw e;
                }

                synchronized (mLock) {
                    try {
                        // This list is pre-filtered for the calling user.
                        return getAllPhoneAccounts().getList().size();
                    } catch (Exception e) {
                        Log.e(this, e, "getAllPhoneAccountsCount");
                        throw e;

                    }
                }
            } finally {
                Log.endSession();
            }
        }

        @Override
        public ParceledListSlice<PhoneAccount> getAllPhoneAccounts() {
            synchronized (mLock) {
                try {
                    Log.startSession("TSI.gAPA");
                    try {
                        enforceModifyPermission(
                                "getAllPhoneAccounts requires MODIFY_PHONE_STATE permission.");
                    } catch (SecurityException e) {
                        EventLog.writeEvent(0x534e4554, "62347125", Binder.getCallingUid(),
                                "getAllPhoneAccounts");
                        throw e;
                    }

                    final UserHandle callingUserHandle = Binder.getCallingUserHandle();
                    long token = Binder.clearCallingIdentity();
                    try {
                        return new ParceledListSlice<>(mPhoneAccountRegistrar
                                .getAllPhoneAccounts(callingUserHandle));
                    } catch (Exception e) {
                        Log.e(this, e, "getAllPhoneAccounts");
                        throw e;
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                } finally {
                    Log.endSession();
                }
            }
        }

        @Override
        public ParceledListSlice<PhoneAccountHandle> getAllPhoneAccountHandles() {
            try {
                Log.startSession("TSI.gAPAH");
                try {
                    enforceModifyPermission(
                            "getAllPhoneAccountHandles requires MODIFY_PHONE_STATE permission.");
                } catch (SecurityException e) {
                    EventLog.writeEvent(0x534e4554, "62347125", Binder.getCallingUid(),
                            "getAllPhoneAccountHandles");
                    throw e;
                }

                synchronized (mLock) {
                    final UserHandle callingUserHandle = Binder.getCallingUserHandle();
                    long token = Binder.clearCallingIdentity();
                    try {
                        return new ParceledListSlice<>(mPhoneAccountRegistrar
                                .getAllPhoneAccountHandles(callingUserHandle));
                    } catch (Exception e) {
                        Log.e(this, e, "getAllPhoneAccounts");
                        throw e;
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        @Override
        public PhoneAccountHandle getSimCallManager(int subId) {
            synchronized (mLock) {
                try {
                    Log.startSession("TSI.gSCM");
                    final int callingUid = Binder.getCallingUid();
                    final int user = UserHandle.getUserId(callingUid);
                    long token = Binder.clearCallingIdentity();
                    try {
                        if (user != ActivityManager.getCurrentUser()) {
                            enforceCrossUserPermission(callingUid);
                        }
                        return mPhoneAccountRegistrar.getSimCallManager(subId, UserHandle.of(user));
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                } catch (Exception e) {
                    Log.e(this, e, "getSimCallManager");
                    throw e;
                } finally {
                    Log.endSession();
                }
            }
        }

        @Override
        public PhoneAccountHandle getSimCallManagerForUser(int user) {
            synchronized (mLock) {
                try {
                    Log.startSession("TSI.gSCMFU");
                    final int callingUid = Binder.getCallingUid();
                    long token = Binder.clearCallingIdentity();
                    try {
                        if (user != ActivityManager.getCurrentUser()) {
                            enforceCrossUserPermission(callingUid);
                        }
                        return mPhoneAccountRegistrar.getSimCallManager(UserHandle.of(user));
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                } catch (Exception e) {
                    Log.e(this, e, "getSimCallManager");
                    throw e;
                } finally {
                    Log.endSession();
                }
            }
        }

        @Override
        public void registerPhoneAccount(PhoneAccount account) {
            try {
                Log.startSession("TSI.rPA");
                synchronized (mLock) {
                    if (!mContext.getApplicationContext().getResources().getBoolean(
                            com.android.internal.R.bool.config_voice_capable)) {
                        Log.w(this,
                                "registerPhoneAccount not allowed on non-voice capable device.");
                        return;
                    }
                    try {
                        enforcePhoneAccountModificationForPackage(
                                account.getAccountHandle().getComponentName().getPackageName());
                        if (account.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)) {
                            enforceRegisterSelfManaged();
                            if (account.hasCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER) ||
                                    account.hasCapabilities(
                                            PhoneAccount.CAPABILITY_CONNECTION_MANAGER) ||
                                    account.hasCapabilities(
                                            PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
                                throw new SecurityException("Self-managed ConnectionServices " +
                                        "cannot also be call capable, connection managers, or " +
                                        "SIM accounts.");
                            }

                            // For self-managed CS, the phone account registrar will override the
                            // label the user has set for the phone account.  This ensures the
                            // self-managed cs implementation can't spoof their app name.
                        }
                        if (account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
                            enforceRegisterSimSubscriptionPermission();
                        }
                        if (account.hasCapabilities(PhoneAccount.CAPABILITY_MULTI_USER)) {
                            enforceRegisterMultiUser();
                        }
                        Bundle extras = account.getExtras();
                        if (extras != null
                                && extras.getBoolean(PhoneAccount.EXTRA_SKIP_CALL_FILTERING)) {
                            enforceRegisterSkipCallFiltering();
                        }
                        final int callingUid = Binder.getCallingUid();
                        if (callingUid != Process.SHELL_UID) {
                            enforceUserHandleMatchesCaller(account.getAccountHandle());
                        }

                        if (TextUtils.isEmpty(account.getGroupId())
                                && mContext.checkCallingOrSelfPermission(MODIFY_PHONE_STATE)
                                != PackageManager.PERMISSION_GRANTED) {
                            Log.w(this, "registerPhoneAccount - attempt to set a"
                                    + " group from a non-system caller.");
                            // Not permitted to set group, so null it out.
                            account = new PhoneAccount.Builder(account)
                                    .setGroupId(null)
                                    .build();
                        }

                        // Validate the profile boundary of the given image URI.
                        validateAccountIconUserBoundary(account.getIcon());

                        final long token = Binder.clearCallingIdentity();
                        try {
                            mPhoneAccountRegistrar.registerPhoneAccount(account);
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    } catch (Exception e) {
                        Log.e(this, e, "registerPhoneAccount %s", account);
                        throw e;
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void unregisterPhoneAccount(PhoneAccountHandle accountHandle) {
            synchronized (mLock) {
                try {
                    Log.startSession("TSI.uPA");
                    enforcePhoneAccountModificationForPackage(
                            accountHandle.getComponentName().getPackageName());
                    enforceUserHandleMatchesCaller(accountHandle);
                    final long token = Binder.clearCallingIdentity();
                    try {
                        mPhoneAccountRegistrar.unregisterPhoneAccount(accountHandle);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                } catch (Exception e) {
                    Log.e(this, e, "unregisterPhoneAccount %s", accountHandle);
                    throw e;
                } finally {
                    Log.endSession();
                }
            }
        }

        @Override
        public void clearAccounts(String packageName) {
            synchronized (mLock) {
                try {
                    Log.startSession("TSI.cA");
                    enforcePhoneAccountModificationForPackage(packageName);
                    mPhoneAccountRegistrar
                            .clearAccounts(packageName, Binder.getCallingUserHandle());
                } catch (Exception e) {
                    Log.e(this, e, "clearAccounts %s", packageName);
                    throw e;
                } finally {
                    Log.endSession();
                }
            }
        }

        /**
         * @see android.telecom.TelecomManager#isVoiceMailNumber
         */
        @Override
        public boolean isVoiceMailNumber(PhoneAccountHandle accountHandle, String number,
                String callingPackage) {
            try {
                Log.startSession("TSI.iVMN");
                synchronized (mLock) {
                    if (!canReadPhoneState(callingPackage, "isVoiceMailNumber")) {
                        return false;
                    }
                    final UserHandle callingUserHandle = Binder.getCallingUserHandle();
                    if (!isPhoneAccountHandleVisibleToCallingUser(accountHandle,
                            callingUserHandle)) {
                        Log.d(this, "%s is not visible for the calling user [iVMN]", accountHandle);
                        return false;
                    }
                    long token = Binder.clearCallingIdentity();
                    try {
                        return mPhoneAccountRegistrar.isVoiceMailNumber(accountHandle, number);
                    } catch (Exception e) {
                        Log.e(this, e, "getSubscriptionIdForPhoneAccount");
                        throw e;
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#getVoiceMailNumber
         */
        @Override
        public String getVoiceMailNumber(PhoneAccountHandle accountHandle, String callingPackage) {
            try {
                Log.startSession("TSI.gVMN");
                synchronized (mLock) {
                    if (!canReadPhoneState(callingPackage, "getVoiceMailNumber")) {
                        return null;
                    }
                    try {
                        final UserHandle callingUserHandle = Binder.getCallingUserHandle();
                        if (!isPhoneAccountHandleVisibleToCallingUser(accountHandle,
                                callingUserHandle)) {
                            Log.d(this, "%s is not visible for the calling user [gVMN]",
                                    accountHandle);
                            return null;
                        }
                        int subId = mSubscriptionManagerAdapter.getDefaultVoiceSubId();
                        if (accountHandle != null) {
                            subId = mPhoneAccountRegistrar
                                    .getSubscriptionIdForPhoneAccount(accountHandle);
                        }
                        return getTelephonyManager().getVoiceMailNumber(subId);
                    } catch (Exception e) {
                        Log.e(this, e, "getSubscriptionIdForPhoneAccount");
                        throw e;
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#getLine1Number
         */
        @Override
        public String getLine1Number(PhoneAccountHandle accountHandle, String callingPackage) {
            try {
                Log.startSession("getL1N");
                if (!canReadPhoneState(callingPackage, "getLine1Number")) {
                    return null;
                }

                synchronized (mLock) {
                    final UserHandle callingUserHandle = Binder.getCallingUserHandle();
                    if (!isPhoneAccountHandleVisibleToCallingUser(accountHandle,
                            callingUserHandle)) {
                        Log.d(this, "%s is not visible for the calling user [gL1N]", accountHandle);
                        return null;
                    }

                    long token = Binder.clearCallingIdentity();
                    try {
                        int subId = mPhoneAccountRegistrar.getSubscriptionIdForPhoneAccount(
                                accountHandle);
                        return getTelephonyManager().getLine1Number(subId);
                    } catch (Exception e) {
                        Log.e(this, e, "getSubscriptionIdForPhoneAccount");
                        throw e;
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#silenceRinger
         */
        @Override
        public void silenceRinger(String callingPackage) {
            try {
                Log.startSession("TSI.sR");
                synchronized (mLock) {
                    enforcePermissionOrPrivilegedDialer(MODIFY_PHONE_STATE, callingPackage);

                    long token = Binder.clearCallingIdentity();
                    try {
                        Log.i(this, "Silence Ringer requested by %s", callingPackage);
                        mCallsManager.getCallAudioManager().silenceRingers();
                        mCallsManager.getInCallController().silenceRinger();
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#getDefaultPhoneApp
         * @deprecated - Use {@link android.telecom.TelecomManager#getDefaultDialerPackage()}
         *         instead.
         */
        @Override
        public ComponentName getDefaultPhoneApp() {
            try {
                Log.startSession("TSI.gDPA");
                // No need to synchronize
                Resources resources = mContext.getResources();
                return new ComponentName(
                        TelecomServiceImpl.getSystemDialerPackage(mContext),
                        resources.getString(R.string.dialer_default_class));
            } finally {
                Log.endSession();
            }
        }

        /**
         * @return the package name of the current user-selected default dialer. If no default
         *         has been selected, the package name of the system dialer is returned. If
         *         neither exists, then {@code null} is returned.
         * @see android.telecom.TelecomManager#getDefaultDialerPackage
         */
        @Override
        public String getDefaultDialerPackage() {
            try {
                Log.startSession("TSI.gDDP");
                final long token = Binder.clearCallingIdentity();
                try {
                    return mDefaultDialerCache.getDefaultDialerApplication(
                            ActivityManager.getCurrentUser());
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#getSystemDialerPackage
         */
        @Override
        public String getSystemDialerPackage() {
            try {
                Log.startSession("TSI.gSDP");
                return TelecomServiceImpl.getSystemDialerPackage(mContext);
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#isInCall
         */
        @Override
        public boolean isInCall(String callingPackage) {
            try {
                Log.startSession("TSI.iIC");
                if (!canReadPhoneState(callingPackage, "isInCall")) {
                    return false;
                }

                synchronized (mLock) {
                    return mCallsManager.hasOngoingCalls();
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#isInManagedCall
         */
        @Override
        public boolean isInManagedCall(String callingPackage) {
            try {
                Log.startSession("TSI.iIMC");
                if (!canReadPhoneState(callingPackage, "isInManagedCall")) {
                    throw new SecurityException("Only the default dialer or caller with " +
                            "READ_PHONE_STATE permission can use this method.");
                }

                synchronized (mLock) {
                    return mCallsManager.hasOngoingManagedCalls();
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#isRinging
         */
        @Override
        public boolean isRinging(String callingPackage) {
            try {
                Log.startSession("TSI.iR");
                if (!isPrivilegedDialerCalling(callingPackage)) {
                    try {
                        enforceModifyPermission(
                                "isRinging requires MODIFY_PHONE_STATE permission.");
                    } catch (SecurityException e) {
                        EventLog.writeEvent(0x534e4554, "62347125", "isRinging: " + callingPackage);
                        throw e;
                    }
                }

                synchronized (mLock) {
                    // Note: We are explicitly checking the calls telecom is tracking rather than
                    // relying on mCallsManager#getCallState(). Since getCallState() relies on the
                    // current state as tracked by PhoneStateBroadcaster, any failure to properly
                    // track the current call state there could result in the wrong ringing state
                    // being reported by this API.
                    return mCallsManager.hasRingingCall();
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see TelecomManager#getCallState
         */
        @Override
        public int getCallState() {
            try {
                Log.startSession("TSI.getCallState");
                synchronized (mLock) {
                    return mCallsManager.getCallState();
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#endCall
         */
        @Override
        public boolean endCall(String callingPackage) {
            try {
                Log.startSession("TSI.eC");
                synchronized (mLock) {
                    if (!enforceAnswerCallPermission(callingPackage, Binder.getCallingUid())) {
                        throw new SecurityException("requires ANSWER_PHONE_CALLS permission");
                    }

                    long token = Binder.clearCallingIdentity();
                    try {
                        return endCallInternal(callingPackage);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#acceptRingingCall
         */
        @Override
        public void acceptRingingCall(String packageName) {
            try {
                Log.startSession("TSI.aRC");
                synchronized (mLock) {
                    if (!enforceAnswerCallPermission(packageName, Binder.getCallingUid())) return;

                    long token = Binder.clearCallingIdentity();
                    try {
                        acceptRingingCallInternal(DEFAULT_VIDEO_STATE);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#acceptRingingCall(int)
         *
         */
        @Override
        public void acceptRingingCallWithVideoState(String packageName, int videoState) {
            try {
                Log.startSession("TSI.aRCWVS");
                synchronized (mLock) {
                    if (!enforceAnswerCallPermission(packageName, Binder.getCallingUid())) return;

                    long token = Binder.clearCallingIdentity();
                    try {
                        acceptRingingCallInternal(videoState);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#showInCallScreen
         */
        @Override
        public void showInCallScreen(boolean showDialpad, String callingPackage) {
            try {
                Log.startSession("TSI.sICS");
                if (!canReadPhoneState(callingPackage, "showInCallScreen")) {
                    return;
                }

                synchronized (mLock) {

                    long token = Binder.clearCallingIdentity();
                    try {
                        mCallsManager.getInCallController().bringToForeground(showDialpad);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#cancelMissedCallsNotification
         */
        @Override
        public void cancelMissedCallsNotification(String callingPackage) {
            try {
                Log.startSession("TSI.cMCN");
                synchronized (mLock) {
                    enforcePermissionOrPrivilegedDialer(MODIFY_PHONE_STATE, callingPackage);
                    UserHandle userHandle = Binder.getCallingUserHandle();
                    long token = Binder.clearCallingIdentity();
                    try {
                        mCallsManager.getMissedCallNotifier().clearMissedCalls(userHandle);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }
        /**
         * @see android.telecom.TelecomManager#handleMmi
         */
        @Override
        public boolean handlePinMmi(String dialString, String callingPackage) {
            try {
                Log.startSession("TSI.hPM");
                synchronized (mLock) {
                    enforcePermissionOrPrivilegedDialer(MODIFY_PHONE_STATE, callingPackage);

                    // Switch identity so that TelephonyManager checks Telecom's permissions
                    // instead.
                    long token = Binder.clearCallingIdentity();
                    boolean retval = false;
                    try {
                        retval = getTelephonyManager().handlePinMmi(dialString);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }

                    return retval;
                }
            }finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#handleMmi
         */
        @Override
        public boolean handlePinMmiForPhoneAccount(PhoneAccountHandle accountHandle,
                String dialString, String callingPackage) {
            try {
                Log.startSession("TSI.hPMFPA");
                synchronized (mLock) {
                    enforcePermissionOrPrivilegedDialer(MODIFY_PHONE_STATE, callingPackage);

                    UserHandle callingUserHandle = Binder.getCallingUserHandle();
                    if (!isPhoneAccountHandleVisibleToCallingUser(accountHandle,
                            callingUserHandle)) {
                        Log.d(this, "%s is not visible for the calling user [hMMI]", accountHandle);
                        return false;
                    }

                    // Switch identity so that TelephonyManager checks Telecom's permissions
                    // instead.
                    long token = Binder.clearCallingIdentity();
                    boolean retval = false;
                    try {
                        int subId = mPhoneAccountRegistrar.getSubscriptionIdForPhoneAccount(
                                accountHandle);
                        retval = getTelephonyManager().handlePinMmiForSubscriber(subId, dialString);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                    return retval;
                }
            }finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#getAdnUriForPhoneAccount
         */
        @Override
        public Uri getAdnUriForPhoneAccount(PhoneAccountHandle accountHandle,
                String callingPackage) {
            try {
                Log.startSession("TSI.aAUFPA");
                synchronized (mLock) {
                    enforcePermissionOrPrivilegedDialer(MODIFY_PHONE_STATE, callingPackage);
                    if (!isPhoneAccountHandleVisibleToCallingUser(accountHandle,
                            Binder.getCallingUserHandle())) {
                        Log.d(this, "%s is not visible for the calling user [gA4PA]",
                                accountHandle);
                        return null;
                    }
                    // Switch identity so that TelephonyManager checks Telecom's permissions
                    // instead.
                    long token = Binder.clearCallingIdentity();
                    String retval = "content://icc/adn/";
                    try {
                        long subId = mPhoneAccountRegistrar
                                .getSubscriptionIdForPhoneAccount(accountHandle);
                        retval = retval + "subId/" + subId;
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }

                    return Uri.parse(retval);
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#isTtySupported
         */
        @Override
        public boolean isTtySupported(String callingPackage) {
            try {
                Log.startSession("TSI.iTS");
                if (!canReadPhoneState(callingPackage, "isTtySupported")) {
                    throw new SecurityException("Only default dialer or an app with" +
                            "READ_PRIVILEGED_PHONE_STATE or READ_PHONE_STATE can call this api");
                }

                synchronized (mLock) {
                    return mCallsManager.isTtySupported();
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#getCurrentTtyMode
         */
        @Override
        public int getCurrentTtyMode(String callingPackage) {
            try {
                Log.startSession("TSI.gCTM");
                if (!canReadPhoneState(callingPackage, "getCurrentTtyMode")) {
                    return TelecomManager.TTY_MODE_OFF;
                }

                synchronized (mLock) {
                    return mCallsManager.getCurrentTtyMode();
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#addNewIncomingCall
         */
        @Override
        public void addNewIncomingCall(PhoneAccountHandle phoneAccountHandle, Bundle extras) {
            try {
                Log.startSession("TSI.aNIC");
                synchronized (mLock) {
                    Log.i(this, "Adding new incoming call with phoneAccountHandle %s",
                            phoneAccountHandle);
                    if (phoneAccountHandle != null &&
                            phoneAccountHandle.getComponentName() != null) {
                        if (isCallerSimCallManager(phoneAccountHandle)
                                && TelephonyUtil.isPstnComponentName(
                                        phoneAccountHandle.getComponentName())) {
                            Log.v(this, "Allowing call manager to add incoming call with PSTN" +
                                    " handle");
                        } else {
                            mAppOpsManager.checkPackage(
                                    Binder.getCallingUid(),
                                    phoneAccountHandle.getComponentName().getPackageName());
                            // Make sure it doesn't cross the UserHandle boundary
                            enforceUserHandleMatchesCaller(phoneAccountHandle);
                            enforcePhoneAccountIsRegisteredEnabled(phoneAccountHandle,
                                    Binder.getCallingUserHandle());
                            if (isSelfManagedConnectionService(phoneAccountHandle)) {
                                // Self-managed phone account, ensure it has MANAGE_OWN_CALLS.
                                mContext.enforceCallingOrSelfPermission(
                                        android.Manifest.permission.MANAGE_OWN_CALLS,
                                        "Self-managed phone accounts must have MANAGE_OWN_CALLS " +
                                                "permission.");

                                // Self-managed ConnectionServices can ONLY add new incoming calls
                                // using their own PhoneAccounts.  The checkPackage(..) app opps
                                // check above ensures this.
                            }
                        }
                        long token = Binder.clearCallingIdentity();
                        try {
                            Intent intent = new Intent(TelecomManager.ACTION_INCOMING_CALL);
                            intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                                    phoneAccountHandle);
                            intent.putExtra(CallIntentProcessor.KEY_IS_INCOMING_CALL, true);
                            if (extras != null) {
                                extras.setDefusable(true);
                                intent.putExtra(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, extras);
                            }
                            mCallIntentProcessorAdapter.processIncomingCallIntent(
                                    mCallsManager, intent);
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    } else {
                        Log.w(this, "Null phoneAccountHandle. Ignoring request to add new" +
                                " incoming call");
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#acceptHandover
         */
        @Override
        public void acceptHandover(Uri srcAddr, int videoState, PhoneAccountHandle destAcct) {
            try {
                Log.startSession("TSI.aHO");
                synchronized (mLock) {
                    Log.i(this, "acceptHandover; srcAddr=%s, videoState=%s, dest=%s",
                            Log.pii(srcAddr), VideoProfile.videoStateToString(videoState),
                            destAcct);

                    if (destAcct != null && destAcct.getComponentName() != null) {
                        mAppOpsManager.checkPackage(
                                Binder.getCallingUid(),
                                destAcct.getComponentName().getPackageName());
                        enforceUserHandleMatchesCaller(destAcct);
                        enforcePhoneAccountIsRegisteredEnabled(destAcct,
                                Binder.getCallingUserHandle());
                        if (isSelfManagedConnectionService(destAcct)) {
                            // Self-managed phone account, ensure it has MANAGE_OWN_CALLS.
                            mContext.enforceCallingOrSelfPermission(
                                    android.Manifest.permission.MANAGE_OWN_CALLS,
                                    "Self-managed phone accounts must have MANAGE_OWN_CALLS " +
                                            "permission.");
                        }
                        if (!enforceAcceptHandoverPermission(
                                destAcct.getComponentName().getPackageName(),
                                Binder.getCallingUid())) {
                            throw new SecurityException("App must be granted runtime "
                                    + "ACCEPT_HANDOVER permission.");
                        }

                        long token = Binder.clearCallingIdentity();
                        try {
                            mCallsManager.acceptHandover(srcAddr, videoState, destAcct);
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    } else {
                        Log.w(this, "Null phoneAccountHandle. Ignoring request " +
                                "to handover the call");
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#addNewUnknownCall
         */
        @Override
        public void addNewUnknownCall(PhoneAccountHandle phoneAccountHandle, Bundle extras) {
            try {
                Log.startSession("TSI.aNUC");
                try {
                    enforceModifyPermission(
                            "addNewUnknownCall requires MODIFY_PHONE_STATE permission.");
                } catch (SecurityException e) {
                    EventLog.writeEvent(0x534e4554, "62347125", Binder.getCallingUid(),
                            "addNewUnknownCall");
                    throw e;
                }

                synchronized (mLock) {
                    if (phoneAccountHandle != null &&
                            phoneAccountHandle.getComponentName() != null) {
                        mAppOpsManager.checkPackage(
                                Binder.getCallingUid(),
                                phoneAccountHandle.getComponentName().getPackageName());

                        // Make sure it doesn't cross the UserHandle boundary
                        enforceUserHandleMatchesCaller(phoneAccountHandle);
                        enforcePhoneAccountIsRegisteredEnabled(phoneAccountHandle,
                                Binder.getCallingUserHandle());
                        long token = Binder.clearCallingIdentity();

                        try {
                            Intent intent = new Intent(TelecomManager.ACTION_NEW_UNKNOWN_CALL);
                            if (extras != null) {
                                extras.setDefusable(true);
                                intent.putExtras(extras);
                            }
                            intent.putExtra(CallIntentProcessor.KEY_IS_UNKNOWN_CALL, true);
                            intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                                    phoneAccountHandle);
                            mCallIntentProcessorAdapter.processUnknownCallIntent(mCallsManager, intent);
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    } else {
                        Log.i(this,
                                "Null phoneAccountHandle or not initiated by Telephony. " +
                                        "Ignoring request to add new unknown call.");
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#placeCall
         */
        @Override
        public void placeCall(Uri handle, Bundle extras, String callingPackage) {
            try {
                Log.startSession("TSI.pC");
                enforceCallingPackage(callingPackage);

                PhoneAccountHandle phoneAccountHandle = null;
                if (extras != null) {
                    phoneAccountHandle = extras.getParcelable(
                            TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE);
                    if (extras.containsKey(TelecomManager.EXTRA_IS_HANDOVER)) {
                        // This extra is for Telecom use only so should never be passed in.
                        extras.remove(TelecomManager.EXTRA_IS_HANDOVER);
                    }
                }
                boolean isSelfManaged = phoneAccountHandle != null &&
                        isSelfManagedConnectionService(phoneAccountHandle);
                if (isSelfManaged) {
                    mContext.enforceCallingOrSelfPermission(Manifest.permission.MANAGE_OWN_CALLS,
                            "Self-managed ConnectionServices require MANAGE_OWN_CALLS permission.");

                    if (!callingPackage.equals(
                            phoneAccountHandle.getComponentName().getPackageName())
                            && !canCallPhone(callingPackage,
                            "CALL_PHONE permission required to place calls.")) {
                        // The caller is not allowed to place calls, so we want to ensure that it
                        // can only place calls through itself.
                        throw new SecurityException("Self-managed ConnectionServices can only "
                                + "place calls through their own ConnectionService.");
                    }
                } else if (!canCallPhone(callingPackage, "placeCall")) {
                    throw new SecurityException("Package " + callingPackage
                            + " is not allowed to place phone calls");
                }

                // Note: we can still get here for the default/system dialer, even if the Phone
                // permission is turned off. This is because the default/system dialer is always
                // allowed to attempt to place a call (regardless of permission state), in case
                // it turns out to be an emergency call. If the permission is denied and the
                // call is being made to a non-emergency number, the call will be denied later on
                // by {@link UserCallIntentProcessor}.

                final boolean hasCallAppOp = mAppOpsManager.noteOp(AppOpsManager.OP_CALL_PHONE,
                        Binder.getCallingUid(), callingPackage) == AppOpsManager.MODE_ALLOWED;

                final boolean hasCallPermission = mContext.checkCallingPermission(CALL_PHONE) ==
                        PackageManager.PERMISSION_GRANTED;
                // The Emergency Dialer has call privileged permission and uses this to place
                // emergency calls.  We ensure permission checks in
                // NewOutgoingCallIntentBroadcaster#process pass by sending this to
                // Telecom as an ACTION_CALL_PRIVILEGED intent (which makes sense since the
                // com.android.phone process has that permission).
                final boolean hasCallPrivilegedPermission = mContext.checkCallingPermission(
                        CALL_PRIVILEGED) == PackageManager.PERMISSION_GRANTED;

                synchronized (mLock) {
                    final UserHandle userHandle = Binder.getCallingUserHandle();
                    long token = Binder.clearCallingIdentity();
                    try {
                        final Intent intent = new Intent(hasCallPrivilegedPermission ?
                                Intent.ACTION_CALL_PRIVILEGED : Intent.ACTION_CALL, handle);
                        if (extras != null) {
                            extras.setDefusable(true);
                            intent.putExtras(extras);
                        }
                        mUserCallIntentProcessorFactory.create(mContext, userHandle)
                                .processIntent(
                                        intent, callingPackage, isSelfManaged ||
                                                (hasCallAppOp && hasCallPermission),
                                        true /* isLocalInvocation */);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#enablePhoneAccount
         */
        @Override
        public boolean enablePhoneAccount(PhoneAccountHandle accountHandle, boolean isEnabled) {
            try {
                Log.startSession("TSI.ePA");
                enforceModifyPermission();
                synchronized (mLock) {
                    long token = Binder.clearCallingIdentity();
                    try {
                        // enable/disable phone account
                        return mPhoneAccountRegistrar.enablePhoneAccount(accountHandle, isEnabled);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        @Override
        public boolean setDefaultDialer(String packageName) {
            try {
                Log.startSession("TSI.sDD");
                enforcePermission(MODIFY_PHONE_STATE);
                enforcePermission(WRITE_SECURE_SETTINGS);
                synchronized (mLock) {
                    long token = Binder.clearCallingIdentity();
                    try {
                        return mDefaultDialerCache.setDefaultDialer(packageName,
                                ActivityManager.getCurrentUser());
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        @Override
        public TelecomAnalytics dumpCallAnalytics() {
            try {
                Log.startSession("TSI.dCA");
                enforcePermission(DUMP);
                return Analytics.dumpToParcelableAnalytics();
            } finally {
                Log.endSession();
            }
        }

        /**
         * Dumps the current state of the TelecomService.  Used when generating problem reports.
         *
         * @param fd The file descriptor.
         * @param writer The print writer to dump the state to.
         * @param args Optional dump arguments.
         */
        @Override
        protected void dump(FileDescriptor fd, final PrintWriter writer, String[] args) {
            if (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                writer.println("Permission Denial: can't dump TelecomService " +
                        "from from pid=" + Binder.getCallingPid() + ", uid=" +
                        Binder.getCallingUid());
                return;
            }


            if (args.length > 0 && Analytics.ANALYTICS_DUMPSYS_ARG.equals(args[0])) {
                Binder.withCleanCallingIdentity(() ->
                        Analytics.dumpToEncodedProto(mContext, writer, args));
                return;
            }

            boolean isTimeLineView = (args.length > 0 && TIME_LINE_ARG.equalsIgnoreCase(args[0]));

            final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
            if (mCallsManager != null) {
                pw.println("CallsManager: ");
                pw.increaseIndent();
                mCallsManager.dump(pw);
                pw.decreaseIndent();

                pw.println("PhoneAccountRegistrar: ");
                pw.increaseIndent();
                mPhoneAccountRegistrar.dump(pw);
                pw.decreaseIndent();

                pw.println("Analytics:");
                pw.increaseIndent();
                Analytics.dump(pw);
                pw.decreaseIndent();
            }
            if (isTimeLineView) {
                Log.dumpEventsTimeline(pw);
            } else {
                Log.dumpEvents(pw);
            }
        }

        /**
         * @see android.telecom.TelecomManager#createManageBlockedNumbersIntent
         */
        @Override
        public Intent createManageBlockedNumbersIntent() {
            return BlockedNumbersActivity.getIntentForStartingActivity();
        }

        /**
         * @see android.telecom.TelecomManager#isIncomingCallPermitted(PhoneAccountHandle)
         */
        @Override
        public boolean isIncomingCallPermitted(PhoneAccountHandle phoneAccountHandle) {
            try {
                Log.startSession("TSI.iICP");
                enforcePermission(android.Manifest.permission.MANAGE_OWN_CALLS);
                synchronized (mLock) {
                    long token = Binder.clearCallingIdentity();
                    try {
                        return mCallsManager.isIncomingCallPermitted(phoneAccountHandle);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * @see android.telecom.TelecomManager#isOutgoingCallPermitted(PhoneAccountHandle)
         */
        @Override
        public boolean isOutgoingCallPermitted(PhoneAccountHandle phoneAccountHandle) {
            try {
                Log.startSession("TSI.iOCP");
                enforcePermission(android.Manifest.permission.MANAGE_OWN_CALLS);
                synchronized (mLock) {
                    long token = Binder.clearCallingIdentity();
                    try {
                        return mCallsManager.isOutgoingCallPermitted(phoneAccountHandle);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * Blocks until all Telecom handlers have completed their current work.
         *
         * See {@link com.android.commands.telecom.Telecom}.
         */
        @Override
        public void waitOnHandlers() {
            try {
                Log.startSession("TSI.wOH");
                enforceModifyPermission();
                synchronized (mLock) {
                    long token = Binder.clearCallingIdentity();
                    try {
                        Log.i(this, "waitOnHandlers");
                        mCallsManager.waitOnHandlers();
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * See {@link TelecomManager#isInEmergencyCall()}
         */
        @Override
        public boolean isInEmergencyCall() {
            try {
                Log.startSession("TSI.iIEC");
                enforceModifyPermission();
                synchronized (mLock) {
                    long token = Binder.clearCallingIdentity();
                    try {
                        boolean isInEmergencyCall = mCallsManager.isInEmergencyCall();
                        Log.i(this, "isInEmergencyCall: %b", isInEmergencyCall);
                        return isInEmergencyCall;
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        /**
         * See {@link TelecomManager#handleCallIntent(Intent)} ()}
         */
        @Override
        public void handleCallIntent(Intent intent) {
            try {
                Log.startSession("TSI.hCI");
                synchronized (mLock) {
                    mContext.enforceCallingOrSelfPermission(PERMISSION_HANDLE_CALL_INTENT,
                            "handleCallIntent is for internal use only.");

                    long token = Binder.clearCallingIdentity();
                    try {
                        Log.i(this, "handleCallIntent: handling call intent");
                        mCallIntentProcessorAdapter.processOutgoingCallIntent(mContext,
                                mCallsManager, intent, null /* callingPackage */);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void setTestDefaultCallRedirectionApp(String packageName) {
            try {
                Log.startSession("TSI.sTDCRA");
                enforceModifyPermission();
                if (!Build.IS_USERDEBUG) {
                    throw new SecurityException("Test-only API.");
                }
                synchronized (mLock) {
                    long token = Binder.clearCallingIdentity();
                    try {
                        mCallsManager.getRoleManagerAdapter().setTestDefaultCallRedirectionApp(
                                packageName);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void setTestDefaultCallScreeningApp(String packageName) {
            try {
                Log.startSession("TSI.sTDCSA");
                enforceModifyPermission();
                if (!Build.IS_USERDEBUG) {
                    throw new SecurityException("Test-only API.");
                }
                synchronized (mLock) {
                    long token = Binder.clearCallingIdentity();
                    try {
                        mCallsManager.getRoleManagerAdapter().setTestDefaultCallScreeningApp(
                                packageName);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void addOrRemoveTestCallCompanionApp(String packageName, boolean isAdded) {
            try {
                Log.startSession("TSI.aORTCCA");
                enforceModifyPermission();
                if (!Build.IS_USERDEBUG) {
                    throw new SecurityException("Test-only API.");
                }
                synchronized (mLock) {
                    long token = Binder.clearCallingIdentity();
                    try {
                        mCallsManager.getRoleManagerAdapter().addOrRemoveTestCallCompanionApp(
                                packageName, isAdded);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void setTestAutoModeApp(String packageName) {
            try {
                Log.startSession("TSI.sTAMA");
                enforceModifyPermission();
                if (!Build.IS_USERDEBUG) {
                    throw new SecurityException("Test-only API.");
                }
                synchronized (mLock) {
                    long token = Binder.clearCallingIdentity();
                    try {
                        mCallsManager.getRoleManagerAdapter().setTestAutoModeApp(packageName);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void setTestPhoneAcctSuggestionComponent(String flattenedComponentName) {
            try {
                Log.startSession("TSI.sPASA");
                enforceModifyPermission();
                if (Binder.getCallingUid() != Process.SHELL_UID
                        && Binder.getCallingUid() != Process.ROOT_UID) {
                    throw new SecurityException("Shell-only API.");
                }
                synchronized (mLock) {
                    PhoneAccountSuggestionHelper.setOverrideServiceName(flattenedComponentName);
                }
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void setTestDefaultDialer(String packageName) {
            try {
                Log.startSession("TSI.sTDD");
                enforceModifyPermission();
                if (Binder.getCallingUid() != Process.SHELL_UID
                        && Binder.getCallingUid() != Process.ROOT_UID) {
                    throw new SecurityException("Shell-only API.");
                }
                synchronized (mLock) {
                    long token = Binder.clearCallingIdentity();
                    try {
                        mCallsManager.getRoleManagerAdapter().setTestDefaultDialer(packageName);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } finally {
                Log.endSession();
            }
        }
    };

    /**
     * @return whether to return early without doing the action/throwing
     * @throws SecurityException same as {@link Context#enforceCallingOrSelfPermission}
     */
    private boolean enforceAnswerCallPermission(String packageName, int uid) {
        try {
            enforceModifyPermission();
        } catch (SecurityException e) {
            final String permission = Manifest.permission.ANSWER_PHONE_CALLS;
            enforcePermission(permission);

            final int opCode = AppOpsManager.permissionToOpCode(permission);
            if (opCode != AppOpsManager.OP_NONE
                    && mAppOpsManager.checkOp(opCode, uid, packageName)
                        != AppOpsManager.MODE_ALLOWED) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return {@code true} if the app has the handover permission and has received runtime
     * permission to perform that operation, {@code false}.
     * @throws SecurityException same as {@link Context#enforceCallingOrSelfPermission}
     */
    private boolean enforceAcceptHandoverPermission(String packageName, int uid) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCEPT_HANDOVER,
                "App requires ACCEPT_HANDOVER permission to accept handovers.");

        final int opCode = AppOpsManager.permissionToOpCode(Manifest.permission.ACCEPT_HANDOVER);
        if (opCode != AppOpsManager.OP_ACCEPT_HANDOVER || (
                mAppOpsManager.checkOp(opCode, uid, packageName)
                        != AppOpsManager.MODE_ALLOWED)) {
            return false;
        }
        return true;
    }

    private Context mContext;
    private AppOpsManager mAppOpsManager;
    private PackageManager mPackageManager;
    private CallsManager mCallsManager;
    private final PhoneAccountRegistrar mPhoneAccountRegistrar;
    private final CallIntentProcessor.Adapter mCallIntentProcessorAdapter;
    private final UserCallIntentProcessorFactory mUserCallIntentProcessorFactory;
    private final DefaultDialerCache mDefaultDialerCache;
    private final SubscriptionManagerAdapter mSubscriptionManagerAdapter;
    private final SettingsSecureAdapter mSettingsSecureAdapter;
    private final TelecomSystem.SyncRoot mLock;

    public TelecomServiceImpl(
            Context context,
            CallsManager callsManager,
            PhoneAccountRegistrar phoneAccountRegistrar,
            CallIntentProcessor.Adapter callIntentProcessorAdapter,
            UserCallIntentProcessorFactory userCallIntentProcessorFactory,
            DefaultDialerCache defaultDialerCache,
            SubscriptionManagerAdapter subscriptionManagerAdapter,
            SettingsSecureAdapter settingsSecureAdapter,
            TelecomSystem.SyncRoot lock) {
        mContext = context;
        mAppOpsManager = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);

        mPackageManager = mContext.getPackageManager();

        mCallsManager = callsManager;
        mLock = lock;
        mPhoneAccountRegistrar = phoneAccountRegistrar;
        mUserCallIntentProcessorFactory = userCallIntentProcessorFactory;
        mDefaultDialerCache = defaultDialerCache;
        mCallIntentProcessorAdapter = callIntentProcessorAdapter;
        mSubscriptionManagerAdapter = subscriptionManagerAdapter;
        mSettingsSecureAdapter = settingsSecureAdapter;

        mDefaultDialerCache.observeDefaultDialerApplication(mContext.getMainExecutor(), userId -> {
            String defaultDialer = mDefaultDialerCache.getDefaultDialerApplication(userId);
            if (defaultDialer == null) {
                // We are replacing the dialer, just wait for the upcoming callback.
                return;
            }
            final Intent intent = new Intent(TelecomManager.ACTION_DEFAULT_DIALER_CHANGED)
                    .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                            defaultDialer);
            mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
        });
    }

    public static String getSystemDialerPackage(Context context) {
        return context.getResources().getString(com.android.internal.R.string.config_defaultDialer);
    }

    public ITelecomService.Stub getBinder() {
        return mBinderImpl;
    }

    //
    // Supporting methods for the ITelecomService interface implementation.
    //

    private boolean isPhoneAccountHandleVisibleToCallingUser(
            PhoneAccountHandle phoneAccountUserHandle, UserHandle callingUser) {
        return mPhoneAccountRegistrar.getPhoneAccount(phoneAccountUserHandle, callingUser) != null;
    }

    private boolean isCallerSystemApp() {
        int uid = Binder.getCallingUid();
        String[] packages = mPackageManager.getPackagesForUid(uid);
        for (String packageName : packages) {
            if (isPackageSystemApp(packageName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPackageSystemApp(String packageName) {
        try {
            ApplicationInfo applicationInfo = mPackageManager.getApplicationInfo(packageName,
                    PackageManager.GET_META_DATA);
            if ((applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                return true;
            }
        } catch (PackageManager.NameNotFoundException e) {
        }
        return false;
    }

    private void acceptRingingCallInternal(int videoState) {
        Call call = mCallsManager.getFirstCallWithState(CallState.RINGING);
        if (call != null) {
            if (videoState == DEFAULT_VIDEO_STATE || !isValidAcceptVideoState(videoState)) {
                videoState = call.getVideoState();
            }
            call.answer(videoState);
        }
    }

    private boolean endCallInternal(String callingPackage) {
        // Always operate on the foreground call if one exists, otherwise get the first call in
        // priority order by call-state.
        Call call = mCallsManager.getForegroundCall();
        if (call == null) {
            call = mCallsManager.getFirstCallWithState(
                    CallState.ACTIVE,
                    CallState.DIALING,
                    CallState.PULLING,
                    CallState.RINGING,
                    CallState.ON_HOLD);
        }

        if (call != null) {
            if (call.isEmergencyCall()) {
                android.util.EventLog.writeEvent(0x534e4554, "132438333", -1, "");
                return false;
            }

            if (call.getState() == CallState.RINGING) {
                call.reject(false /* rejectWithMessage */, null, callingPackage);
            } else {
                call.disconnect(0 /* disconnectionTimeout */, callingPackage);
            }
            return true;
        }

        return false;
    }

    // Enforce that the PhoneAccountHandle being passed in is both registered to the current user
    // and enabled.
    private void enforcePhoneAccountIsRegisteredEnabled(PhoneAccountHandle phoneAccountHandle,
                                                        UserHandle callingUserHandle) {
        PhoneAccount phoneAccount = mPhoneAccountRegistrar.getPhoneAccount(phoneAccountHandle,
                callingUserHandle);
        if(phoneAccount == null) {
            EventLog.writeEvent(0x534e4554, "26864502", Binder.getCallingUid(), "R");
            throw new SecurityException("This PhoneAccountHandle is not registered for this user!");
        }
        if(!phoneAccount.isEnabled()) {
            EventLog.writeEvent(0x534e4554, "26864502", Binder.getCallingUid(), "E");
            throw new SecurityException("This PhoneAccountHandle is not enabled for this user!");
        }
    }

    private void enforcePhoneAccountModificationForPackage(String packageName) {
        // TODO: Use a new telecomm permission for this instead of reusing modify.

        int result = mContext.checkCallingOrSelfPermission(MODIFY_PHONE_STATE);

        // Callers with MODIFY_PHONE_STATE can use the PhoneAccount mechanism to implement
        // built-in behavior even when PhoneAccounts are not exposed as a third-part API. They
        // may also modify PhoneAccounts on behalf of any 'packageName'.

        if (result != PackageManager.PERMISSION_GRANTED) {
            // Other callers are only allowed to modify PhoneAccounts if the relevant system
            // feature is enabled ...
            enforceConnectionServiceFeature();
            // ... and the PhoneAccounts they refer to are for their own package.
            enforceCallingPackage(packageName);
        }
    }

    private void enforcePermissionOrPrivilegedDialer(String permission, String packageName) {
        if (!isPrivilegedDialerCalling(packageName)) {
            try {
                enforcePermission(permission);
            } catch (SecurityException e) {
                Log.e(this, e, "Caller must be the default or system dialer, or have the permission"
                        + " %s to perform this operation.", permission);
                throw e;
            }
        }
    }

    private void enforceCallingPackage(String packageName) {
        mAppOpsManager.checkPackage(Binder.getCallingUid(), packageName);
    }

    private void enforceConnectionServiceFeature() {
        enforceFeature(PackageManager.FEATURE_CONNECTION_SERVICE);
    }

    private void enforceRegisterSimSubscriptionPermission() {
        enforcePermission(REGISTER_SIM_SUBSCRIPTION);
    }

    private void enforceModifyPermission() {
        enforcePermission(MODIFY_PHONE_STATE);
    }

    private void enforceModifyPermission(String message) {
        mContext.enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, message);
    }

    private void enforcePermission(String permission) {
        mContext.enforceCallingOrSelfPermission(permission, null);
    }

    private void enforceRegisterSelfManaged() {
        mContext.enforceCallingPermission(android.Manifest.permission.MANAGE_OWN_CALLS, null);
    }

    private void enforceRegisterMultiUser() {
        if (!isCallerSystemApp()) {
            throw new SecurityException("CAPABILITY_MULTI_USER is only available to system apps.");
        }
    }

    private void enforceRegisterSkipCallFiltering() {
        if (!isCallerSystemApp()) {
            throw new SecurityException(
                "EXTRA_SKIP_CALL_FILTERING is only available to system apps.");
        }
    }

    private void enforceUserHandleMatchesCaller(PhoneAccountHandle accountHandle) {
        if (!Binder.getCallingUserHandle().equals(accountHandle.getUserHandle())) {
            throw new SecurityException("Calling UserHandle does not match PhoneAccountHandle's");
        }
    }

    private void enforceCrossUserPermission(int callingUid) {
        if (callingUid != Process.SYSTEM_UID && callingUid != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, "Must be system or have"
                            + " INTERACT_ACROSS_USERS_FULL permission");
        }
    }

    private void enforceFeature(String feature) {
        PackageManager pm = mContext.getPackageManager();
        if (!pm.hasSystemFeature(feature)) {
            throw new UnsupportedOperationException(
                    "System does not support feature " + feature);
        }
    }

    private boolean canReadPhoneState(String callingPackage, String message) {
        // The system/default dialer can always read phone state - so that emergency calls will
        // still work.
        if (isPrivilegedDialerCalling(callingPackage)) {
            return true;
        }

        try {
            mContext.enforceCallingOrSelfPermission(READ_PRIVILEGED_PHONE_STATE, message);
            // SKIP checking run-time OP_READ_PHONE_STATE since caller or self has PRIVILEGED
            // permission
            return true;
        } catch (SecurityException e) {
            // Accessing phone state is gated by a special permission.
            mContext.enforceCallingOrSelfPermission(READ_PHONE_STATE, message);

            // Some apps that have the permission can be restricted via app ops.
            return mAppOpsManager.noteOp(AppOpsManager.OP_READ_PHONE_STATE,
                    Binder.getCallingUid(), callingPackage) == AppOpsManager.MODE_ALLOWED;
        }
    }

    private boolean isDialerOrPrivileged(String callingPackage, String message) {
        // The system/default dialer can always read phone state - so that emergency calls will
        // still work.
        if (isPrivilegedDialerCalling(callingPackage)) {
            return true;
        }

        mContext.enforceCallingOrSelfPermission(READ_PRIVILEGED_PHONE_STATE, message);
        // SKIP checking run-time OP_READ_PHONE_STATE since caller or self has PRIVILEGED
        // permission
        return true;
    }

    private boolean isSelfManagedConnectionService(PhoneAccountHandle phoneAccountHandle) {
        if (phoneAccountHandle != null) {
                PhoneAccount phoneAccount = mPhoneAccountRegistrar.getPhoneAccountUnchecked(
                        phoneAccountHandle);
                return phoneAccount != null && phoneAccount.isSelfManaged();
        }
        return false;
    }

    private boolean canCallPhone(String callingPackage, String message) {
        // The system/default dialer can always read phone state - so that emergency calls will
        // still work.
        if (isPrivilegedDialerCalling(callingPackage)) {
            return true;
        }

        // Accessing phone state is gated by a special permission.
        mContext.enforceCallingOrSelfPermission(CALL_PHONE, message);

        // Some apps that have the permission can be restricted via app ops.
        return mAppOpsManager.noteOp(AppOpsManager.OP_CALL_PHONE,
                Binder.getCallingUid(), callingPackage) == AppOpsManager.MODE_ALLOWED;
    }

    private boolean isCallerSimCallManager(PhoneAccountHandle targetPhoneAccount) {
        long token = Binder.clearCallingIdentity();
        PhoneAccountHandle accountHandle = null;
        try {
            accountHandle = mPhoneAccountRegistrar.getSimCallManagerFromHandle(targetPhoneAccount,
                    mCallsManager.getCurrentUserHandle());
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        if (accountHandle != null) {
            try {
                mAppOpsManager.checkPackage(
                        Binder.getCallingUid(), accountHandle.getComponentName().getPackageName());
                return true;
            } catch (SecurityException e) {
            }
        }
        return false;
    }

    private boolean isPrivilegedDialerCalling(String callingPackage) {
        mAppOpsManager.checkPackage(Binder.getCallingUid(), callingPackage);

        // Note: Important to clear the calling identity since the code below calls into RoleManager
        // to check who holds the dialer role, and that requires MANAGE_ROLE_HOLDERS permission
        // which is a system permission.
        long token = Binder.clearCallingIdentity();
        try {
            return mDefaultDialerCache.isDefaultOrSystemDialer(
                    callingPackage, Binder.getCallingUserHandle().getIdentifier());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private TelephonyManager getTelephonyManager() {
        return (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
    }

    /**
     * Determines if a video state is valid for accepting an incoming call.
     * For the purpose of accepting a call, states {@link VideoProfile#STATE_AUDIO_ONLY}, and
     * any combination of {@link VideoProfile#STATE_RX_ENABLED} and
     * {@link VideoProfile#STATE_TX_ENABLED} are considered valid.
     *
     * @param videoState The video state.
     * @return {@code true} if the video state is valid, {@code false} otherwise.
     */
    private boolean isValidAcceptVideoState(int videoState) {
        // Given a video state input, turn off TX and RX so that we can determine if those were the
        // only bits set.
        int remainingState = videoState & ~VideoProfile.STATE_TX_ENABLED;
        remainingState = remainingState & ~VideoProfile.STATE_RX_ENABLED;

        // If only TX or RX were set (or neither), the video state is valid.
        return remainingState == 0;
    }

    private void broadcastCallScreeningAppChangedIntent(String componentName,
        boolean isDefault) {
        if (TextUtils.isEmpty(componentName)) {
            return;
        }

        ComponentName broadcastComponentName = ComponentName.unflattenFromString(componentName);

        if (broadcastComponentName != null) {
            Intent intent = new Intent(TelecomManager
                .ACTION_DEFAULT_CALL_SCREENING_APP_CHANGED);
            intent.putExtra(TelecomManager
                .EXTRA_IS_DEFAULT_CALL_SCREENING_APP, isDefault);
            intent.putExtra(TelecomManager
                .EXTRA_DEFAULT_CALL_SCREENING_APP_COMPONENT_NAME, componentName);
            intent.setPackage(broadcastComponentName.getPackageName());
            mContext.sendBroadcast(intent);
        }
    }

    private void validateAccountIconUserBoundary(Icon icon) {
        // Refer to Icon#getUriString for context. The URI string is invalid for icons of
        // incompatible types.
        if (icon != null && (icon.getType() == Icon.TYPE_URI)) {
            int callingUserId = UserHandle.getCallingUserId();
            int requestingUserId = StatusHints.getUserIdFromAuthority(
                    icon.getUri().getAuthority(), callingUserId);
            if(callingUserId != requestingUserId) {
                // If we are transcending the profile boundary, throw an error.
                throw new IllegalArgumentException("Attempting to register a phone account with"
                        + " an image icon belonging to another user.");
            }
        }
    }
}
