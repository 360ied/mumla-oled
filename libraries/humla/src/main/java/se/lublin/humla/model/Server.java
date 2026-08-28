/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.model;

import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.util.Patterns;

import org.minidns.hla.ResolverApi;
import org.minidns.hla.SrvResolverResult;
import org.minidns.record.SRV;
import org.minidns.util.SrvUtil;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import se.lublin.humla.Constants;

public class Server implements Parcelable {
    private static final String TAG = Server.class.getName();

    private long mId;
    private String mName;
    private String mHost;
    private int mPort;
    private String mUsername;
    private String mPassword;

    private String mResolvedHost = null;
    private int mResolvedPort;

    public static final Parcelable.Creator<Server> CREATOR = new Parcelable.Creator<Server>() {

        @Override
        public Server createFromParcel(Parcel parcel) {
            return new Server(parcel);
        }

        @Override
        public Server[] newArray(int i) {
            return new Server[i];
        }
    };

    public Server(long id, String name, String host, int port, String username, String password) {
        mId = id;
        mName = name;
        mHost = host;
        mPort = port;
        mUsername = username;
        mPassword = password;
        mResolvedHost = null;
    }

    private Server(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeLong(mId);
        parcel.writeString(mName);
        parcel.writeString(mHost);
        parcel.writeInt(mPort);
        parcel.writeString(mUsername);
        parcel.writeString(mPassword);
    }

    private void readFromParcel(Parcel in) {
        mId = in.readLong();
        mName = in.readString();
        mHost = in.readString();
        mPort = in.readInt();
        mUsername = in.readString();
        mPassword = in.readString();
        mResolvedHost = null;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public long getId() {
        return mId;
    }

    public void setId(long id) {
        mId = id;
    }

    /**
     * Returns a user-defined name for the server, or the host if the user-defined name is not set.
     * @return A user readable name for the server.
     */
    public String getName() {
        return (mName != null && mName.length() > 0) ? mName : mHost;
    }

    public void setName(String mName) {
        this.mName = mName;
    }

    public String getHost() {
        return mHost;
    }

    public void setHost(String mHost) {
        this.mHost = mHost;
        this.mResolvedHost = null;
    }

    public int getPort() {
        return mPort;
    }

    public void setPort(int mPort) {
        this.mPort = mPort;
        this.mResolvedHost = null;
    }

    public String getUsername() {
        return mUsername;
    }

    public void setUsername(String mUsername) {
        this.mUsername = mUsername;
    }

    public String getPassword() {
        return mPassword;
    }

    public void setPassword(String mPassword) {
        this.mPassword = mPassword;
    }

    /**
     * Returns whether or not the server is stored in a database.
     * @return true if the server's ID is in the database.
     */
    public boolean isSaved() {
        return mId != -1;
    }

    public String getSrvHost() {
        if (mResolvedHost != null) {
            return mResolvedHost;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "getSrvHost() called on main thread; returning fallback host without caching.");
            return mHost;
        }
        srvResolve();
        return mResolvedHost != null ? mResolvedHost : mHost;
    }

    public int getSrvPort() {
        if (mResolvedHost != null) {
            return mResolvedPort;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "getSrvPort() called on main thread; returning fallback port without caching.");
            return (mPort != 0) ? mPort : Constants.DEFAULT_PORT;
        }
        srvResolve();
        return mResolvedPort != 0 ? mResolvedPort : ((mPort != 0) ? mPort : Constants.DEFAULT_PORT);
    }

    public synchronized void setResolved(String host, int port) {
        mResolvedHost = host;
        mResolvedPort = port;
    }

    private synchronized void srvResolve() {
        if (mResolvedHost != null) {
            return;
        }
        // if we have a port then don't bother with SRV
        if (mPort != 0) {
            mResolvedHost = mHost;
            mResolvedPort = mPort;
            return;
        }
        // skip also IP addresses and Tor Onion Services (a pseudo-TLD)
        if (Patterns.IP_ADDRESS.matcher(mHost).matches()
                || mHost.contains(":")
                || mHost.endsWith(".onion")) {
            mResolvedHost = mHost;
            mResolvedPort = Constants.DEFAULT_PORT;
            return;
        }
        // Do not block main (UI) thread on synchronous DNS queries
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "srvResolve() called on main thread; skipping synchronous DNS query to prevent ANR.");
            return;
        }
        // set to our fallback values in case of no SRV or resolve fail
        String srvHost = mHost;
        int srvPort = Constants.DEFAULT_PORT;
        try {
            final String lookup = "_mumble._tcp." + srvHost;
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<SrvResolverResult> future = executor.submit(new Callable<SrvResolverResult>() {
                @Override
                public SrvResolverResult call() throws Exception {
                    return ResolverApi.INSTANCE.resolveSrv(lookup);
                }
            });
            SrvResolverResult res = null;
            try {
                res = future.get(800, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                Log.d(TAG, "SRV lookup timed out for " + lookup);
            } finally {
                executor.shutdownNow();
            }
            if (res != null && res.wasSuccessful()) {
                Set<SRV> answers = res.getAnswersOrEmptySet();
                if (answers != null && !answers.isEmpty()) {
                    List<SRV> srvs = SrvUtil.sortSrvRecords(answers);
                    for (SRV srv : srvs) {
                        Log.d(TAG, "resolved " + lookup + " SRV: " + srv.toString());
                        String target = srv.target.toString();
                        if (target.endsWith(".")) {
                            target = target.substring(0, target.length() - 1);
                        }
                        srvHost = target;
                        srvPort = srv.port;
                        break;
                    }
                } else {
                    Log.d(TAG, "resolveSrv " + lookup + ": empty answer");
                }
            } else if (res != null) {
                Log.d(TAG, "resolveSrv " + lookup + ": " + res.getResponseCode());
            }
        } catch (Exception e) {
            Log.d(TAG, "exception in srvResolve: " + e);
        }
        mResolvedHost = srvHost;
        mResolvedPort = srvPort;
    }
}
