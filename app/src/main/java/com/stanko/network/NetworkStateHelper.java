package com.stanko.network;

import android.content.Context;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Looper;
import android.text.TextUtils;

import com.stanko.tools.BackgroundThreadFactory;
import com.stanko.tools.BooleanLock;
import com.stanko.tools.Log;
import com.stanko.tools.StoppableThread;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Stack;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Created by Stan Koshutsky <stan.koshutsky@gmail.com> on 03.09.2015.
 */
public class NetworkStateHelper {

    public static final int TIME_OUT = 1000 * 2; //2s

//    static final BooleanLock checkIfHostRespondsLock = new BooleanLock();

    private static Context sAppContext;
    private static ConnectivityManager sConnectivityManager;
    private static String sHostToCheck;
    private static boolean isNetworkConnectionAvailable;
    private static Boolean isHostReachable;

    static final Executor sExecutorService = Executors.newSingleThreadExecutor(new BackgroundThreadFactory());
//    private static StoppableThread sCheckIfHostRespondsThread;
    private static final AtomicInteger aiCheckIfHostRespondsThreadsCount = new AtomicInteger();
    private static final Stack<Runnable> sCheckIfHostRespondsTasks = new Stack<>();

    private static NetworkStateReceiver sNetworkStateReceiver;

    public static synchronized void init(final Context context) {
        if (sAppContext == null)
            init(context, null);
    }

    public static synchronized void init(final Context context, final String hostToCheck) {
        if (sAppContext == null)
            sAppContext = context.getApplicationContext();
        isNetworkConnectionAvailable = isAnyNetworkConnectionAvailable();
        sHostToCheck = TextUtils.isEmpty(hostToCheck) ? null : hostToCheck;
        if (setConnectivityManager())
            registerReceiver(); // will trigger handleNetworkState() method
    }

    public static synchronized void registerReceiver() {
        if (sNetworkStateReceiver != null)
            return;
        final IntentFilter mIFNetwork = getReceiverIntentFilter();
        sNetworkStateReceiver = new NetworkStateReceiver(sAppContext, sConnectivityManager);
        sAppContext.registerReceiver(sNetworkStateReceiver, mIFNetwork);
    }

    @Override
    protected void finalize() throws Throwable {
        unregisterReceiver();
        super.finalize();
    }

    public static synchronized void unregisterReceiver() {
        if (sNetworkStateReceiver != null)
            sAppContext.unregisterReceiver(sNetworkStateReceiver);
        sNetworkStateReceiver = null;
    }

    /**
     * returns NetworkStateReceiver
     *
     * @param context - Application Context
     * @return NetworkStateReceiver
     */
    public static NetworkStateReceiver getReceiver(final Context context) {
        if (sAppContext == null)
            init(context);
        return new NetworkStateReceiver(sAppContext, sConnectivityManager);
    }

    /**
     * returns NetworkStateReceiver
     *
     * @param context - Application Context
     * @param host    - the host to check availability of
     * @return NetworkStateReceiver
     */
    public static NetworkStateReceiver getReceiver(final Context context, final String host) {
        if (sAppContext == null)
            init(context, host);
        else
            sHostToCheck = host;
        return new NetworkStateReceiver(sAppContext, sConnectivityManager);
    }

    public static void setHostToCheck(final String hostToCheck) {
        NetworkStateHelper.sHostToCheck = hostToCheck;
    }

