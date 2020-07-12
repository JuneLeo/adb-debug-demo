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
package com.cleanmaster.adb;

import android.app.Activity;
import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;

import com.cleanmaster.adb.util.AppInfo;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Timer;
import java.util.TimerTask;

import static com.cleanmaster.common.Paths.RESOURCE_FILE_NAME;
import static com.cleanmaster.common.ProtocolConstants.MESSAGE_EOF;
import static com.cleanmaster.common.ProtocolConstants.MESSAGE_PATH_CHECKSUM;
import static com.cleanmaster.common.ProtocolConstants.MESSAGE_PATH_EXISTS;
import static com.cleanmaster.common.ProtocolConstants.MESSAGE_PING;
import static com.cleanmaster.common.ProtocolConstants.MESSAGE_RESTART_ACTIVITY;
import static com.cleanmaster.common.ProtocolConstants.MESSAGE_SHOW_TOAST;
import static com.cleanmaster.common.ProtocolConstants.PROTOCOL_IDENTIFIER;
import static com.cleanmaster.common.ProtocolConstants.PROTOCOL_VERSION;

/**
 * Server running in the app listening for messages from the IDE and updating the code and resources
 * when provided
 */
public class Server {

    /**
     * If true, app restarts itself after receiving coldswap patches. If false,
     * it will just wait for the client to kill it remotely and restart via activity manager.
     * If we restart locally, there could be problems around: a) getting all the right intent
     * data to the restarted activity, and b) sometimes, the activity state is saved by the
     * system, and it could lead to conflicts with the new version of the app.
     * So this is currently turned off. See
     * https://code.google.com/p/android/issues/detail?id=200895#c9
     */
    private static final boolean RESTART_LOCALLY = false;

    /**
     * Temporary debugging: have the server emit a message to the log every 30 seconds to
     * indicate whether it's still alive
     */
    private static final boolean POST_ALIVE_STATUS = false;

    private LocalServerSocket serverSocket;

    private final Context context;

    private static int wrongTokenCount;

    @NonNull
    public static Server create(@NonNull Context context) {
        return new Server(context.getPackageName(), context);
    }

    private Server(@NonNull String packageName, @NonNull Context context) {
        this.context = context;
        try {
            serverSocket = new LocalServerSocket(packageName);
            if (Log.isLoggable(Logging.LOG_TAG, Log.VERBOSE)) {
                Log.v(
                        Logging.LOG_TAG,
                        "Starting server socket listening for package "
                                + packageName
                                + " on "
                                + serverSocket.getLocalSocketAddress());
            }
        } catch (IOException e) {
            Log.e(Logging.LOG_TAG, "IO Error creating local socket at " + packageName, e);
            return;
        }
        startServer();

        if (Log.isLoggable(Logging.LOG_TAG, Log.VERBOSE)) {
            Log.v(Logging.LOG_TAG, "Started server for package " + packageName);
        }
    }

    private void startServer() {
        try {
            Thread socketServerThread = new Thread(new SocketServerThread());
            socketServerThread.start();
        } catch (Throwable e) {
            // Make sure an exception doesn't cause the rest of the user's
            // onCreate() method to be invoked
            if (Log.isLoggable(Logging.LOG_TAG, Log.ERROR)) {
                Log.e(Logging.LOG_TAG, "Fatal error starting Instant Run server", e);
            }
        }
    }

