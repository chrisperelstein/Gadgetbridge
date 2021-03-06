package nodomain.freeyourgadget.gadgetbridge.service.devices.miband;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst;
import nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandDateConverter;
import nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandFWHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandService;
import nodomain.freeyourgadget.gadgetbridge.devices.miband.VibrationProfile;
import nodomain.freeyourgadget.gadgetbridge.impl.GBAlarm;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice.State;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.CalendarEvents;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.ServiceCommand;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BtLEAction;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.AbortTransactionAction;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceStateAction;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.WriteAction;
import nodomain.freeyourgadget.gadgetbridge.service.devices.miband.operations.FetchActivityOperation;
import nodomain.freeyourgadget.gadgetbridge.service.devices.miband.operations.UpdateFirmwareOperation;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.DEFAULT_VALUE_FLASH_COLOUR;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.DEFAULT_VALUE_FLASH_COUNT;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.DEFAULT_VALUE_FLASH_DURATION;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.DEFAULT_VALUE_FLASH_ORIGINAL_COLOUR;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.DEFAULT_VALUE_VIBRATION_COUNT;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.DEFAULT_VALUE_VIBRATION_DURATION;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.DEFAULT_VALUE_VIBRATION_PAUSE;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.DEFAULT_VALUE_VIBRATION_PROFILE;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.FLASH_COLOUR;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.FLASH_COUNT;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.FLASH_DURATION;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.FLASH_ORIGINAL_COLOUR;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.ORIGIN_GENERIC;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.ORIGIN_K9MAIL;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.ORIGIN_PEBBLEMSG;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.ORIGIN_SMS;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.VIBRATION_COUNT;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.VIBRATION_DURATION;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.VIBRATION_PAUSE;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.VIBRATION_PROFILE;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.getNotificationPrefIntValue;
import static nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst.getNotificationPrefStringValue;

public class MiBandSupport extends AbstractBTLEDeviceSupport {

    private static final Logger LOG = LoggerFactory.getLogger(MiBandSupport.class);
    private volatile boolean telephoneRinging;
    private volatile boolean isLocatingDevice;

    private DeviceInfo mDeviceInfo;

    private final GBDeviceEventVersionInfo versionCmd = new GBDeviceEventVersionInfo();
    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();

    public MiBandSupport() {
        addSupportedService(GattService.UUID_SERVICE_GENERIC_ACCESS);
        addSupportedService(GattService.UUID_SERVICE_GENERIC_ATTRIBUTE);
        addSupportedService(MiBandService.UUID_SERVICE_MIBAND_SERVICE);
        addSupportedService(GattService.UUID_SERVICE_IMMEDIATE_ALERT);
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.add(new SetDeviceStateAction(getDevice(), State.INITIALIZING, getContext()));
        pair(builder)
                .requestDeviceInfo(builder)
                .sendUserInfo(builder)
                .setWearLocation(builder)
                .setFitnessGoal(builder)
                .enableNotifications(builder, true)
                .setCurrentTime(builder)
                .requestBatteryInfo(builder)
                .setInitialized(builder);

        return builder;
    }

    /**
     * Last action of initialization sequence. Sets the device to initialized.
     * It is only invoked if all other actions were successfully run, so the device
     * must be initialized, then.
     *
     * @param builder
     */
    private void setInitialized(TransactionBuilder builder) {
        builder.add(new SetDeviceStateAction(getDevice(), State.INITIALIZED, getContext()));
    }