    /**
     * Returns android.net.ConnectivityManager.CONNECTIVITY_ACTION ("android.net.conn.CONNECTIVITY_CHANGE")
     * as a IntentFilter action
     *
     * @return IntentFilter
     */
    public static IntentFilter getReceiverIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION); // "android.net.conn.CONNECTIVITY_CHANGE"
        return intentFilter;
    }

    /**
     * Checks if currently device has any network connection without ensuring it has Internet access.
     *
     * @return true if connection persists or false otherwise
     */
    public static boolean isNetworkAvailable() {
        Log.i("isNetworkAvailable(): isNetworkConnectionAvailable: " + isNetworkConnectionAvailable + " isAnyNetworkConnectionAvailable(): " + isAnyNetworkConnectionAvailable());
        //TODO: isNetworkConnectionAvailable = isAnyNetworkConnectionAvailable(); //?
        if (!TextUtils.isEmpty(sHostToCheck) && isNetworkConnectionAvailable && isHostReachable != null) {
//            checkIfHostResponds(sLastNetworkState, NetworkState.NRGotNetwork, null, null);
            if (!isHostReachable) {
                // emulate was no network and now we got it
                checkIfHostResponds(NetworkState.NRNoNetwork, NetworkState.NRGotNetwork, "", "");
            }
            return isHostReachable;
        }
        return isNetworkConnectionAvailable;
    }

    /**
     * Called from NetworkStateReceiver
     *
     * @param wasNetworkAvailable
     * @param isNetworkAvailable
     * @param lastNetworkState
     * @param newNetworkState
     * @param newNetworkID
     * @param lastNetworkID
     */
    static synchronized void handleNetworkState(final boolean wasNetworkAvailable,
                                                final boolean isNetworkAvailable,
                                                final NetworkState lastNetworkState,
                                                final NetworkState newNetworkState,
                                                final String newNetworkID,
                                                final String lastNetworkID) {
        Log.i("wasNetworkAvailable: " + wasNetworkAvailable
                + " isNetworkAvailable: " + isNetworkAvailable
                + " lastNetworkState: " + lastNetworkState
                + " newNetworkState: " + newNetworkState
                + " newNetworkID: " + newNetworkID
                + " lastNetworkID: " + lastNetworkID
        );
        if (isNetworkConnectionAvailable != isNetworkAvailable) {
            Log.i("isNetworkAvailable(): isNetworkConnectionAvailable: " + isNetworkConnectionAvailable + " isAnyNetworkConnectionAvailable(): " + isAnyNetworkConnectionAvailable());
            isNetworkConnectionAvailable = isAnyNetworkConnectionAvailable();
        }
        if (TextUtils.equals(lastNetworkID, newNetworkID) && lastNetworkState == newNetworkState && wasNetworkAvailable == isNetworkAvailable) {
            Log.i("same state -> ignoring");
            // if host was set and there is connection available but isHostReachable was not checked
            // yet (null) - we should not return
            if (TextUtils.isEmpty(sHostToCheck) || isHostReachable != null || !isNetworkConnectionAvailable)
                return;
        }
        // check if last lastNetworkID is WiFi's one (SSID/BSSID) and is it the same
        // or we switched from one WiFi network to another (networkId is changed)
        // cuz if its WiFi but networkId is changed - it is another WiFi network
        // and it could be a LAN via WiFi (with no Internet connection available)
        // boolean isWiFiNetworkChanged = !newNetworkID.equals(lastNetworkID);

//        sLastNetworkState = newNetworkState;
        if (isNetworkAvailable && !TextUtils.isEmpty(sHostToCheck)) {
            // first check host then post Event bia EventBus
            checkIfHostResponds(lastNetworkState, newNetworkState, lastNetworkID, newNetworkID);
        } else {
            // no host to check - post Event about connectivity change
            final EventBus eventBus = EventBus.getDefault();
            if (eventBus.hasSubscriberForEvent(NetworkStateReceiverEvent.class)) {
                eventBus.post(new NetworkStateReceiverEvent(
                        wasNetworkAvailable,
                        isNetworkAvailable,
                        isHostReachable == null ? false : isHostReachable,
                        lastNetworkState,
                        newNetworkState,
                        lastNetworkID,
                        newNetworkID));
            }
        }
    }

    /**
     * Handles request to Host to check if it responds called by NetworkStateReceiver
     *
     * @param lastNetworkState
     * @param newNetworkState
     * @param lastNetworkID
     * @param newNetworkID
     */
    static void checkIfHostResponds(final NetworkState lastNetworkState,
                                    final NetworkState newNetworkState,
                                    final String lastNetworkID,
                                    final String newNetworkID) {
        if (TextUtils.isEmpty(sHostToCheck)) {
            Log.i("NetworkStateHelper: Can't start checkIfHostResponds() task - HostToCheck not set");
            return;
        }

        if (isNetworkConnectionAvailable && lastNetworkState == newNetworkState
                && TextUtils.equals(lastNetworkID, newNetworkID)) {
            Log.i("Wont start checkIfHostResponds() task - same NetworkState or NetworkID");
            return;
        }
        // Creating and starting a thread for sending a request to Host
        final NSHRunnable checkIfHostRespondsTask = new NSHRunnable() {
            @Override
            public void run() {
                isRunning = true;
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                Looper.prepare();
                // following method call could freeze for {TIME_OUT} seconds
                final boolean doesHostRespond = isHostReachable(sHostToCheck);
                    isHostReachable = doesHostRespond;
                    final EventBus eventBus = EventBus.getDefault();
                    if (eventBus.hasSubscriberForEvent(NetworkStateReceiverEvent.class)) {
                        eventBus.post(new NetworkStateReceiverEvent(false,
                                isNetworkConnectionAvailable,
                                doesHostRespond,
                                lastNetworkState,
                                newNetworkState,
                                lastNetworkID,
                                newNetworkID));
                    }
                aiCheckIfHostRespondsThreadsCount.decrementAndGet();
                synchronized (sCheckIfHostRespondsTasks) {
                    sCheckIfHostRespondsTasks.remove(this); // could be already removed
                    if (sCheckIfHostRespondsTasks.size() > 0) {
                        sExecutorService.execute(sCheckIfHostRespondsTasks.pop());
                    }
                }
                isRunning = false;
                Looper.loop();
            }
        };
        synchronized (sCheckIfHostRespondsTasks) {
            // if there is a queue of tasks - kill all the previous
            // there must be no more than 1-2 tasks at a time
            final boolean hasTasks = sCheckIfHostRespondsTasks.size() > 0;
            if (hasTasks) { // at least 1 task is executing now
                // remove all tasks from stack
                while (sCheckIfHostRespondsTasks.size() > 0)
                    sCheckIfHostRespondsTasks.pop();
            }
            // no queue - add current task and execute it
            sCheckIfHostRespondsTasks.add(checkIfHostRespondsTask);
            // if there was no tasks
            if (!hasTasks)
                sExecutorService.execute(checkIfHostRespondsTask);
        }
    }


    /**
     * Checks host by connection to using HttpURLConnection. Method should not be run
     * on a Main/UIThread otherwise exception will be thrown.
     *
     * @param hostUrl - the host to check. By default http:// prefix will be added if no any
     * @return true if host reachable (connection were established)
     */
    public static boolean isHostReachable(final String hostUrl) {
        boolean doesHostRespond = false;
        try {
            // adding http:// if its just a pure host name like google.com instead of http://google.com
            final URL url = new URL(hostUrl.contains("://") ? hostUrl : "http://" + hostUrl);
//            if (hostUrl.startsWith("https")){
            setTrustAnySSLCertificateMode();
//            }
            final HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();
//            httpURLConnection.setRequestProperty("User-Agent", "Android Application");
            httpURLConnection.setRequestProperty("Connection", "close");
            httpURLConnection.setConnectTimeout(TIME_OUT); // Timeout in seconds
            httpURLConnection.connect();
            final int responseCode = httpURLConnection.getResponseCode();
            // https://en.wikipedia.org/wiki/List_of_HTTP_status_codes
            // there are too many codes, guess checking if its gt 0 is enough
            doesHostRespond = responseCode < 400;
        } catch (MalformedURLException e) {
            e.printStackTrace();
//            isExceptionHappens = true;
        } catch (SocketTimeoutException e) {
            e.printStackTrace();
//            isExceptionHappens = true;
        } catch (IOException e) {
            e.printStackTrace();
//            isExceptionHappens = true;
        } catch (SecurityException e) {
            e.printStackTrace();
            // user could limit Internet access so app could crash here
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (KeyManagementException e) {
            e.printStackTrace();
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
        }
        return doesHostRespond;
    }

    public static void setTrustAnySSLCertificateMode() throws NoSuchAlgorithmException, KeyManagementException {
        HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
            public boolean verify(String hostname, SSLSession session) {
                return true;
            }
        });
        final SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[]{};
                    }
                }
        }, null);
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
    }

    /**
     * Checks if given host responds. Uses callback interface ICheckIfHostResponds
     * Could be run in MainUI/MainThread.
     */
    public static void checkIfHostResponds(final String hostUrl, final ICheckIfHostResponds callback) {
        // Creating and starting a thread for sending a request to Host
        new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                boolean doesHostRespond = isHostReachable(hostUrl);
                // sending check result using callback
                callback.doesHostRespond(doesHostRespond);
                Looper.loop();
            }
        }).start();
    }

