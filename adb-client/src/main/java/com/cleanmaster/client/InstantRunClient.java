/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.cleanmaster.client;



import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.ddmlib.*;
import com.android.utils.ILogger;
import com.android.utils.NullLogger;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.cleanmaster.common.Paths.getDeviceIdFolder;
import static com.cleanmaster.common.ProtocolConstants.MESSAGE_PING;
import static com.cleanmaster.common.ProtocolConstants.MESSAGE_RESTART_ACTIVITY;
import static com.cleanmaster.common.ProtocolConstants.MESSAGE_SHOW_TOAST;

public class InstantRunClient {

    /** Local port on the desktop machine via which we tunnel to the Android device */
    // Note: just a random number, hopefully it is a free/available port on the host
    private static final int DEFAULT_LOCAL_PORT = 46622;

    @NonNull
    private final String mPackageName;

    @NonNull
    private final ILogger mLogger;

    private final long mToken;

    private final ServiceCommunicator mAppService;

    public InstantRunClient(
            @NonNull String packageName,
            @NonNull ILogger logger,
            long token) {
        this(packageName, logger, token, DEFAULT_LOCAL_PORT);
    }

    @VisibleForTesting
    public InstantRunClient(
            @NonNull String packageName,
            @NonNull ILogger logger,
            long token,
            int port) {
        mAppService = new ServiceCommunicator(packageName, logger, port);
        mPackageName = packageName;
        mLogger = logger;
        mToken = token;
    }

    private static File createTempFile(String prefix, String suffix) throws IOException {
        //noinspection SSBasedInspection Tests use this in tools/base
        File file = File.createTempFile(prefix, suffix);
        file.deleteOnExit();
        return file;
    }

    /**
     * Attempts to connect to a given device and sees if an instant run enabled app is running
     * there.
     */
    @NonNull
    public AppState getAppState(@NonNull IDevice device) throws IOException {
        return mAppService.talkToService(device,
                new Communicator<AppState>() {
                    @Override
                    public AppState communicate(@NonNull DataInputStream input,
                            @NonNull DataOutputStream output) throws IOException {
                        output.writeInt(MESSAGE_PING);
                        boolean foreground = input.readBoolean(); // Wait for "pong"
                        mLogger.info(
                            "Ping sent and replied successfully, "
                            + "application seems to be running. Foreground=" + foreground);
                        return foreground ? AppState.FOREGROUND : AppState.BACKGROUND;
                    }
                });
    }


    @SuppressWarnings("unused")
    public void showToast(@NonNull IDevice device, @NonNull final String message)
            throws IOException {
        mAppService.talkToService(device, new Communicator<Boolean>() {
            @Override
            public Boolean communicate(@NonNull DataInputStream input,
                    @NonNull DataOutputStream output) throws IOException {
                output.writeInt(MESSAGE_SHOW_TOAST);
                output.writeUTF(message);
                return false;
            }
        });
    }

    /**
     * Restart the activity on this device, if it's running and is in the foreground.
     */
    public void restartActivity(@NonNull IDevice device) throws IOException {
        AppState appState = getAppState(device);
        if (appState == AppState.FOREGROUND || appState == AppState.BACKGROUND) {
            mAppService.talkToService(device, new Communicator<Void>() {
                @Override
                public Void communicate(@NonNull DataInputStream input,
                        @NonNull DataOutputStream output) throws IOException {
                    output.writeInt(MESSAGE_RESTART_ACTIVITY);
                    writeToken(output);
                    return null;
                }
            });
        }
    }




    // Note: This method can be called even if IR is turned off, as even when IR is off, we want to
    // trash any existing build ids saved on the device.
    public static void transferBuildIdToDevice(@NonNull IDevice device,
            @NonNull String buildId,
            @NonNull String pkgName,
            @Nullable ILogger logger) {
        if (logger == null) {
            logger = new NullLogger();
        }
        final long unused = 0L;
        InstantRunClient client = new InstantRunClient(pkgName, logger, unused);
        client.transferBuildIdToDevice(device, buildId);
    }

    private void transferBuildIdToDevice(@NonNull IDevice device, @NonNull String buildId) {
        try {
            String remoteIdFile = getDeviceIdFolder(mPackageName);
            //noinspection SSBasedInspection This should work
            File local = File.createTempFile("build-id", "txt");
            local.deleteOnExit();
            Files.write(buildId, local, Charsets.UTF_8);
            device.pushFile(local.getPath(), remoteIdFile);
        } catch (IOException ioe) {
            mLogger.warning("Couldn't write build id file: %s", ioe);
        } catch (AdbCommandRejectedException | TimeoutException | SyncException e) {
            mLogger.warning("%s", Throwables.getStackTraceAsString(e));
        }
    }

    @SuppressWarnings("unused")
    @Nullable
    public static String getDeviceBuildTimestamp(@NonNull IDevice device,
            @NonNull String packageName, @NonNull ILogger logger) {
        try {
            String remoteIdFile = getDeviceIdFolder(packageName);
            File localIdFile = createTempFile("build-id", "txt");
            try {
                device.pullFile(remoteIdFile, localIdFile.getPath());
                return Files.toString(localIdFile, Charsets.UTF_8).trim();
            } catch (SyncException ignore) {
                return null;
            } finally {
                //noinspection ResultOfMethodCallIgnored
                localIdFile.delete();
            }
        } catch (IOException ignore) {
        } catch (AdbCommandRejectedException | TimeoutException e) {
            logger.warning("%s", Throwables.getStackTraceAsString(e));
        }

        return null;
    }

    private void writeToken(@NonNull DataOutputStream output) throws IOException {
        output.writeLong(mToken);
    }

    /**
     * Transfer the file as a hotswap overlay file. This means
     * that its remote path should be a temporary file.
     */
    public static final int TRANSFER_MODE_HOTSWAP = 3;

    /**
     * Transfer the file as a resource file. This means that it
     * should be written to the inactive resource file section
     * in the app data directory.
     */
    public static final int TRANSFER_MODE_RESOURCES = 4;


    private boolean runCommand(@NonNull IDevice device, @NonNull String cmd)
            throws TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException, IOException {
        String output = getCommandOutput(device, cmd).trim();
        if (!output.isEmpty()) {
            mLogger.warning("Unexpected shell output for " + cmd + ": " + output);
            return false;
        }
        return true;
    }

    @NonNull
    private static String getCommandOutput(@NonNull IDevice device, @NonNull String cmd)
            throws TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException, IOException {
        CollectingOutputReceiver receiver;
        receiver = new CollectingOutputReceiver();
        device.executeShellCommand(cmd, receiver);
        return receiver.getOutput();
    }

}