    // TODO: tear down the notifications on quit
    private MiBandSupport enableNotifications(TransactionBuilder builder, boolean enable) {
        builder.notify(getCharacteristic(MiBandService.UUID_CHARACTERISTIC_NOTIFICATION), enable)
                .notify(getCharacteristic(MiBandService.UUID_CHARACTERISTIC_REALTIME_STEPS), enable)
                .notify(getCharacteristic(MiBandService.UUID_CHARACTERISTIC_ACTIVITY_DATA), enable)
                .notify(getCharacteristic(MiBandService.UUID_CHARACTERISTIC_BATTERY), enable)
                .notify(getCharacteristic(MiBandService.UUID_CHARACTERISTIC_SENSOR_DATA), enable);

        return this;
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    public void pair() {
        for (int i = 0; i < 5; i++) {
            if (connect()) {
                return;
            }
        }
    }

    public DeviceInfo getDeviceInfo() {
        return mDeviceInfo;
    }

    private MiBandSupport sendDefaultNotification(TransactionBuilder builder, short repeat, BtLEAction extraAction) {
        LOG.info("Sending notification to MiBand: (" + repeat + " times)");
        NotificationStrategy strategy = getNotificationStrategy();
        for (short i = 0; i < repeat; i++) {
            strategy.sendDefaultNotification(builder, extraAction);
        }
        return this;
    }

    /**
     * Adds a custom notification to the given transaction builder
     *
     * @param vibrationProfile specifies how and how often the Band shall vibrate.
     * @param flashTimes
     * @param flashColour
     * @param originalColour
     * @param flashDuration
     * @param extraAction      an extra action to be executed after every vibration and flash sequence. Allows to abort the repetition, for example.
     * @param builder
     */
    private MiBandSupport sendCustomNotification(VibrationProfile vibrationProfile, int flashTimes, int flashColour, int originalColour, long flashDuration, BtLEAction extraAction, TransactionBuilder builder) {
        getNotificationStrategy().sendCustomNotification(vibrationProfile, flashTimes, flashColour, originalColour, flashDuration, extraAction, builder);
        LOG.info("Sending notification to MiBand");
        return this;
    }

    private NotificationStrategy getNotificationStrategy() {
        if (mDeviceInfo == null) {
            // not initialized yet?
            return new NoNotificationStrategy();
        }
        if (mDeviceInfo.getFirmwareVersion() < MiBandFWHelper.FW_16779790) {
            return new V1NotificationStrategy(this);
        } else {
            //use the new alert characteristic
            return new V2NotificationStrategy(this);
        }
    }

    static final byte[] reboot = new byte[]{MiBandService.COMMAND_REBOOT};
    static final byte[] startRealTimeStepsNotifications = new byte[]{MiBandService.COMMAND_SET_REALTIME_STEPS_NOTIFICATION, 1};
    static final byte[] stopRealTimeStepsNotifications = new byte[]{MiBandService.COMMAND_SET_REALTIME_STEPS_NOTIFICATION, 0};

    /**
     * Part of device initialization process. Do not call manually.
     *
     * @param builder
     * @return
     */
    private MiBandSupport sendUserInfo(TransactionBuilder builder) {
        LOG.debug("Writing User Info!");
        // Use a custom action instead of just builder.write() because mDeviceInfo
        // is set by handleDeviceInfo *after* this action is created.
        builder.add(new BtLEAction(getCharacteristic(MiBandService.UUID_CHARACTERISTIC_USER_INFO)) {
            @Override
            public boolean expectsResult() {
                return true;
            }

            @Override
            public boolean run(BluetoothGatt gatt) {
                // at this point, mDeviceInfo should be set
                return new WriteAction(getCharacteristic(),
                        MiBandCoordinator.getAnyUserInfo(getDevice().getAddress()).getData(mDeviceInfo)
                ).run(gatt);
            }
        });
        return this;
    }

    private MiBandSupport requestBatteryInfo(TransactionBuilder builder) {
        LOG.debug("Requesting Battery Info!");
        BluetoothGattCharacteristic characteristic = getCharacteristic(MiBandService.UUID_CHARACTERISTIC_BATTERY);
        builder.read(characteristic);
        return this;
    }

    private MiBandSupport requestDeviceInfo(TransactionBuilder builder) {
        LOG.debug("Requesting Device Info!");
        BluetoothGattCharacteristic deviceInfo = getCharacteristic(MiBandService.UUID_CHARACTERISTIC_DEVICE_INFO);
        builder.read(deviceInfo);
        BluetoothGattCharacteristic deviceName = getCharacteristic(GattCharacteristic.UUID_CHARACTERISTIC_GAP_DEVICE_NAME);
        builder.read(deviceName);
        return this;
    }

    /**
     * Part of device initialization process. Do not call manually.
     *
     * @param transaction
     * @return
     */
    private MiBandSupport pair(TransactionBuilder transaction) {
        LOG.info("Attempting to pair MI device...");
        BluetoothGattCharacteristic characteristic = getCharacteristic(MiBandService.UUID_CHARACTERISTIC_PAIR);
        if (characteristic != null) {
            transaction.write(characteristic, new byte[]{2});
        } else {
            LOG.info("Unable to pair MI device -- characteristic not available");
        }
        return this;
    }

    /**
     * Part of device initialization process. Do not call manually.
     *
     * @param transaction
     * @return
     */

    private MiBandSupport setFitnessGoal(TransactionBuilder transaction) {
        LOG.info("Attempting to set Fitness Goal...");
        BluetoothGattCharacteristic characteristic = getCharacteristic(MiBandService.UUID_CHARACTERISTIC_CONTROL_POINT);
        if (characteristic != null) {
            int fitnessGoal = MiBandCoordinator.getFitnessGoal(getDevice().getAddress());
            transaction.write(characteristic, new byte[]{
                    MiBandService.COMMAND_SET_FITNESS_GOAL,
                    0,
                    (byte) (fitnessGoal & 0xff),
                    (byte) ((fitnessGoal >>> 8) & 0xff)
            });
        } else {
            LOG.info("Unable to set Fitness Goal");
        }
        return this;
    }

    /**
     * Part of device initialization process. Do not call manually.
     *
     * @param transaction
     * @return
     */
    private MiBandSupport setWearLocation(TransactionBuilder transaction) {
        LOG.info("Attempting to set wear location...");
        BluetoothGattCharacteristic characteristic = getCharacteristic(MiBandService.UUID_CHARACTERISTIC_CONTROL_POINT);
        if (characteristic != null) {
            int location = MiBandCoordinator.getWearLocation(getDevice().getAddress());
            transaction.write(characteristic, new byte[]{
                    MiBandService.COMMAND_SET_WEAR_LOCATION,
                    (byte) location
            });
        } else {
            LOG.info("Unable to set Wear Location");
        }
        return this;
    }

    private void performDefaultNotification(String task, short repeat, BtLEAction extraAction) {
        try {
            TransactionBuilder builder = performInitialized(task);
            sendDefaultNotification(builder, repeat, extraAction);
            builder.queue(getQueue());
        } catch (IOException ex) {
            LOG.error("Unable to send notification to MI device", ex);
        }
    }

    private void performPreferredNotification(String task, String notificationOrigin, BtLEAction extraAction) {
        try {
            TransactionBuilder builder = performInitialized(task);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
            int vibrateDuration = getPreferredVibrateDuration(notificationOrigin, prefs);
            int vibratePause = getPreferredVibratePause(notificationOrigin, prefs);
            short vibrateTimes = getPreferredVibrateCount(notificationOrigin, prefs);
            VibrationProfile profile = getPreferredVibrateProfile(notificationOrigin, prefs, vibrateTimes);

            int flashTimes = getPreferredFlashCount(notificationOrigin, prefs);
            int flashColour = getPreferredFlashColour(notificationOrigin, prefs);
            int originalColour = getPreferredOriginalColour(notificationOrigin, prefs);
            int flashDuration = getPreferredFlashDuration(notificationOrigin, prefs);

            sendCustomNotification(profile, flashTimes, flashColour, originalColour, flashDuration, extraAction, builder);
//            sendCustomNotification(vibrateDuration, vibrateTimes, vibratePause, flashTimes, flashColour, originalColour, flashDuration, builder);
            builder.queue(getQueue());
        } catch (IOException ex) {
            LOG.error("Unable to send notification to MI device", ex);
        }
    }

    private int getPreferredFlashDuration(String notificationOrigin, SharedPreferences prefs) {
        return getNotificationPrefIntValue(FLASH_DURATION, notificationOrigin, prefs, DEFAULT_VALUE_FLASH_DURATION);
    }

    private int getPreferredOriginalColour(String notificationOrigin, SharedPreferences prefs) {
        return getNotificationPrefIntValue(FLASH_ORIGINAL_COLOUR, notificationOrigin, prefs, DEFAULT_VALUE_FLASH_ORIGINAL_COLOUR);
    }

    private int getPreferredFlashColour(String notificationOrigin, SharedPreferences prefs) {
        return getNotificationPrefIntValue(FLASH_COLOUR, notificationOrigin, prefs, DEFAULT_VALUE_FLASH_COLOUR);
    }

    private int getPreferredFlashCount(String notificationOrigin, SharedPreferences prefs) {
        return getNotificationPrefIntValue(FLASH_COUNT, notificationOrigin, prefs, DEFAULT_VALUE_FLASH_COUNT);
    }

    private int getPreferredVibratePause(String notificationOrigin, SharedPreferences prefs) {
        return getNotificationPrefIntValue(VIBRATION_PAUSE, notificationOrigin, prefs, DEFAULT_VALUE_VIBRATION_PAUSE);
    }

    private short getPreferredVibrateCount(String notificationOrigin, SharedPreferences prefs) {
        return (short) Math.min(Short.MAX_VALUE, getNotificationPrefIntValue(VIBRATION_COUNT, notificationOrigin, prefs, DEFAULT_VALUE_VIBRATION_COUNT));
    }

    private int getPreferredVibrateDuration(String notificationOrigin, SharedPreferences prefs) {
        return getNotificationPrefIntValue(VIBRATION_DURATION, notificationOrigin, prefs, DEFAULT_VALUE_VIBRATION_DURATION);
    }

    private VibrationProfile getPreferredVibrateProfile(String notificationOrigin, SharedPreferences prefs, short repeat) {
        String profileId = getNotificationPrefStringValue(VIBRATION_PROFILE, notificationOrigin, prefs, DEFAULT_VALUE_VIBRATION_PROFILE);
        return VibrationProfile.getProfile(profileId, repeat);
    }

    @Override
    public void onSetAlarms(ArrayList<? extends Alarm> alarms) {
        try {
            BluetoothGattCharacteristic characteristic = getCharacteristic(MiBandService.UUID_CHARACTERISTIC_CONTROL_POINT);
            TransactionBuilder builder = performInitialized("Set alarm");
            boolean anyAlarmEnabled = false;
            for (Alarm alarm : alarms) {
                anyAlarmEnabled |= alarm.isEnabled();
                queueAlarm(alarm, builder, characteristic);
            }
            builder.queue(getQueue());
            if (anyAlarmEnabled) {
                GB.toast(getContext(), getContext().getString(R.string.user_feedback_miband_set_alarms_ok), Toast.LENGTH_SHORT, GB.INFO);
            } else {
                GB.toast(getContext(), getContext().getString(R.string.user_feedback_all_alarms_disabled), Toast.LENGTH_SHORT, GB.INFO);
            }
        } catch (IOException ex) {
            GB.toast(getContext(), getContext().getString(R.string.user_feedback_miband_set_alarms_failed), Toast.LENGTH_LONG, GB.ERROR, ex);
        }
    }

    @Override
    public void onNotification(NotificationSpec notificationSpec) {
        // FIXME: these ORIGIN contants do not really make sense anymore
        switch (notificationSpec.type) {
            case SMS:
                performPreferredNotification("sms received", ORIGIN_SMS, null);
                break;
            case EMAIL:
                performPreferredNotification("email received", ORIGIN_K9MAIL, null);
                break;
            case CHAT:
                performPreferredNotification("chat message received", ORIGIN_PEBBLEMSG, null);
                break;
            default:
                performPreferredNotification("generic notification received", ORIGIN_GENERIC, null);
        }
    }

    @Override
    public void onSetTime() {
        try {
            TransactionBuilder builder = performInitialized("Set date and time");
            setCurrentTime(builder);
            builder.queue(getQueue());
        } catch (IOException ex) {
            LOG.error("Unable to set time on MI device", ex);
        }
        //TODO: once we have a common strategy for sending events (e.g. EventHandler), remove this call from here. Meanwhile it does no harm.
        sendCalendarEvents();
    }

    /**
     * Sets the current time to the Mi device using the given builder.
     *
     * @param builder
     */
    private MiBandSupport setCurrentTime(TransactionBuilder builder) {
        Calendar now = GregorianCalendar.getInstance();
        Date date = now.getTime();
        LOG.info("Sending current time to Mi Band: " + DateTimeUtils.formatDate(date) + " (" + date.toGMTString() + ")");
        byte[] nowBytes = MiBandDateConverter.calendarToRawBytes(now);
        byte[] time = new byte[]{
                nowBytes[0],
                nowBytes[1],
                nowBytes[2],
                nowBytes[3],
                nowBytes[4],
                nowBytes[5],
                (byte) 0x0f,
                (byte) 0x0f,
                (byte) 0x0f,
                (byte) 0x0f,
                (byte) 0x0f,
                (byte) 0x0f
        };
        BluetoothGattCharacteristic characteristic = getCharacteristic(MiBandService.UUID_CHARACTERISTIC_DATE_TIME);
        if (characteristic != null) {
            builder.write(characteristic, time);
        } else {
            LOG.info("Unable to set time -- characteristic not available");
        }
        return this;
    }

    @Override
    public void onSetCallState(String number, String name, ServiceCommand command) {
        if (ServiceCommand.CALL_INCOMING.equals(command)) {
            telephoneRinging = true;
            AbortTransactionAction abortAction = new AbortTransactionAction() {
                @Override
                protected boolean shouldAbort() {
                    return !isTelephoneRinging();
                }
            };
            performPreferredNotification("incoming call", MiBandConst.ORIGIN_INCOMING_CALL, abortAction);
        } else if (ServiceCommand.CALL_START.equals(command) || ServiceCommand.CALL_END.equals(command)) {
            telephoneRinging = false;
        }
    }

    private boolean isTelephoneRinging() {
        // don't synchronize, this is not really important
        return telephoneRinging;
    }

    @Override
    public void onSetMusicInfo(String artist, String album, String track) {
        // not supported
    }

    @Override
    public void onReboot() {
        try {
            TransactionBuilder builder = performInitialized("Reboot");
            builder.write(getCharacteristic(MiBandService.UUID_CHARACTERISTIC_CONTROL_POINT), reboot);
            builder.queue(getQueue());
        } catch (IOException ex) {
            LOG.error("Unable to reboot MI", ex);
        }
    }

    @Override
    public void onFindDevice(boolean start) {
        isLocatingDevice = start;

        if (start) {
            AbortTransactionAction abortAction = new AbortTransactionAction() {
                @Override
                protected boolean shouldAbort() {
                    return !isLocatingDevice;
                }
            };
            performDefaultNotification("locating device", (short) 255, abortAction);
        }
    }

    @Override
    public void onFetchActivityData() {
        try {
            new FetchActivityOperation(this).perform();
        } catch (IOException ex) {
            LOG.error("Unable to fetch MI activity data", ex);
        }
    }

    @Override
    public void onEnableRealtimeSteps(boolean enable) {
        try {
            BluetoothGattCharacteristic controlPoint = getCharacteristic(MiBandService.UUID_CHARACTERISTIC_CONTROL_POINT);
            if (enable) {
                TransactionBuilder builder = performInitialized("Read realtime steps");
                builder.read(getCharacteristic(MiBandService.UUID_CHARACTERISTIC_REALTIME_STEPS)).queue(getQueue());
            }
            performInitialized(enable ? "Enabling realtime steps notifications" : "Disabling realtime steps notifications")
                    .write(getCharacteristic(MiBandService.UUID_CHARACTERISTIC_LE_PARAMS), enable ? getLowLatency() : getHighLatency())
                    .write(controlPoint, enable ? startRealTimeStepsNotifications : stopRealTimeStepsNotifications).queue(getQueue());
        } catch (IOException e) {
            LOG.error("Unable to change realtime steps notification to: " + enable, e);
        }
    }

    private byte[] getHighLatency() {
        int minConnectionInterval = 460;
        int maxConnectionInterval = 500;
        int latency = 0;
        int timeout = 500;
        int advertisementInterval = 0;

        return getLatency(minConnectionInterval, maxConnectionInterval, latency, timeout, advertisementInterval);
    }

    private byte[] getLatency(int minConnectionInterval, int maxConnectionInterval, int latency, int timeout, int advertisementInterval) {
        byte result[] = new byte[12];
        result[0] = (byte) (minConnectionInterval & 0xff);
        result[1] = (byte) (0xff & minConnectionInterval >> 8);
        result[2] = (byte) (maxConnectionInterval & 0xff);
        result[3] = (byte) (0xff & maxConnectionInterval >> 8);
        result[4] = (byte) (latency & 0xff);
        result[5] = (byte) (0xff & latency >> 8);
        result[6] = (byte) (timeout & 0xff);
        result[7] = (byte) (0xff & timeout >> 8);
        result[8] = 0;
        result[9] = 0;
        result[10] = (byte) (advertisementInterval & 0xff);
        result[11] = (byte) (0xff & advertisementInterval >> 8);

        return result;
    }

    private byte[] getLowLatency() {
        int minConnectionInterval = 39;
        int maxConnectionInterval = 49;
        int latency = 0;
        int timeout = 500;
        int advertisementInterval = 0;

        return getLatency(minConnectionInterval, maxConnectionInterval, latency, timeout, advertisementInterval);
    }

    @Override
    public void onInstallApp(Uri uri) {
        try {
            new UpdateFirmwareOperation(uri, this).perform();
        } catch (IOException ex) {
            GB.toast(getContext(), "Firmware cannot be installed: " + ex.getMessage(), Toast.LENGTH_LONG, GB.ERROR, ex);
        }
    }

    @Override
    public void onAppInfoReq() {
        // not supported
    }

    @Override
    public void onAppStart(UUID uuid, boolean start) {
        // not supported
    }

    @Override
    public void onAppDelete(UUID uuid) {
        // not supported
    }

    @Override
    public void onScreenshotReq() {
        // not supported
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt,
                                        BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);

        UUID characteristicUUID = characteristic.getUuid();
        if (MiBandService.UUID_CHARACTERISTIC_BATTERY.equals(characteristicUUID)) {
            handleBatteryInfo(characteristic.getValue(), BluetoothGatt.GATT_SUCCESS);
        } else if (MiBandService.UUID_CHARACTERISTIC_NOTIFICATION.equals(characteristicUUID)) {
            handleNotificationNotif(characteristic.getValue());
        } else if (MiBandService.UUID_CHARACTERISTIC_REALTIME_STEPS.equals(characteristicUUID)) {
            handleRealtimeSteps(characteristic.getValue());
        } else {
            LOG.info("Unhandled characteristic changed: " + characteristicUUID);
            logMessageContent(characteristic.getValue());
        }
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt gatt,
                                     BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicRead(gatt, characteristic, status);

        UUID characteristicUUID = characteristic.getUuid();
        if (MiBandService.UUID_CHARACTERISTIC_DEVICE_INFO.equals(characteristicUUID)) {
            handleDeviceInfo(characteristic.getValue(), status);
        } else if (GattCharacteristic.UUID_CHARACTERISTIC_GAP_DEVICE_NAME.equals(characteristicUUID)) {
            handleDeviceName(characteristic.getValue(), status);
        } else if (MiBandService.UUID_CHARACTERISTIC_BATTERY.equals(characteristicUUID)) {
            handleBatteryInfo(characteristic.getValue(), status);
        } else if (MiBandService.UUID_CHARACTERISTIC_REALTIME_STEPS.equals(characteristicUUID)) {
            handleRealtimeSteps(characteristic.getValue());
        } else {
            LOG.info("Unhandled characteristic read: "+ characteristicUUID);
            logMessageContent(characteristic.getValue());
        }
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt,
                                      BluetoothGattCharacteristic characteristic, int status) {
        UUID characteristicUUID = characteristic.getUuid();
        if (MiBandService.UUID_CHARACTERISTIC_PAIR.equals(characteristicUUID)) {
            handlePairResult(characteristic.getValue(), status);
        } else if (MiBandService.UUID_CHARACTERISTIC_USER_INFO.equals(characteristicUUID)) {
            handleUserInfoResult(characteristic.getValue(), status);
        } else if (MiBandService.UUID_CHARACTERISTIC_CONTROL_POINT.equals(characteristicUUID)) {
            handleControlPointResult(characteristic.getValue(), status);
        }
    }