/**
 * Interface to deliver result of {@link #checkIfHostResponds(String, ICheckIfHostResponds)}
 */
public interface ICheckIfHostResponds {
    void doesHostRespond(boolean doestIt);

}


    /**
     * Checks if currently device has any network connection without ensuring it has Internet access
     *
     * @return true if connection persists or false otherwise
     */
    public static boolean isActiveNetworkConnectionAvailable(final Context context) {
        if (sAppContext == null)
            sAppContext = context.getApplicationContext();
        return isActiveNetworkConnectionAvailable();
    }

    public static boolean isActiveNetworkConnectionAvailable() {
        if (!setConnectivityManager())
            return false;

        NetworkInfo networkInfo = null;
        try {
            networkInfo = sConnectivityManager.getActiveNetworkInfo();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return networkInfo != null && networkInfo.isConnected();
    }


    /**
     * method checks if any (INTERNET_OVER_WIFI,INTERNET_OVER_MOBILE,
     * INTERNET_OVER_OTHER) network connection is available.
     * 1) issue1: assumes the network connection is an Internet connection
     * 2) issue2: fails if a connection requires a login/password (like hotels WiFi)
     *
     * @return
     */
    public static boolean isAnyNetworkConnectionAvailable(final Context context) {
        if (sAppContext == null)
            sAppContext = context.getApplicationContext();
        return isAnyNetworkConnectionAvailable();
    }

    private static boolean isAnyNetworkConnectionAvailable() {
        if (isActiveNetworkConnectionAvailable(sAppContext))
            return true;

        boolean isInternetWiFi, isInternetMobile, isInternetWiMax, isInternetOther;
//        int connectionType = 0;

        if (!setConnectivityManager())
            return false;

        // determine the type of network
//		int TYPE_BLUETOOTH		The Bluetooth data connection.
//		int TYPE_DUMMY			Dummy data connection.
//		int TYPE_ETHERNET		The Ethernet data connection.
//		int TYPE_MOBILE			The Mobile data connection.
//		int TYPE_MOBILE_DUN		A DUN-specific Mobile data connection.
//		int TYPE_MOBILE_HIPRI	A High Priority Mobile data connection.
//		int TYPE_MOBILE_MMS		An MMS-specific Mobile data connection.
//		int TYPE_MOBILE_SUPL	A SUPL-specific Mobile data connection.
//		int TYPE_WIFI			The WIFI data connection.
//		int TYPE_WIMAX			The WiMAX data connection.

        final NetworkInfo niMobile = sConnectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        final NetworkInfo niWiFi = sConnectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        final NetworkInfo niWiMax = sConnectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIMAX);
        final NetworkInfo niOther = sConnectivityManager.getActiveNetworkInfo();

//        if (niOther != null)
//            connectionType = niOther.getType();

        isInternetWiFi = niWiFi != null && niWiFi.isConnected(); //.getState() == NetworkInfo.State.CONNECTED;
        isInternetMobile = niMobile != null && niMobile.isConnected(); //.getState() == NetworkInfo.State.CONNECTED;
        isInternetWiMax = niWiMax != null && niWiMax.isConnected(); //.getState() == NetworkInfo.State.CONNECTED;
        isInternetOther = niOther != null && niOther.isConnected(); //.getState() == NetworkInfo.State.CONNECTED;

        return (isInternetWiFi || isInternetWiMax || isInternetMobile || isInternetOther);
    }

    /**
     * method checks if an Internet connection is available via WiFi
     * 1) issue1: assumes the WiFi connection is an Internet connection
     * 2) issue2: fails if a WiFi connection requires a login/password
     *
     * @return
     */
    public static boolean isActiveConnectionViaWiFi() {
        if (!setConnectivityManager())
            return false;

        final NetworkInfo niActive = sConnectivityManager.getActiveNetworkInfo();
        if (niActive == null)
            return false;
        final int activeNetworkType = niActive.getType();
        return activeNetworkType == ConnectivityManager.TYPE_WIFI
                || activeNetworkType == ConnectivityManager.TYPE_WIMAX;
    }

    public static boolean isActiveConnectionViaWiFiOrEthernet() {
        if (!setConnectivityManager())
            return false;

        final NetworkInfo niActive = sConnectivityManager.getActiveNetworkInfo();
        if (niActive == null)
            return false;
        final int activeNetworkType = niActive.getType();
        return activeNetworkType == ConnectivityManager.TYPE_WIFI
                || activeNetworkType == ConnectivityManager.TYPE_WIMAX
                || activeNetworkType == ConnectivityManager.TYPE_ETHERNET;
    }

    public static boolean isNetworkAvailableViaWiFi() {

        if (sAppContext == null) {
            throw new NullPointerException("Context is null - did you call init() with valid context?");
        }

        if (isActiveConnectionViaWiFi()) {
            return true;
        }

        boolean isInternetWiFi, isInternetWiMax;

        if (!setConnectivityManager())
            return false;

        // determine the type of network
//		int TYPE_BLUETOOTH		The Bluetooth data connection.
//		int TYPE_DUMMY			Dummy data connection.
//		int TYPE_ETHERNET		The Ethernet data connection.
//		int TYPE_MOBILE			The Mobile data connection.
//		int TYPE_MOBILE_DUN		A DUN-specific Mobile data connection.
//		int TYPE_MOBILE_HIPRI	A High Priority Mobile data connection.
//		int TYPE_MOBILE_MMS		An MMS-specific Mobile data connection.
//		int TYPE_MOBILE_SUPL	A SUPL-specific Mobile data connection.
//		int TYPE_WIFI			The WIFI data connection.
//		int TYPE_WIMAX			The WiMAX data connection.

        final NetworkInfo niWiFi = sConnectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        final NetworkInfo niWiMax = sConnectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIMAX);

        isInternetWiFi = niWiFi != null && niWiFi.isConnected(); //.getState() == NetworkInfo.State.CONNECTED;
        isInternetWiMax = niWiMax != null && niWiMax.isConnected(); //.getState() == NetworkInfo.State.CONNECTED;

        return (isInternetWiFi || isInternetWiMax);
    }

    private static boolean setConnectivityManager() {
        if (sAppContext == null) {
            throw new NullPointerException("Context is null - did you call init() with valid context?");
        }

        if (sConnectivityManager == null) {
            try {
                sConnectivityManager = (ConnectivityManager) sAppContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            } catch (Exception e) {
                Log.e("Unable to retrieve ConnectivityManager using getSystemService(Context.CONNECTIVITY_SERVICE)!");
                e.printStackTrace();
            }
        }

        return sConnectivityManager != null;
    }

}