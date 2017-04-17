/*
 * This is an Android user space port of DVB-T Linux kernel modules.
 *
 * Copyright (C) 2017 Martin Marinov <martintzvetomirov at gmail com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */

package info.martinmarinov.dvbservice;

import android.app.Activity;
import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.NotificationCompat;
import android.util.Log;

import java.io.Serializable;
import java.util.List;

import info.martinmarinov.drivers.DeviceFilter;
import info.martinmarinov.drivers.DvbDevice;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.usb.DvbUsbDeviceRegistry;
import info.martinmarinov.dvbservice.tools.InetAddressTools;
import info.martinmarinov.dvbservice.tools.TsDumpFileUtils;

import static info.martinmarinov.drivers.DvbException.ErrorCode.CANNOT_OPEN_USB;

public class DvbService extends Service {
    private static final String TAG = DvbService.class.getSimpleName();
    private final static int ONGOING_NOTIFICATION_ID = 743713489; // random id

    public static final String BROADCAST_ACTION = "info.martinmarinov.dvbservice.DvbService.BROADCAST";

    private final static String DEVICE_FILTER = "DeviceFilter";
    private final static String STATUS_MESSAGE = "StatusMessage";

    private static Thread worker;

    static void requestOpen(Activity activity, DeviceFilter deviceFilter) {
        Intent intent = new Intent(activity, DvbService.class)
                .putExtra(DEVICE_FILTER, deviceFilter);
        activity.startService(intent);
    }

    static StatusMessage parseMessage(Intent intent) {
        return (StatusMessage) intent.getSerializableExtra(STATUS_MESSAGE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final DeviceFilter deviceFilter = (DeviceFilter) intent.getSerializableExtra(DEVICE_FILTER);

        // Kill existing connection
        if (worker != null && worker.isAlive()) {
            worker.interrupt();
            try {
                worker.join(5_000L);
            } catch (InterruptedException ignored) {}
            if (worker != null && worker.isAlive()) {
                throw new RuntimeException("Cannot stop existing service");
            }
        }

        worker = new Thread() {
            @Override
            public void run() {
                DvbServer dvbServer = null;
                try {
                    dvbServer = new DvbServer(getDeviceFromFilter(deviceFilter));
                    DvbServerPorts dvbServerPorts = dvbServer.bind(InetAddressTools.getLocalLoopback());
                    dvbServer.open();
                    // Device was opened! Tell client it's time to connect
                    broadcastStatus(new StatusMessage(null, dvbServerPorts, deviceFilter));

                    startForeground();
                    dvbServer.serve();
                } catch (Exception e) {
                    e.printStackTrace();
                    broadcastStatus(new StatusMessage(e, null, deviceFilter));
                } finally {
                    if (dvbServer != null) dvbServer.close();
                }

                Log.d(TAG, "Finished");
                worker = null;
                stopSelf();
            }
        };

        worker.start();
        return START_NOT_STICKY;
    }

    private void startForeground() {
        Notification notification = new NotificationCompat.Builder(this)
                .setContentTitle(getText(R.string.app_name))
                .setContentText(getText(R.string.driver_description))
                .setSmallIcon(R.drawable.ic_notification)
                .build();

        startForeground(ONGOING_NOTIFICATION_ID, notification);
    }

    private DvbDevice getDeviceFromFilter(DeviceFilter deviceFilter) throws DvbException {
        List<DvbDevice> dvbDevices = DvbUsbDeviceRegistry.getUsbDvbDevices(this);
        dvbDevices.addAll(TsDumpFileUtils.getDevicesForAllRecordings(this));

        for (DvbDevice dvbDevice : dvbDevices) {
            if (dvbDevice.getDeviceFilter().equals(deviceFilter)) return dvbDevice;
        }
        throw new DvbException(CANNOT_OPEN_USB, getString(R.string.device_no_longer_available));
    }

    private void broadcastStatus(StatusMessage statusMessage) {
        Intent intent = new Intent(BROADCAST_ACTION)
                        .putExtra(STATUS_MESSAGE, statusMessage);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    class StatusMessage implements Serializable {
        final Exception exception;
        final DvbServerPorts serverAddresses;
        final DeviceFilter deviceFilter;

        private StatusMessage(Exception exception, DvbServerPorts serverAddresses, DeviceFilter deviceFilter) {
            this.exception = exception;
            this.serverAddresses = serverAddresses;
            this.deviceFilter = deviceFilter;
        }
    }
}