    /**
     * Utility method that may be used to log incoming messages when we don't know how to deal with them yet.
     *
     * @param value
     */
    public void logMessageContent(byte[] value) {
        LOG.info("RECEIVED DATA WITH LENGTH: " + value.length);
        for (byte b : value) {
            LOG.warn("DATA: " + String.format("0x%2x", b));
        }
    }



    private void handleRealtimeSteps(byte[] value) {
        int steps = 0xff & value[0] | (0xff & value[1]) << 8;
        if (LOG.isDebugEnabled()) {
            LOG.debug("realtime steps: " + steps);
        }
        Intent intent = new Intent(DeviceService.ACTION_REALTIME_STEPS)
                .putExtra(DeviceService.EXTRA_REALTIME_STEPS, steps)
                .putExtra(DeviceService.EXTRA_TIMESTAMP, System.currentTimeMillis());
        LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent);
    }

    /**
     * React to unsolicited messages sent by the Mi Band to the MiBandService.UUID_CHARACTERISTIC_NOTIFICATION
     * characteristic,
     * These messages appear to be always 1 byte long, with values that are listed in MiBandService.
     * It is not excluded that there are further values which are still unknown.
     * <p/>
     * Upon receiving known values that request further action by GB, the appropriate method is called.
     *
     * @param value
     */
    private void handleNotificationNotif(byte[] value) {
        if (value.length != 1) {
            LOG.error("Notifications should be 1 byte long.");
            LOG.info("RECEIVED DATA WITH LENGTH: " + value.length);
            for (byte b : value) {
                LOG.warn("DATA: " + String.format("0x%2x", b));
            }
            return;
        }
        switch (value[0]) {
            default:
                for (byte b : value) {
                    LOG.warn("DATA: " + String.format("0x%2x", b));
                }
        }
    }

    private void handleDeviceInfo(byte[] value, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            mDeviceInfo = new DeviceInfo(value);
            LOG.warn("Device info: " + mDeviceInfo);
            versionCmd.hwVersion = mDeviceInfo.getHwVersion();
            versionCmd.fwVersion = mDeviceInfo.getHumanFirmwareVersion();
            handleGBDeviceEvent(versionCmd);
        }
    }

    private void handleDeviceName(byte[] value, int status) {
//        if (status == BluetoothGatt.GATT_SUCCESS) {
//            versionCmd.hwVersion = new String(value);
//            handleGBDeviceEvent(versionCmd);
//        }
    }

    /**
     * Convert an alarm from the GB internal structure to a Mi Band message and put on the specified
     * builder queue as a write message for the passed characteristic
     *
     * @param alarm
     * @param builder
     * @param characteristic
     */
    private void queueAlarm(Alarm alarm, TransactionBuilder builder, BluetoothGattCharacteristic characteristic) {
        byte[] alarmCalBytes = MiBandDateConverter.calendarToRawBytes(alarm.getAlarmCal());

        byte[] alarmMessage = new byte[]{
                MiBandService.COMMAND_SET_TIMER,
                (byte) alarm.getIndex(),
                (byte) (alarm.isEnabled() ? 1 : 0),
                alarmCalBytes[0],
                alarmCalBytes[1],
                alarmCalBytes[2],
                alarmCalBytes[3],
                alarmCalBytes[4],
                alarmCalBytes[5],
                (byte) (alarm.isSmartWakeup() ? 30 : 0),
                (byte) alarm.getRepetitionMask()
        };
        builder.write(characteristic, alarmMessage);
    }

    private void handleControlPointResult(byte[] value, int status) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            LOG.warn("Could not write to the control point.");
        }
        LOG.info("handleControlPoint write status:" + status);

        if (value != null) {
            for (byte b : value) {
                LOG.info("handleControlPoint WROTE DATA:" + String.format("0x%8x", b));
            }
        } else {
            LOG.warn("handleControlPoint WROTE null");
        }
    }

    private void handleBatteryInfo(byte[] value, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            BatteryInfo info = new BatteryInfo(value);
            batteryCmd.level = ((short) info.getLevelInPercent());
            batteryCmd.state = info.getState();
            batteryCmd.lastChargeTime = info.getLastChargeTime();
            batteryCmd.numCharges = info.getNumCharges();
            handleGBDeviceEvent(batteryCmd);
        }
    }

    private void handleUserInfoResult(byte[] value, int status) {
        // successfully transfered user info means we're initialized
        if (status == BluetoothGatt.GATT_SUCCESS) {
            setConnectionState(State.INITIALIZED);
        }
    }

    private void setConnectionState(State newState) {
        getDevice().setState(newState);
        getDevice().sendDeviceUpdateIntent(getContext());
    }

    private void handlePairResult(byte[] pairResult, int status) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            LOG.info("Pairing MI device failed: " + status);
            return;
        }

        String value = null;
        if (pairResult != null) {
            if (pairResult.length == 1) {
                try {
                    if (pairResult[0] == 2) {
                        LOG.info("Successfully paired  MI device");
                        return;
                    }
                } catch (Exception ex) {
                    LOG.warn("Error identifying pairing result", ex);
                    return;
                }
            }
            value = Arrays.toString(pairResult);
        }
        LOG.info("MI Band pairing result: " + value);
    }

    /**
     * Fetch the events from the android device calendars and set the alarms on the miband.
     */
    private void sendCalendarEvents() {
        try {
            TransactionBuilder builder = performInitialized("Send upcoming events");
            BluetoothGattCharacteristic characteristic = getCharacteristic(MiBandService.UUID_CHARACTERISTIC_CONTROL_POINT);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
            int availableSlots = Integer.parseInt(prefs.getString(MiBandConst.PREF_MIBAND_RESERVE_ALARM_FOR_CALENDAR, "0"));

            if (availableSlots > 0) {
                CalendarEvents upcomingEvents = new CalendarEvents();
                List<CalendarEvents.CalendarEvent> mEvents =  upcomingEvents.getCalendarEventList(getContext());

                int iteration = 0;
                ArrayList<GBAlarm> alarmList = new ArrayList<>();
                for(CalendarEvents.CalendarEvent mEvt : mEvents) {
                    if (iteration >= availableSlots || iteration > 2) {
                        break;
                    }
                    int slotToUse = 2 - iteration;
                    Calendar calendar = Calendar.getInstance();
                    calendar.setTimeInMillis(mEvt.getBegin());
                    byte[] calBytes = MiBandDateConverter.calendarToRawBytes(calendar);

                    byte[] alarmMessage = new byte[]{
                            MiBandService.COMMAND_SET_TIMER,
                            (byte) slotToUse,
                            (byte) 1,
                            calBytes[0],
                            calBytes[1],
                            calBytes[2],
                            calBytes[3],
                            calBytes[4],
                            calBytes[5],
                            (byte) 0,
                            (byte) 0
                    };
                    builder.write(characteristic, alarmMessage);
                    iteration++;
                }
                builder.queue(getQueue());
            }
        } catch (IOException ex) {
            LOG.error("Unable to send Events to MI device", ex);
        }
    }


}