    public void shutdown() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignore) {
            }
            serverSocket = null;
        }
    }

    private class SocketServerThread extends Thread {
        @Override
        public void run() {
            if (POST_ALIVE_STATUS) {
                final Handler handler = new Handler();
                Timer timer = new Timer();
                TimerTask task =
                        new TimerTask() {
                            @Override
                            public void run() {
                                handler.post(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                Log.v(
                                                        Logging.LOG_TAG,
                                                        "Instant Run server still here...");
                                            }
                                        });
                            }
                        };

                timer.schedule(task, 1, 30000L);
            }

            while (true) {
                try {
                    LocalServerSocket serverSocket = Server.this.serverSocket;
                    if (serverSocket == null) {
                        break; // stopped?
                    }
                    LocalSocket socket = serverSocket.accept();

                    if (Log.isLoggable(Logging.LOG_TAG, Log.VERBOSE)) {
                        Log.v(
                                Logging.LOG_TAG,
                                "Received connection from IDE: spawning connection thread");
                    }

                    SocketServerReplyThread socketServerReplyThread = new SocketServerReplyThread(
                            socket);
                    socketServerReplyThread.run();

                    if (wrongTokenCount > 50) {
                        if (Log.isLoggable(Logging.LOG_TAG, Log.VERBOSE)) {
                            Log.v(
                                    Logging.LOG_TAG,
                                    "Stopping server: too many wrong token connections");
                        }
                        Server.this.serverSocket.close();
                        break;
                    }
                } catch (Throwable e) {
                    if (Log.isLoggable(Logging.LOG_TAG, Log.VERBOSE)) {
                        Log.v(
                                Logging.LOG_TAG,
                                "Fatal error accepting connection on local socket",
                                e);
                    }
                }
            }
        }
    }

    private class SocketServerReplyThread extends Thread {

        private final LocalSocket socket;

        SocketServerReplyThread(LocalSocket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                try {
                    handle(input, output);
                } finally {
                    try {
                        input.close();
                    } catch (IOException ignore) {
                    }
                    try {
                        output.close();
                    } catch (IOException ignore) {
                    }
                }
            } catch (IOException e) {
                if (Log.isLoggable(Logging.LOG_TAG, Log.VERBOSE)) {
                    Log.v(Logging.LOG_TAG, "Fatal error receiving messages", e);
                }
            }
        }

        private void handle(DataInputStream input, DataOutputStream output) throws IOException {
            long magic = input.readLong();
            if (magic != PROTOCOL_IDENTIFIER) {
                Log.w(Logging.LOG_TAG, "Unrecognized header format " + Long.toHexString(magic));
                return;
            }
            int version = input.readInt();

            // Send current protocol version to the IDE so it can decide what to do
            output.writeInt(PROTOCOL_VERSION);

            if (version != PROTOCOL_VERSION) {
                Log.w(
                        Logging.LOG_TAG,
                        "Mismatched protocol versions; app is "
                                + "using version "
                                + PROTOCOL_VERSION
                                + " and tool is using version "
                                + version);
                return;
            }

            while (true) {
                int message = input.readInt();
                switch (message) {
                    case MESSAGE_EOF:
                        {
                            if (Log.isLoggable(Logging.LOG_TAG, Log.VERBOSE)) {
                                Log.v(Logging.LOG_TAG, "Received EOF from the IDE");
                            }
                            return;
                        }

                    case MESSAGE_PING:
                        {
                            // Send an "ack" back to the IDE.
                            // The value of the boolean is true only when the app is in the
                            // foreground.
                            boolean active = Restarter.getForegroundActivity(context) != null;
                            output.writeBoolean(active);
                            if (Log.isLoggable(Logging.LOG_TAG, Log.VERBOSE)) {
                                Log.v(
                                        Logging.LOG_TAG,
                                        "Received Ping message from the IDE; "
                                                + "returned active = "
                                                + active);
                            }
                            continue;
                        }

                    case MESSAGE_PATH_EXISTS:
                        {
                            if (FileManager.USE_EXTRACTED_RESOURCES) {
                                String path = input.readUTF();
                                long size = FileManager.getFileSize(path);
                                output.writeLong(size);
                                if (Log.isLoggable(Logging.LOG_TAG, Log.VERBOSE)) {
                                    Log.v(
                                            Logging.LOG_TAG,
                                            "Received path-exists("
                                                    + path
                                                    + ") from the "
                                                    + "IDE; returned size="
                                                    + size);
                                }
                            } else {
                                if (Log.isLoggable(Logging.LOG_TAG, Log.ERROR)) {
                                    Log.e(Logging.LOG_TAG, "Unexpected message type: " + message);
                                }
                            }
                            continue;
                        }

                    case MESSAGE_PATH_CHECKSUM:
                        {
                            if (FileManager.USE_EXTRACTED_RESOURCES) {
                                long begin = System.currentTimeMillis();
                                String path = input.readUTF();
                                byte[] checksum = FileManager.getCheckSum(path);
                                if (checksum != null) {
                                    output.writeInt(checksum.length);
                                    output.write(checksum);
                                    if (Log.isLoggable(Logging.LOG_TAG, Log.VERBOSE)) {
                                        long end = System.currentTimeMillis();
                                        String hash = new BigInteger(1, checksum).toString(16);
                                        Log.v(
                                                Logging.LOG_TAG,
                                                "Received checksum("
                                                        + path
                                                        + ") from the "
                                                        + "IDE: took "
                                                        + (end - begin)
                                                        + "ms to compute "
                                                        + hash);
                                    }
                                } else {
                                    output.writeInt(0);
                                    if (Log.isLoggable(Logging.LOG_TAG, Log.VERBOSE)) {
                                        Log.v(
                                                Logging.LOG_TAG,
                                                "Received checksum("
                                                        + path
                                                        + ") from the "
                                                        + "IDE: returning <null>");
                                    }
                                }
                            } else {
                                if (Log.isLoggable(Logging.LOG_TAG, Log.ERROR)) {
                                    Log.e(Logging.LOG_TAG, "Unexpected message type: " + message);
                                }
                            }
                            continue;
                        }

                    case MESSAGE_RESTART_ACTIVITY:
                        {
                            if (!authenticate(input)) {
                                return;
                            }

                            Activity activity = Restarter.getForegroundActivity(context);
                            if (activity != null) {
                                if (Log.isLoggable(Logging.LOG_TAG, Log.VERBOSE)) {
                                    Log.v(Logging.LOG_TAG, "Restarting activity per user request");
                                }
                                Restarter.restartActivityOnUiThread(activity);
                            }
                            continue;
                        }

                    case MESSAGE_SHOW_TOAST:
                        {
                            String text = input.readUTF();
                            Activity foreground = Restarter.getForegroundActivity(context);
                            if (foreground != null) {
                                Restarter.showToast(foreground, text);
                            } else if (Log.isLoggable(Logging.LOG_TAG, Log.VERBOSE)) {
                                Log.v(
                                        Logging.LOG_TAG,
                                        "Couldn't show toast (no activity) : " + text);
                            }
                            continue;
                        }

                    default:
                        {
                            if (Log.isLoggable(Logging.LOG_TAG, Log.ERROR)) {
                                Log.e(Logging.LOG_TAG, "Unexpected message type: " + message);
                            }
                            // If we hit unexpected message types we can't really continue
                            // the conversation: we can misinterpret data for the unexpected
                            // command as separate messages with different meanings than intended
                            return;
                        }
                }
            }
        }

        private boolean authenticate(@NonNull DataInputStream input) throws IOException {
            long token = input.readLong();
            if (token != AppInfo.token) {
                Log.w(
                        Logging.LOG_TAG,
                        "Mismatched identity token from client; received "
                                + token
                                + " and expected "
                                + AppInfo.token);
                wrongTokenCount++;
                return false;
            }
            return true;
        }
    }

    private static boolean isResourcePath(String path) {
        return path.equals(RESOURCE_FILE_NAME) || path.startsWith("res/");
    }
}
