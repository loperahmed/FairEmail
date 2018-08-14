package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018 by Marcel Bokhorst (M66B)
*/

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import com.sun.mail.iap.ProtocolException;
import com.sun.mail.imap.AppendUID;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;
import com.sun.mail.imap.IMAPStore;
import com.sun.mail.imap.protocol.IMAPProtocol;
import com.sun.mail.smtp.SMTPSendFailedException;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.mail.Address;
import javax.mail.FetchProfile;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.FolderClosedException;
import javax.mail.FolderNotFoundException;
import javax.mail.Message;
import javax.mail.MessageRemovedException;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.UIDFolder;
import javax.mail.event.ConnectionAdapter;
import javax.mail.event.ConnectionEvent;
import javax.mail.event.FolderAdapter;
import javax.mail.event.FolderEvent;
import javax.mail.event.MessageChangedEvent;
import javax.mail.event.MessageChangedListener;
import javax.mail.event.MessageCountAdapter;
import javax.mail.event.MessageCountEvent;
import javax.mail.event.StoreEvent;
import javax.mail.event.StoreListener;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.search.ComparisonTerm;
import javax.mail.search.ReceivedDateTerm;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;
import androidx.lifecycle.Observer;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class ServiceSynchronize extends LifecycleService {
    private final Object lock = new Object();
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final int NOTIFICATION_SYNCHRONIZE = 1;
    private static final int NOTIFICATION_UNSEEN = 2;

    private static final int CONNECT_BACKOFF_START = 2; // seconds
    private static final int CONNECT_BACKOFF_MAX = 128; // seconds
    private static final long NOOP_INTERVAL = 9 * 60 * 1000L; // ms
    private static final int ATTACHMENT_BUFFER_SIZE = 8192; // bytes

    static final String ACTION_PROCESS_OPERATIONS = BuildConfig.APPLICATION_ID + ".PROCESS_OPERATIONS";

    public ServiceSynchronize() {
        // https://docs.oracle.com/javaee/6/api/javax/mail/internet/package-summary.html
        System.setProperty("mail.mime.ignoreunknownencoding", "true");
        System.setProperty("mail.mime.decodefilename", "true");
        System.setProperty("mail.mime.encodefilename", "true");
    }

    @Override
    public void onCreate() {
        Log.i(Helper.TAG, "Service create");
        super.onCreate();
        startForeground(NOTIFICATION_SYNCHRONIZE, getNotificationService(0, 0, 0).build());

        // Listen for network changes
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        // Removed because of Android VPN service
        // builder.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        cm.registerNetworkCallback(builder.build(), networkCallback);

        DB.getInstance(this).account().liveStats().observe(this, new Observer<TupleAccountStats>() {
            private int prev_unseen = -1;

            @Override
            public void onChanged(@Nullable TupleAccountStats stats) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.notify(NOTIFICATION_SYNCHRONIZE,
                        getNotificationService(stats.accounts, stats.operations, stats.unsent).build());

                if (stats.unseen > 0) {
                    if (stats.unseen > prev_unseen) {
                        nm.cancel(NOTIFICATION_UNSEEN);
                        nm.notify(NOTIFICATION_UNSEEN, getNotificationUnseen(stats.unseen).build());
                    }
                } else
                    nm.cancel(NOTIFICATION_UNSEEN);

                prev_unseen = stats.unseen;
            }
        });
    }

    @Override
    public void onDestroy() {
        Log.i(Helper.TAG, "Service destroy");

        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.unregisterNetworkCallback(networkCallback);

        networkCallback.onLost(cm.getActiveNetwork());

        stopForeground(true);

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.cancel(NOTIFICATION_SYNCHRONIZE);

        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(Helper.TAG, "Service start intent=" + intent);
        super.onStartCommand(intent, flags, startId);

        if (intent != null && "unseen".equals(intent.getAction())) {
            Bundle args = new Bundle();
            args.putLong("time", new Date().getTime());

            new SimpleTask<Void>() {
                @Override
                protected Void onLoad(Context context, Bundle args) {
                    long time = args.getLong("time");

                    DB db = DB.getInstance(context);
                    try {
                        db.beginTransaction();

                        for (EntityAccount account : db.account().getAccounts(true))
                            db.account().setAccountSeenUntil(account.id, time);

                        db.setTransactionSuccessful();
                    } finally {
                        db.endTransaction();
                    }

                    return null;
                }

                @Override
                protected void onLoaded(Bundle args, Void data) {
                    Log.i(Helper.TAG, "Updated seen until");
                }
            }.load(ServiceSynchronize.this, args);
        }

        return START_STICKY;
    }

    private Notification.Builder getNotificationService(int accounts, int operations, int unsent) {
        // Build pending intent
        Intent intent = new Intent(this, ActivityView.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(
                this, ActivityView.REQUEST_VIEW, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        // Build notification
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            builder = new Notification.Builder(this, "service");
        else
            builder = new Notification.Builder(this);

        builder
                .setSmallIcon(R.drawable.baseline_mail_outline_24)
                .setContentTitle(getResources().getQuantityString(R.plurals.title_notification_synchronizing, accounts, accounts))
                .setContentIntent(pi)
                .setAutoCancel(false)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_MIN)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_SECRET);

        if (operations > 0)
            builder.setStyle(new Notification.BigTextStyle().setSummaryText(
                    getResources().getQuantityString(R.plurals.title_notification_operations, operations, operations)));

        if (unsent > 0)
            builder.setContentText(getResources().getQuantityString(R.plurals.title_notification_unsent, unsent, unsent));

        return builder;
    }

    private Notification.Builder getNotificationUnseen(int unseen) {
        // Build pending intent
        Intent intent = new Intent(this, ActivityView.class);
        intent.setAction("unseen");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(
                this, ActivityView.REQUEST_UNSEEN, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent delete = new Intent(this, ServiceSynchronize.class);
        delete.setAction("unseen");
        PendingIntent pid = PendingIntent.getService(this, 1, delete, PendingIntent.FLAG_UPDATE_CURRENT);

        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

        // Build notification
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            builder = new Notification.Builder(this, "notification");
        else
            builder = new Notification.Builder(this);

        builder
                .setSmallIcon(R.drawable.baseline_mail_24)
                .setContentTitle(getResources().getQuantityString(R.plurals.title_notification_unseen, unseen, unseen))
                .setContentIntent(pi)
                .setSound(uri)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDeleteIntent(pid);

        return builder;
    }

    private Notification.Builder getNotificationError(String action, Throwable ex) {
        // Build pending intent
        Intent intent = new Intent(this, ActivityView.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(
                this, ActivityView.REQUEST_VIEW, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        // Build notification
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            builder = new Notification.Builder(this, "error");
        else
            builder = new Notification.Builder(this);

        builder
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(getString(R.string.title_notification_failed, action))
                .setContentText(Helper.formatThrowable(ex))
                .setContentIntent(pi)
                .setAutoCancel(false)
                .setShowWhen(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_ERROR)
                .setVisibility(Notification.VISIBILITY_SECRET);

        return builder;
    }

    private void reportError(String account, String folder, Throwable ex) {
        String action = account + "/" + folder;
        if (!(ex instanceof IllegalStateException) && // This operation is not allowed on a closed folder
                !(ex instanceof FolderClosedException)) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(action, 1, getNotificationError(action, ex).build());
        }
    }

    private void monitorAccount(final EntityAccount account, final ServiceState state) {
        final List<Thread> threads = new ArrayList<>();
        Log.i(Helper.TAG, account.name + " start");

        final DB db = DB.getInstance(ServiceSynchronize.this);
        db.account().setAccountState(account.id, "connecting");

        int backoff = CONNECT_BACKOFF_START;
        while (state.running) {
            IMAPStore istore = null;
            try {
                Properties props = MessageHelper.getSessionProperties();
                props.setProperty("mail.imaps.peek", "true");
                props.setProperty("mail.mime.address.strict", "false");
                props.setProperty("mail.mime.decodetext.strict", "false");
                //props.put("mail.imaps.minidletime", "5000");

                boolean debug = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("debug", false);
                final Session isession = Session.getInstance(props, null);
                isession.setDebug(debug);
                // adb -t 1 logcat | grep "eu.faircode.email\|System.out"

                istore = (IMAPStore) isession.getStore("imaps");
                final IMAPStore fstore = istore;

                // Listen for events
                istore.addStoreListener(new StoreListener() {
                    @Override
                    public void notification(StoreEvent e) {
                        Log.i(Helper.TAG, account.name + " event: " + e.getMessage());
                        db.account().setAccountError(account.id, e.getMessage());
                    }
                });
                istore.addFolderListener(new FolderAdapter() {
                    @Override
                    public void folderCreated(FolderEvent e) {
                        // TODO: folder created
                    }

                    @Override
                    public void folderRenamed(FolderEvent e) {
                        // TODO: folder renamed
                    }

                    @Override
                    public void folderDeleted(FolderEvent e) {
                        // TODO: folder deleted
                    }
                });

                // Listen for connection changes
                istore.addConnectionListener(new ConnectionAdapter() {
                    Map<Long, IMAPFolder> mapFolder = new HashMap<>();

                    @Override
                    public void opened(ConnectionEvent e) {
                        Log.i(Helper.TAG, account.name + " opened");

                        db.account().setAccountState(account.id, "connected");
                        db.account().setAccountError(account.id, null);

                        try {
                            synchronizeFolders(account, fstore);

                            for (final EntityFolder folder : db.folder().getFolders(account.id, true)) {
                                Log.i(Helper.TAG, account.name + " sync folder " + folder.name);

                                // Monitor folders
                                Thread t = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        IMAPFolder ifolder = null;
                                        try {
                                            Log.i(Helper.TAG, folder.name + " start");

                                            db.folder().setFolderState(folder.id, "connecting");

                                            ifolder = (IMAPFolder) fstore.getFolder(folder.name);
                                            ifolder.open(Folder.READ_WRITE);

                                            db.folder().setFolderState(folder.id, "connected");
                                            db.folder().setFolderError(folder.id, null);

                                            synchronized (mapFolder) {
                                                mapFolder.put(folder.id, ifolder);
                                            }

                                            monitorFolder(account, folder, fstore, ifolder, state);

                                        } catch (FolderClosedException ex) {
                                            // Happens when no connectivity
                                            Log.w(Helper.TAG, folder.name + " " + ex + "\n" + Log.getStackTraceString(ex));

                                        } catch (IllegalStateException ex) {
                                            // Happens when syncing message
                                            // This operation is not allowed on a closed folder
                                            Log.w(Helper.TAG, folder.name + " " + ex + "\n" + Log.getStackTraceString(ex));

                                        } catch (Throwable ex) {
                                            Log.e(Helper.TAG, folder.name + " " + ex + "\n" + Log.getStackTraceString(ex));
                                            reportError(account.name, folder.name, ex);

                                            db.folder().setFolderError(folder.id, Helper.formatThrowable(ex));

                                            // Cascade up
                                            try {
                                                fstore.close();
                                            } catch (MessagingException e1) {
                                                Log.w(Helper.TAG, account.name + " " + e1 + "\n" + Log.getStackTraceString(e1));
                                            }
                                        } finally {
                                            if (ifolder != null && ifolder.isOpen()) {
                                                try {
                                                    ifolder.close(false);
                                                } catch (MessagingException ex) {
                                                    Log.w(Helper.TAG, folder.name + " " + ex + "\n" + Log.getStackTraceString(ex));
                                                }
                                            }

                                            db.folder().setFolderState(folder.id, null);

                                            Log.i(Helper.TAG, folder.name + " stopped");
                                        }
                                    }
                                }, "sync.folder." + folder.id);
                                t.start();
                                threads.add(t);
                            }

                            // Listen for folder operations
                            IntentFilter f = new IntentFilter(ACTION_PROCESS_OPERATIONS);
                            f.addDataType("account/" + account.id);
                            LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(ServiceSynchronize.this);
                            lbm.registerReceiver(processReceiver, f);

                            // Run folder operations
                            Log.i(Helper.TAG, "listen process folder");
                            for (final EntityFolder folder : db.folder().getFolders(account.id))
                                if (!EntityFolder.OUTBOX.equals(folder.type))
                                    lbm.sendBroadcast(new Intent(ACTION_PROCESS_OPERATIONS)
                                            .setType("account/" + account.id)
                                            .putExtra("folder", folder.id));

                        } catch (Throwable ex) {
                            Log.e(Helper.TAG, account.name + " " + ex + "\n" + Log.getStackTraceString(ex));
                            reportError(account.name, null, ex);

                            db.account().setAccountError(account.id, Helper.formatThrowable(ex));

                            // Cascade up
                            try {
                                fstore.close();
                            } catch (MessagingException e1) {
                                Log.w(Helper.TAG, account.name + " " + e1 + "\n" + Log.getStackTraceString(e1));
                            }
                        }
                    }

                    @Override
                    public void disconnected(ConnectionEvent e) {
                        Log.e(Helper.TAG, account.name + " disconnected");

                        db.account().setAccountState(account.id, null);

                        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(ServiceSynchronize.this);
                        lbm.unregisterReceiver(processReceiver);

                        synchronized (mapFolder) {
                            mapFolder.clear();
                        }

                        // Check connection
                        synchronized (state) {
                            state.notifyAll();
                        }
                    }

                    @Override
                    public void closed(ConnectionEvent e) {
                        Log.e(Helper.TAG, account.name + " closed");

                        db.account().setAccountState(account.id, null);

                        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(ServiceSynchronize.this);
                        lbm.unregisterReceiver(processReceiver);

                        synchronized (mapFolder) {
                            mapFolder.clear();
                        }

                        // Check connection
                        synchronized (state) {
                            state.notifyAll();
                        }
                    }

                    BroadcastReceiver processReceiver = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            final long fid = intent.getLongExtra("folder", -1);

                            IMAPFolder ifolder;
                            synchronized (mapFolder) {
                                ifolder = mapFolder.get(fid);
                            }
                            final boolean shouldClose = (ifolder == null);
                            final IMAPFolder ffolder = ifolder;

                            Log.i(Helper.TAG, "run operations folder=" + fid + " offline=" + shouldClose);
                            executor.submit(new Runnable() {
                                @Override
                                public void run() {
                                    DB db = DB.getInstance(ServiceSynchronize.this);
                                    EntityFolder folder = db.folder().getFolder(fid);
                                    IMAPFolder ifolder = ffolder;
                                    try {
                                        Log.i(Helper.TAG, folder.name + " start operations");

                                        if (ifolder == null) {
                                            // Prevent unnecessary folder connections
                                            if (db.operation().getOperationCount(fid) == 0)
                                                return;

                                            ifolder = (IMAPFolder) fstore.getFolder(folder.name);
                                            ifolder.open(Folder.READ_WRITE);
                                        }

                                        processOperations(folder, isession, fstore, ifolder);
                                    } catch (Throwable ex) {
                                        Log.e(Helper.TAG, folder.name + " " + ex + "\n" + Log.getStackTraceString(ex));
                                        reportError(account.name, folder.name, ex);
                                    } finally {
                                        if (shouldClose)
                                            if (ifolder != null && ifolder.isOpen()) {
                                                try {
                                                    ifolder.close(false);
                                                } catch (MessagingException ex) {
                                                    Log.w(Helper.TAG, folder.name + " " + ex + "\n" + Log.getStackTraceString(ex));
                                                }
                                            }
                                        Log.i(Helper.TAG, folder.name + " stop operations");
                                    }
                                }
                            });
                        }
                    };
                });

                // Initiate connection
                Log.i(Helper.TAG, account.name + " connect");
                istore.connect(account.host, account.port, account.user, account.password);
                backoff = CONNECT_BACKOFF_START;

                // Keep alive
                boolean connected = false;
                do {
                    try {
                        Log.i(Helper.TAG, account.name + " wait");
                        synchronized (state) {
                            state.wait();
                        }
                        Log.i(Helper.TAG, account.name + " waited");
                    } catch (InterruptedException ex) {
                        Log.w(Helper.TAG, account.name + " " + ex.toString());
                    }
                    if (state.running) {
                        Log.i(Helper.TAG, account.name + " NOOP");
                        connected = istore.isConnected();
                    }
                } while (state.running && connected);

                if (state.running)
                    Log.w(Helper.TAG, account.name + " not connected anymore");
                else
                    Log.i(Helper.TAG, account.name + " not running anymore");

            } catch (Throwable ex) {
                Log.e(Helper.TAG, account.name + " " + ex + "\n" + Log.getStackTraceString(ex));
                reportError(account.name, null, ex);

                db.account().setAccountError(account.id, Helper.formatThrowable(ex));
            } finally {
                Log.i(Helper.TAG, account.name + " closing");
                if (istore != null) {
                    try {
                        istore.close();
                    } catch (MessagingException ex) {
                        Log.w(Helper.TAG, account.name + " " + ex + "\n" + Log.getStackTraceString(ex));
                    }
                }
                Log.i(Helper.TAG, account.name + " closed");
            }

            if (state.running) {
                try {
                    Log.i(Helper.TAG, "Backoff seconds=" + backoff);
                    Thread.sleep(backoff * 1000L);

                    if (backoff < CONNECT_BACKOFF_MAX)
                        backoff *= 2;
                } catch (InterruptedException ex) {
                    Log.w(Helper.TAG, account.name + " " + ex.toString());
                }
            }
        }

        db.account().setAccountState(account.id, null);

        for (Thread t : threads) {
            try {
                Log.i(Helper.TAG, "Joining " + t.getName());
                t.join();
                Log.i(Helper.TAG, "Joined " + t.getName());
            } catch (InterruptedException ex) {
                Log.w(Helper.TAG, account.name + " " + ex + "\n" + Log.getStackTraceString(ex));
            }
        }
        threads.clear();

        Log.i(Helper.TAG, account.name + " stopped");
    }

    private void monitorFolder(
            final EntityAccount account, final EntityFolder folder,
            final IMAPStore istore, final IMAPFolder ifolder,
            ServiceState state) throws MessagingException, JSONException, IOException {
        // Listen for new and deleted messages
        ifolder.addMessageCountListener(new MessageCountAdapter() {
            @Override
            public void messagesAdded(MessageCountEvent e) {
                synchronized (lock) {
                    try {
                        Log.i(Helper.TAG, folder.name + " messages added");
                        for (Message imessage : e.getMessages())
                            synchronizeMessage(folder, ifolder, (IMAPMessage) imessage);
                    } catch (MessageRemovedException ex) {
                        Log.w(Helper.TAG, folder.name + " " + ex + "\n" + Log.getStackTraceString(ex));
                    } catch (Throwable ex) {
                        Log.e(Helper.TAG, folder.name + " " + ex + "\n" + Log.getStackTraceString(ex));
                        reportError(account.name, folder.name, ex);

                        // Cascade up
                        try {
                            istore.close();
                        } catch (MessagingException e1) {
                            Log.w(Helper.TAG, folder.name + " " + e1 + "\n" + Log.getStackTraceString(e1));
                        }
                    }
                }
            }

            @Override
            public void messagesRemoved(MessageCountEvent e) {
                synchronized (lock) {
                    try {
                        Log.i(Helper.TAG, folder.name + " messages removed");
                        for (Message imessage : e.getMessages())
                            try {
                                long uid = ifolder.getUID(imessage);

                                DB db = DB.getInstance(ServiceSynchronize.this);
                                int count = db.message().deleteMessage(folder.id, uid);

                                Log.i(Helper.TAG, "Deleted uid=" + uid + " count=" + count);
                            } catch (MessageRemovedException ex) {
                                Log.w(Helper.TAG, folder.name + " " + ex + "\n" + Log.getStackTraceString(ex));
                            }
                    } catch (Throwable ex) {
                        Log.e(Helper.TAG, folder.name + " " + ex + "\n" + Log.getStackTraceString(ex));
                        reportError(account.name, folder.name, ex);

                        // Cascade up
                        try {
                            istore.close();
                        } catch (MessagingException e1) {
                            Log.w(Helper.TAG, folder.name + " " + e1 + "\n" + Log.getStackTraceString(e1));
                        }
                    }
                }
            }
        });

        // Fetch e-mail
        synchronizeMessages(folder, ifolder);

        // Flags (like "seen") at the remote could be changed while synchronizing

        // Listen for changed messages
        ifolder.addMessageChangedListener(new MessageChangedListener() {
            @Override
            public void messageChanged(MessageChangedEvent e) {
                synchronized (lock) {
                    try {
                        Log.i(Helper.TAG, folder.name + " message changed");
                        synchronizeMessage(folder, ifolder, (IMAPMessage) e.getMessage());
                    } catch (MessageRemovedException ex) {
                        Log.w(Helper.TAG, folder.name + " " + ex + "\n" + Log.getStackTraceString(ex));
                    } catch (Throwable ex) {
                        Log.e(Helper.TAG, folder.name + " " + ex + "\n" + Log.getStackTraceString(ex));
                        reportError(account.name, folder.name, ex);

                        DB.getInstance(ServiceSynchronize.this).folder().setFolderError(folder.id, Helper.formatThrowable(ex));

                        // Cascade up
                        try {
                            istore.close();
                        } catch (MessagingException e1) {
                            Log.w(Helper.TAG, folder.name + " " + e1 + "\n" + Log.getStackTraceString(e1));
                        }
                    }
                }
            }
        });

        // Keep alive
        Log.i(Helper.TAG, folder.name + " start");
        try {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean open;
                        do {
                            try {
                                Thread.sleep(NOOP_INTERVAL);
                            } catch (InterruptedException ex) {
                                Log.w(Helper.TAG, folder.name + " " + ex.toString());
                            }
                            open = ifolder.isOpen();
                            if (open)
                                noop(folder, ifolder);
                        } while (open);
                    } catch (Throwable ex) {
                        Log.e(Helper.TAG, folder.name + " " + ex + "\n" + Log.getStackTraceString(ex));
                        reportError(account.name, folder.name, ex);

                        DB.getInstance(ServiceSynchronize.this).folder().setFolderError(folder.id, Helper.formatThrowable(ex));

                        // Cascade up
                        try {
                            istore.close();
                        } catch (MessagingException e1) {
                            Log.w(Helper.TAG, folder.name + " " + e1 + "\n" + Log.getStackTraceString(e1));
                        }
                    }
                }
            }, "sync.noop." + folder.id);
            thread.start();

            // Idle
            while (state.running) {
                Log.i(Helper.TAG, folder.name + " start idle");
                ifolder.idle(false);
                Log.i(Helper.TAG, folder.name + " end idle");
            }
        } finally {
            Log.i(Helper.TAG, folder.name + " end");
        }
    }

    private void processOperations(EntityFolder folder, Session isession, IMAPStore istore, IMAPFolder ifolder) throws MessagingException, JSONException, IOException {
        synchronized (lock) {
            try {
                Log.i(Helper.TAG, folder.name + " start process");

                DB db = DB.getInstance(this);
                List<EntityOperation> ops = db.operation().getOperationsByFolder(folder.id);
                Log.i(Helper.TAG, folder.name + " pending operations=" + ops.size());
                for (EntityOperation op : ops)
                    try {
                        Log.i(Helper.TAG, folder.name +
                                " start op=" + op.id + "/" + op.name +
                                " msg=" + op.message +
                                " args=" + op.args);

                        EntityMessage message = db.message().getMessage(op.message);
                        if (message == null)
                            throw new MessageRemovedException();

                        if (message.uid == null &&
                                (EntityOperation.SEEN.equals(op.name) ||
                                        EntityOperation.DELETE.equals(op.name) ||
                                        EntityOperation.MOVE.equals(op.name)))
                            throw new IllegalArgumentException(op.name + " without uid");

                        try {
                            JSONArray jargs = new JSONArray(op.args);

                            if (EntityOperation.SEEN.equals(op.name))
                                doSeen(folder, ifolder, message, jargs, db);

                            else if (EntityOperation.ADD.equals(op.name))
                                doAdd(folder, isession, ifolder, message, jargs, db);

                            else if (EntityOperation.MOVE.equals(op.name))
                                doMove(folder, isession, istore, ifolder, message, jargs, db);

                            else if (EntityOperation.DELETE.equals(op.name))
                                doDelete(folder, ifolder, message, jargs, db);

                            else if (EntityOperation.SEND.equals(op.name))
                                doSend(db, message);

                            else if (EntityOperation.ATTACHMENT.equals(op.name))
                                doAttachment(folder, op, ifolder, message, jargs, db);

                            else
                                throw new MessagingException("Unknown operation name=" + op.name);

                            // Operation succeeded
                            db.operation().deleteOperation(op.id);
                        } catch (Throwable ex) {
                            db.message().setMessageError(message.id, Helper.formatThrowable(ex));

                            if (BuildConfig.DEBUG && ex instanceof NullPointerException) {
                                db.operation().deleteOperation(op.id);
                                throw ex;
                            }

                            if (ex instanceof MessageRemovedException ||
                                    ex instanceof FolderNotFoundException ||
                                    ex instanceof SMTPSendFailedException) {
                                Log.w(Helper.TAG, ex + "\n" + Log.getStackTraceString(ex));

                                // There is no use in repeating
                                db.operation().deleteOperation(op.id);
                                continue;
                            } else if (ex instanceof MessagingException) {
                                // Socket timeout is a recoverable condition (send message)
                                if (ex.getCause() instanceof SocketTimeoutException) {
                                    Log.w(Helper.TAG, "Recoverable " + ex);
                                    // No need to inform user
                                    return;
                                }
                            }

                            throw ex;
                        }
                    } finally {
                        Log.i(Helper.TAG, folder.name + " end op=" + op.id + "/" + op.name);
                    }
            } finally {
                Log.i(Helper.TAG, folder.name + " end process");
            }
        }
    }

    private void doSeen(EntityFolder folder, IMAPFolder ifolder, EntityMessage message, JSONArray jargs, DB db) throws MessagingException, JSONException {
        // Mark message (un)seen
        boolean seen = jargs.getBoolean(0);
        Message imessage = ifolder.getMessageByUID(message.uid);
        if (imessage == null)
            throw new MessageRemovedException();

        imessage.setFlag(Flags.Flag.SEEN, seen);

        db.message().setMessageSeen(message.id, seen);
    }

    private void doAdd(EntityFolder folder, Session isession, IMAPFolder ifolder, EntityMessage message, JSONArray jargs, DB db) throws MessagingException, JSONException {
        // Append message
        List<EntityAttachment> attachments = db.attachment().getAttachments(message.id);

        MimeMessage imessage = MessageHelper.from(this, message, attachments, isession);
        AppendUID[] uid = ifolder.appendUIDMessages(new Message[]{imessage});

        if (message.uid != null) {
            Message iprev = ifolder.getMessageByUID(message.uid);
            if (iprev != null) {
                Log.i(Helper.TAG, "Deleting existing id=" + message.id);
                iprev.setFlag(Flags.Flag.DELETED, true);
                ifolder.expunge();
            }
        }

        db.message().setMessageUid(message.id, uid[0].uid);
        Log.i(Helper.TAG, "Appended uid=" + message.uid);
    }

    private void doMove(EntityFolder folder, Session isession, IMAPStore istore, IMAPFolder ifolder, EntityMessage message, JSONArray jargs, DB db) throws JSONException, MessagingException {
        // Move message
        long id = jargs.getLong(0);
        EntityFolder target = db.folder().getFolder(id);
        if (target == null)
            throw new FolderNotFoundException();

        // Get message
        Message imessage = ifolder.getMessageByUID(message.uid);
        if (imessage == null)
            throw new MessageRemovedException();

        if (istore.hasCapability("MOVE")) {
            Folder itarget = istore.getFolder(target.name);
            ifolder.moveMessages(new Message[]{imessage}, itarget);
        } else {
            Log.w(Helper.TAG, "MOVE by DELETE/APPEND");

            List<EntityAttachment> attachments = db.attachment().getAttachments(message.id);

            if (!EntityFolder.ARCHIVE.equals(folder.type)) {
                imessage.setFlag(Flags.Flag.DELETED, true);
                ifolder.expunge();
            }

            MimeMessageEx icopy = MessageHelper.from(this, message, attachments, isession);
            Folder itarget = istore.getFolder(target.name);
            itarget.appendMessages(new Message[]{icopy});
        }
    }

    private void doDelete(EntityFolder folder, IMAPFolder ifolder, EntityMessage message, JSONArray jargs, DB db) throws MessagingException, JSONException {
        // Delete message
        Message imessage = ifolder.getMessageByUID(message.uid);
        if (imessage == null)
            throw new MessageRemovedException();

        imessage.setFlag(Flags.Flag.DELETED, true);
        ifolder.expunge();

        db.message().deleteMessage(message.id);
    }

    private void doSend(DB db, EntityMessage message) throws MessagingException {
        // Send message
        EntityIdentity ident = db.identity().getIdentity(message.identity);
        EntityMessage reply = (message.replying == null ? null : db.message().getMessage(message.replying));
        List<EntityAttachment> attachments = db.attachment().getAttachments(message.id);

        if (!ident.synchronize) {
            // Message will remain in outbox
            return;
        }

        // Create session
        Properties props = MessageHelper.getSessionProperties();
        Session isession = Session.getInstance(props, null);

        // Create message
        MimeMessage imessage;
        if (reply == null)
            imessage = MessageHelper.from(this, message, attachments, isession);
        else
            imessage = MessageHelper.from(this, message, reply, attachments, isession);
        if (ident.replyto != null)
            imessage.setReplyTo(new Address[]{new InternetAddress(ident.replyto)});

        // Create transport
        // TODO: cache transport?
        Transport itransport = isession.getTransport(ident.starttls ? "smtp" : "smtps");
        try {
            // Connect transport
            itransport.connect(ident.host, ident.port, ident.user, ident.password);

            // Send message
            try {
                Address[] to = imessage.getAllRecipients();
                itransport.sendMessage(imessage, to);
                Log.i(Helper.TAG, "Sent via " + ident.host + "/" + ident.user +
                        " to " + TextUtils.join(", ", to));
            } catch (SMTPSendFailedException ex) {
                // TODO: response codes: https://www.ietf.org/rfc/rfc821.txt
                db.message().setMessageError(message.id, Helper.formatThrowable(ex));
                throw ex;
            }

            try {
                db.beginTransaction();

                // Move message to sent
                EntityFolder sent = db.folder().getFolderByType(ident.account, EntityFolder.SENT);
                if (sent == null)
                    ; // Leave message in outbox
                else {
                    message.folder = sent.id;
                    message.uid = null;
                }

                // Update state
                if (message.thread == null)
                    message.thread = imessage.getMessageID();
                message.sent = imessage.getSentDate().getTime();
                message.seen = true;
                message.ui_seen = true;
                db.message().updateMessage(message);

                //if (sent != null)
                //    EntityOperation.queue(db, message, EntityOperation.ADD); // Could already exist

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            itransport.close();
        }
    }

    private void doAttachment(EntityFolder folder, EntityOperation op, IMAPFolder ifolder, EntityMessage message, JSONArray jargs, DB db) throws JSONException, MessagingException, IOException {
        // Download attachment
        int sequence = jargs.getInt(0);

        EntityAttachment attachment = db.attachment().getAttachment(op.message, sequence);
        if (attachment == null)
            return;

        try {
            // Get message
            Message imessage = ifolder.getMessageByUID(message.uid);
            if (imessage == null)
                throw new MessageRemovedException();

            // Get attachment
            MessageHelper helper = new MessageHelper((MimeMessage) imessage);
            EntityAttachment a = helper.getAttachments().get(sequence - 1);

            // Build filename
            File dir = new File(getFilesDir(), "attachments");
            dir.mkdir();
            File file = new File(dir, Long.toString(attachment.id));

            // Download attachment
            InputStream is = null;
            OutputStream os = null;
            try {
                is = a.part.getInputStream();
                os = new BufferedOutputStream(new FileOutputStream(file));

                int size = 0;
                byte[] buffer = new byte[ATTACHMENT_BUFFER_SIZE];
                for (int len = is.read(buffer); len != -1; len = is.read(buffer)) {
                    size += len;
                    os.write(buffer, 0, len);

                    // Update progress
                    if (attachment.size != null)
                        db.attachment().setProgress(attachment.id, size * 100 / attachment.size);
                }

                // Store attachment data
                attachment.size = size;
                attachment.progress = null;
                attachment.filename = file.getName();
                db.attachment().updateAttachment(attachment);
            } finally {
                try {
                    if (is != null)
                        is.close();
                } finally {
                    if (os != null)
                        os.close();
                }
            }
            Log.i(Helper.TAG, folder.name + " downloaded bytes=" + attachment.size);
        } catch (Throwable ex) {
            // Reset progress on failure
            attachment.progress = null;
            attachment.filename = null;
            db.attachment().updateAttachment(attachment);
            throw ex;
        }
    }

    private void synchronizeFolders(EntityAccount account, IMAPStore istore) throws MessagingException {
        try {
            Log.i(Helper.TAG, "Start sync folders");

            DB db = DB.getInstance(this);

            List<String> names = new ArrayList<>();
            for (EntityFolder folder : db.folder().getUserFolders(account.id))
                names.add(folder.name);
            Log.i(Helper.TAG, "Local folder count=" + names.size());

            Folder[] ifolders = istore.getDefaultFolder().list("*"); // TODO: is the pattern correct?
            Log.i(Helper.TAG, "Remote folder count=" + ifolders.length);

            for (Folder ifolder : ifolders) {
                String[] attrs = ((IMAPFolder) ifolder).getAttributes();
                boolean selectable = true;
                for (String attr : attrs) {
                    if ("\\Noselect".equals(attr)) { // TODO: is this attribute correct?
                        selectable = false;
                        break;
                    }
                    if (attr.startsWith("\\"))
                        if (EntityFolder.SYSTEM_FOLDER_ATTR.contains(attr.substring(1))) {
                            selectable = false;
                            break;
                        }
                }

                if (selectable) {
                    Log.i(Helper.TAG, ifolder.getFullName() + " candidate attr=" + TextUtils.join(",", attrs));
                    EntityFolder folder = db.folder().getFolderByName(account.id, ifolder.getFullName());
                    if (folder == null) {
                        folder = new EntityFolder();
                        folder.account = account.id;
                        folder.name = ifolder.getFullName();
                        folder.type = EntityFolder.USER;
                        folder.synchronize = false;
                        folder.after = EntityFolder.DEFAULT_USER_SYNC;
                        db.folder().insertFolder(folder);
                        Log.i(Helper.TAG, folder.name + " added");
                    } else
                        names.remove(folder.name);
                }
            }

            Log.i(Helper.TAG, "Delete local folder=" + names.size());
            for (String name : names)
                db.folder().deleteFolder(account.id, name);
        } finally {
            Log.i(Helper.TAG, "End sync folder");
        }
    }

    private void synchronizeMessages(EntityFolder folder, IMAPFolder ifolder) throws MessagingException, JSONException, IOException {
        try {
            Log.i(Helper.TAG, folder.name + " start sync after=" + folder.after);

            DB db = DB.getInstance(this);

            // Get reference times
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -folder.after);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);

            long ago = cal.getTimeInMillis();
            Log.i(Helper.TAG, folder.name + " ago=" + new Date(ago));

            // Delete old local messages
            int old = db.message().deleteMessagesBefore(folder.id, ago);
            Log.i(Helper.TAG, folder.name + " local old=" + old);

            // Get list of local uids
            List<Long> uids = db.message().getUids(folder.id, ago);
            Log.i(Helper.TAG, folder.name + " local count=" + uids.size());

            // Reduce list of local uids
            long search = SystemClock.elapsedRealtime();
            Message[] imessages = ifolder.search(new ReceivedDateTerm(ComparisonTerm.GE, new Date(ago)));
            Log.i(Helper.TAG, folder.name + " remote count=" + imessages.length +
                    " search=" + (SystemClock.elapsedRealtime() - search) + " ms");

            FetchProfile fp = new FetchProfile();
            fp.add(UIDFolder.FetchProfileItem.UID);
            fp.add(IMAPFolder.FetchProfileItem.FLAGS);
            ifolder.fetch(imessages, fp);

            long fetch = SystemClock.elapsedRealtime();
            Log.i(Helper.TAG, folder.name + " remote fetched=" + (SystemClock.elapsedRealtime() - fetch) + " ms");

            for (Message imessage : imessages)
                try {
                    uids.remove(ifolder.getUID(imessage));
                } catch (MessageRemovedException ex) {
                    Log.w(Helper.TAG, folder.name + " " + ex + "\n" + Log.getStackTraceString(ex));
                }

            // Delete local messages not at remote
            Log.i(Helper.TAG, folder.name + " delete=" + uids.size());
            for (Long uid : uids) {
                int count = db.message().deleteMessage(folder.id, uid);
                Log.i(Helper.TAG, folder.name + " delete local uid=" + uid + " count=" + count);
            }

            // Add/update local messages
            int added = 0;
            int updated = 0;
            int unchanged = 0;
            Log.i(Helper.TAG, folder.name + " add=" + imessages.length);
            for (Message imessage : imessages)
                try {
                    int status = synchronizeMessage(folder, ifolder, (IMAPMessage) imessage);
                    if (status > 0)
                        added++;
                    else if (status < 0)
                        updated++;
                    else
                        unchanged++;
                } catch (MessageRemovedException ex) {
                    Log.w(Helper.TAG, folder.name + " " + ex + "\n" + Log.getStackTraceString(ex));
                }

            Log.w(Helper.TAG, folder.name + " statistics added=" + added + " updated=" + updated + " unchanged=" + unchanged);
        } finally {
            Log.i(Helper.TAG, folder.name + " end sync");
        }
    }

    private int synchronizeMessage(EntityFolder folder, IMAPFolder ifolder, IMAPMessage imessage) throws MessagingException, JSONException, IOException {
        long uid = -1;
        try {
            FetchProfile fp = new FetchProfile();
            fp.add(UIDFolder.FetchProfileItem.UID);
            fp.add(IMAPFolder.FetchProfileItem.FLAGS);
            ifolder.fetch(new Message[]{imessage}, fp);

            uid = ifolder.getUID(imessage);
            Log.v(Helper.TAG, folder.name + " start sync uid=" + uid);

            if (imessage.isExpunged()) {
                Log.i(Helper.TAG, folder.name + " expunged uid=" + uid);
                return 0;
            }
            if (imessage.isSet(Flags.Flag.DELETED)) {
                Log.i(Helper.TAG, folder.name + " deleted uid=" + uid);
                return 0;
            }

            MessageHelper helper = new MessageHelper(imessage);
            boolean seen = helper.getSeen();

            DB db = DB.getInstance(this);
            try {
                int result = 0;

                db.beginTransaction();

                // Find message by uid (fast, no headers required)
                EntityMessage message = db.message().getMessageByUid(folder.id, uid);

                // Find message by Message-ID (slow, headers required)
                // - messages in inbox have same id as message sent to self
                // - messages in archive have same id as original
                if (message == null) {
                    // Will fetch headers within database transaction
                    String msgid = imessage.getMessageID();
                    message = db.message().getMessageByMsgId(msgid);
                    if (message != null) {
                        EntityFolder mfolder = db.folder().getFolder(message.folder);
                        Log.i(Helper.TAG, folder.name + " found as id=" + message.id +
                                " folder=" + mfolder.type + ":" + message.folder + "/" + folder.type + ":" + folder.id);
                        if (message.folder.equals(folder.id) || EntityFolder.OUTBOX.equals(mfolder.type)) {
                            Log.i(Helper.TAG, folder.name + " found as id=" + message.id + " uid=" + message.uid + " msgid=" + msgid);
                            message.folder = folder.id;
                            message.uid = uid;
                            db.message().updateMessage(message);
                            result = -1;
                        } else
                            message = null;
                    }
                }

                if (message != null) {
                    if (message.seen != seen) {
                        message.seen = seen;
                        message.ui_seen = seen;
                        db.message().updateMessage(message);
                        Log.v(Helper.TAG, folder.name + " updated id=" + message.id + " uid=" + message.uid + " seen=" + seen);
                        result = -1;
                    } else
                        Log.v(Helper.TAG, folder.name + " unchanged id=" + message.id + " uid=" + message.uid);
                }

                db.setTransactionSuccessful();

                if (message != null)
                    return result;

            } finally {
                db.endTransaction();
            }

            FetchProfile fp1 = new FetchProfile();
            fp1.add(FetchProfile.Item.ENVELOPE);
            fp1.add(FetchProfile.Item.CONTENT_INFO);
            fp1.add(IMAPFolder.FetchProfileItem.HEADERS);
            fp1.add(IMAPFolder.FetchProfileItem.MESSAGE);
            ifolder.fetch(new Message[]{imessage}, fp1);

            EntityMessage message = new EntityMessage();
            message.account = folder.account;
            message.folder = folder.id;
            message.uid = uid;

            if (!EntityFolder.ARCHIVE.equals(folder.type)) {
                message.msgid = helper.getMessageID();
                if (TextUtils.isEmpty(message.msgid))
                    Log.w(Helper.TAG, "No Message-ID id=" + message.id + " uid=" + message.uid);
            }

            message.references = TextUtils.join(" ", helper.getReferences());
            message.inreplyto = helper.getInReplyTo();
            message.thread = helper.getThreadId(uid);
            message.from = helper.getFrom();
            message.to = helper.getTo();
            message.cc = helper.getCc();
            message.bcc = helper.getBcc();
            message.reply = helper.getReply();
            message.subject = imessage.getSubject();
            message.body = helper.getHtml();
            message.received = imessage.getReceivedDate().getTime();
            message.sent = (imessage.getSentDate() == null ? null : imessage.getSentDate().getTime());
            message.seen = seen;
            message.ui_seen = seen;
            message.ui_hide = false;

            message.id = db.message().insertMessage(message);
            Log.v(Helper.TAG, folder.name + " added id=" + message.id + " uid=" + message.uid);

            int sequence = 0;
            for (EntityAttachment attachment : helper.getAttachments()) {
                sequence++;
                Log.i(Helper.TAG, "attachment seq=" + sequence +
                        " name=" + attachment.name + " type=" + attachment.type);
                attachment.message = message.id;
                attachment.sequence = sequence;
                attachment.id = db.attachment().insertAttachment(attachment);
            }

            return 1;

        } finally {
            Log.v(Helper.TAG, folder.name + " end sync uid=" + uid);
        }
    }

    private void noop(EntityFolder folder, final IMAPFolder ifolder) throws MessagingException {
        Log.i(Helper.TAG, folder.name + " request NOOP");
        ifolder.doCommand(new IMAPFolder.ProtocolCommand() {
            public Object doCommand(IMAPProtocol p) throws ProtocolException {
                Log.i(Helper.TAG, ifolder.getName() + " start NOOP");
                p.simpleCommand("NOOP", null);
                Log.i(Helper.TAG, ifolder.getName() + " end NOOP");
                return null;
            }
        });
    }

    ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        ServiceState state = new ServiceState();
        private Thread main;
        private EntityFolder outbox = null;

        @Override
        public void onAvailable(Network network) {
            Log.i(Helper.TAG, "Available " + network);

            synchronized (state) {
                state.running = true;
            }

            main = new Thread(new Runnable() {
                private List<Thread> threads = new ArrayList<>();

                @Override
                public void run() {
                    DB db = DB.getInstance(ServiceSynchronize.this);
                    try {
                        List<EntityAccount> accounts = db.account().getAccounts(true);
                        if (accounts.size() == 0) {
                            Log.i(Helper.TAG, "No accounts, halt");
                            stopSelf();
                        } else
                            for (final EntityAccount account : accounts) {
                                Log.i(Helper.TAG, account.host + "/" + account.user + " run");

                                Thread t = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            monitorAccount(account, state);
                                        } catch (Throwable ex) {
                                            // Fall-safe
                                            Log.e(Helper.TAG, ex + "\n" + Log.getStackTraceString(ex));
                                        }
                                    }
                                }, "sync.account." + account.id);
                                t.start();
                                threads.add(t);
                            }
                    } catch (Throwable ex) {
                        // Failsafe
                        Log.e(Helper.TAG, ex + "\n" + Log.getStackTraceString(ex));
                    }

                    outbox = db.folder().getOutbox();
                    if (outbox != null) try {
                        IntentFilter f = new IntentFilter(ACTION_PROCESS_OPERATIONS);
                        f.addDataType("account/outbox");
                        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(ServiceSynchronize.this);
                        lbm.registerReceiver(outboxReceiver, f);

                        lbm.sendBroadcast(new Intent(ACTION_PROCESS_OPERATIONS)
                                .setType("account/outbox")
                                .putExtra("folder", outbox.id));
                    } catch (Throwable ex) {
                        Log.e(Helper.TAG, ex + "\n" + Log.getStackTraceString(ex));
                    }

                    for (Thread t : threads)
                        try {
                            Log.i(Helper.TAG, "Joining " + t.getName());
                            t.join();
                            Log.i(Helper.TAG, "Joined " + t.getName());
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    threads.clear();
                }
            }, "sync.main");
            main.start();
        }

        @Override
        public void onLost(Network network) {
            Log.i(Helper.TAG, "Lost " + network);

            synchronized (state) {
                state.running = false;
                state.notifyAll();
            }

            if (outbox != null) {
                LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(ServiceSynchronize.this);
                lbm.unregisterReceiver(outboxReceiver);
                Log.i(Helper.TAG, outbox.name + " unlisten operations");
            }

            try {
                Log.i(Helper.TAG, "Joining " + main.getName());
                main.join();
                Log.i(Helper.TAG, "Joined " + main.getName());
            } catch (InterruptedException ex) {
                Log.e(Helper.TAG, ex + "\n" + Log.getStackTraceString(ex));
            }
            main = null;
        }

        BroadcastReceiver outboxReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(Helper.TAG, outbox.name + " run operations");
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Log.i(Helper.TAG, outbox.name + " start operations");
                            processOperations(outbox, null, null, null);
                        } catch (Throwable ex) {
                            Log.e(Helper.TAG, outbox.name + " " + ex + "\n" + Log.getStackTraceString(ex));
                            reportError(null, outbox.name, ex);
                        } finally {
                            Log.i(Helper.TAG, outbox.name + " end operations");
                        }
                    }
                });
            }
        };
    };

    public static void start(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);

            NotificationChannel service = new NotificationChannel(
                    "service",
                    context.getString(R.string.channel_service),
                    NotificationManager.IMPORTANCE_MIN);
            service.setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT);
            nm.createNotificationChannel(service);

            NotificationChannel notification = new NotificationChannel(
                    "notification",
                    context.getString(R.string.channel_notification),
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(notification);

            NotificationChannel error = new NotificationChannel(
                    "error",
                    context.getString(R.string.channel_error),
                    NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(error);
        }

        ContextCompat.startForegroundService(context, new Intent(context, ServiceSynchronize.class));
    }

    private IBinder binder = new LocalBinder();

    private class LocalBinder extends Binder {
        ServiceSynchronize getService() {
            return ServiceSynchronize.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void quit() {
        Log.i(Helper.TAG, "Service quit");
        stopSelf();
    }

    public static void stopSynchroneous(Context context, String reason) {
        Log.i(Helper.TAG, "Stop because of '" + reason + "'");

        final Object lock = new Object();
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder binder) {
                Log.i(Helper.TAG, "Service connected");
                ((LocalBinder) binder).getService().quit();
                synchronized (lock) {
                    lock.notify();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                Log.i(Helper.TAG, "Service disconnected");
                synchronized (lock) {
                    lock.notify();
                }
            }

            @Override
            public void onBindingDied(ComponentName name) {
                Log.i(Helper.TAG, "Service died");
                synchronized (lock) {
                    lock.notify();
                }
            }
        };

        Intent intent = new Intent(context, ServiceSynchronize.class);
        boolean exists = context.getApplicationContext().bindService(intent, connection, Context.BIND_AUTO_CREATE);
        Log.i(Helper.TAG, "Service exists=" + exists);

        if (exists) {
            Log.i(Helper.TAG, "Service stopping");
            try {
                synchronized (lock) {
                    lock.wait();
                }
            } catch (InterruptedException ex) {
                Log.e(Helper.TAG, ex + "\n" + Log.getStackTraceString(ex));
            }

            context.getApplicationContext().unbindService(connection);
        }

        Log.i(Helper.TAG, "Service stopped");
    }

    private class ServiceState {
        boolean running = false;
    }
}
