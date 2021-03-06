/*
 * Copyright (c) 2017 OpenLocate
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.openlocate.android.core;

import android.app.ActivityManager;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.ads.identifier.AdvertisingIdClient;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.gcm.GcmNetworkManager;
import com.google.android.gms.gcm.PeriodicTask;
import com.google.android.gms.gcm.Task;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import java.util.HashMap;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;

final class LocationServiceHelper {

    private final static int FOREGROUND_SERVICE_TAG = 1001;

    private final static String TAG = LocationService.class.getSimpleName();
    private final static String LOCATION_DISPATCH_TAG = LocationService.class.getCanonicalName() + ".location_dispatch_task";

    private GoogleApiClient googleApiClient;
    private GcmNetworkManager networkManager;

    private long locationRequestIntervalInSecs;
    private long transmissionIntervalInSecs;
    private LocationAccuracy accuracy;

    private LocationDataSource locations;
    private LocationServiceHelper.LocationListener locationListener;

    private String url;
    private HashMap<String, String> headers;

    private AdvertisingIdClient.Info advertisingInfo;

    private Context context;

    LocationServiceHelper(Context context) {
        this.context = context;
    }

    void onCreate() {
        SQLiteOpenHelper helper = new DatabaseHelper(context);
        locations = new LocationDatabase(helper);
        networkManager = GcmNetworkManager.getInstance(context);

        registerForLocalBroadcastEvents();
    }

    void onDestroy() {
        unschedulePeriodicTasks();
        stopLocationUpdates();
        networkManager = null;
        locations = null;

        if (context.getClass().isInstance(LocationService.class)) {
            ((LocationService) context).stopForeground(true);
        }
    }

    void onStartCommand(Intent intent) {
        setValues(intent);

        /* Starting the service as foreground service. If the service is not foreground,
        service will be killed when the app is killed.
         */
        connectGoogleClient();
        startForeground();
    }

    private BroadcastReceiver locationIntervalChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            setLocationRequestIntervalInSecs(intent);
            resetLocationRequest();
        }
    };

    private BroadcastReceiver transmissionIntervalChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            setTransmissionIntervalInSecs(intent);
            resetTransmissionInterval();
        }
    };

    private BroadcastReceiver locationAccuracyChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            setLocationAccuracy(intent);
            resetLocationRequest();
        }
    };

    private void registerForLocalBroadcastEvents() {
        LocalBroadcastManager.getInstance(context.getApplicationContext()).registerReceiver(
                locationIntervalChangedReceiver,
                new IntentFilter(Constants.LOCATION_INTERVAL_CHANGED));

        LocalBroadcastManager.getInstance(context.getApplicationContext()).registerReceiver(
                transmissionIntervalChangedReceiver,
                new IntentFilter(Constants.TRANSMISSION_INTERVAL_CHANGED));

        LocalBroadcastManager.getInstance(context.getApplicationContext()).registerReceiver(
                locationAccuracyChangedReceiver,
                new IntentFilter(Constants.LOCATION_ACCURACY_CHANGED));
    }

    @SuppressWarnings("unchecked")
    private void setValues(Intent intent) {
        url = intent.getStringExtra(Constants.URL_KEY);
        headers = (HashMap<String, String>) intent.getSerializableExtra(Constants.HEADER_KEY);

        advertisingInfo = new AdvertisingIdClient.Info(
                intent.getStringExtra(Constants.ADVERTISING_ID_KEY),
                intent.getBooleanExtra(Constants.LIMITED_AD_TRACKING_ENABLED_KEY, false)
        );

        setLocationRequestIntervalInSecs(intent);
        setTransmissionIntervalInSecs(intent);
        setLocationAccuracy(intent);
    }

    private void setLocationRequestIntervalInSecs(Intent intent) {
        locationRequestIntervalInSecs = intent.getLongExtra(Constants.LOCATION_INTERVAL_KEY, Constants.DEFAULT_LOCATION_INTERVAL_SEC);
    }

    private void setTransmissionIntervalInSecs(Intent intent) {
        transmissionIntervalInSecs = intent.getLongExtra(Constants.TRANSMISSION_INTERVAL_KEY, Constants.DEFAULT_TRANSMISSION_INTERVAL_SEC);
    }

    private void setLocationAccuracy(Intent intent) {
        accuracy = (LocationAccuracy) intent.getSerializableExtra(Constants.LOCATION_ACCURACY_KEY);
    }

    private void connectGoogleClient() {
        if (googleApiClient == null) {
            googleApiClient = new GoogleApiClient.Builder(context)
                    .addConnectionCallbacks(new ConnectionCallbacks())
                    .addOnConnectionFailedListener(new ConnectionFailedListener())
                    .addApi(LocationServices.API)
                    .build();
        }

        if (!googleApiClient.isConnected()) {
            googleApiClient.connect();
            return;
        }

        startLocationUpdates();
    }

    private LocationRequest getLocationRequest() {
        LocationRequest request = new LocationRequest();

        request.setPriority(accuracy.getLocationRequestAccuracy());
        request.setInterval(locationRequestIntervalInSecs * 1000);
        request.setFastestInterval(Constants.DEFAULT_FAST_LOCATION_INTERVAL_SEC * 1000);

        return request;
    }

    private void startLocationUpdates() {
        try {
            LocationRequest request = getLocationRequest();
            locationListener = new LocationListener();
            LocationServices.FusedLocationApi.requestLocationUpdates(googleApiClient, request, locationListener);
            schedulePeriodicTasks();
        } catch (SecurityException e) {
            locationListener = null;
            Log.e(TAG, e.getMessage());
        }
    }

    private void stopLocationUpdates() {
        if (googleApiClient != null) {
            LocationServices.FusedLocationApi.removeLocationUpdates(googleApiClient, locationListener);
        }
        locationListener = null;
    }

    private void schedulePeriodicTasks() {
        scheduleDispatchLocationService();
    }

    private void scheduleDispatchLocationService() {
        Bundle bundle = new Bundle();
        bundle.putString(Constants.URL_KEY, url);
        bundle.putString(Constants.HEADER_KEY, headers.toString());

        PeriodicTask task = new PeriodicTask.Builder()
                .setExtras(bundle)
                .setService(DispatchLocationService.class)
                .setPeriod(transmissionIntervalInSecs)
                .setRequiredNetwork(Task.NETWORK_STATE_CONNECTED)
                .setRequiresCharging(false)
                .setPersisted(true)
                .setUpdateCurrent(true)
                .setTag(LOCATION_DISPATCH_TAG)
                .build();

        networkManager.schedule(task);
    }

    private void unschedulePeriodicTasks() {
        if (networkManager != null) {
            networkManager.cancelAllTasks(DispatchLocationService.class);
        }
    }

    // Google Api Client Connection Callback
    private class ConnectionCallbacks implements GoogleApiClient.ConnectionCallbacks {

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            startLocationUpdates();
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.e(TAG, "Google Api Client Connection Suspended : " + i);
        }
    }

    // Connection Failed Listener Class
    private class ConnectionFailedListener implements GoogleApiClient.OnConnectionFailedListener {

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.e(TAG, "Google API Client failed to connect. Result = " + connectionResult.getErrorMessage());
        }
    }

    // OpenLocateLocation Listener class
    private class LocationListener implements com.google.android.gms.location.LocationListener {

        @Override
        public void onLocationChanged(Location location) {

            Log.v(TAG, location.toString());
            locations.add(
                    OpenLocateLocation.from(
                            location,
                            advertisingInfo,
                            DeviceInfo.from(context),
                            NetworkInfo.from(context),
                            LocationProvider.getLocationProvider(context),
                            LocationContext.getLocationContext()
                    )
            );
            Log.v(TAG, "COUNT - " + locations.size());

        }
    }

    private void startForeground() {
        Notification notification = new Notification.Builder(context)
                    .build();
        ((LocationService) context).startForeground(FOREGROUND_SERVICE_TAG, notification);
    }

    private void resetLocationRequest() {
        stopLocationUpdates();
        startLocationUpdates();
    }

    private void resetTransmissionInterval() {
        unschedulePeriodicTasks();
        schedulePeriodicTasks();
    }

    long getLocationRequestIntervalInSecs() {
        return locationRequestIntervalInSecs;
    }

    long getTransmissionIntervalInSecs() {
        return transmissionIntervalInSecs;
    }

    LocationAccuracy getAccuracy() {
        return accuracy;
    }

    LocationDataSource getLocations() {
        return locations;
    }

    String getUrl() {
        return url;
    }

    HashMap<String, String> getHeaders() {
        return headers;
    }

    AdvertisingIdClient.Info getAdvertisingInfo() {
        return advertisingInfo;
    }

    GoogleApiClient getGoogleApiClient() {
        return googleApiClient;
    }

    GcmNetworkManager getNetworkManager() {
        return networkManager;
    }

    LocationListener getLocationListener() {
        return locationListener;
    }
}
