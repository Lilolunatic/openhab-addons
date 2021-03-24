/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 * <p>
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 * <p>
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * <p>
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.egloconnect.internal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.BluetoothCharacteristic;
import org.openhab.binding.bluetooth.BluetoothDevice;
import org.openhab.binding.bluetooth.ConnectedBluetoothHandler;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link EgloConnectHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author oshl - Initial contribution
 */
@NonNullByDefault
public class EgloConnectHandler extends ConnectedBluetoothHandler {

    private final Logger logger = LoggerFactory.getLogger(EgloConnectHandler.class);

    // private @Nullable EgloConnectConfiguration config;

    private Optional<EgloConnectConfiguration> configuration = Optional.empty();
    private @Nullable ScheduledFuture<?> scheduledTask;
    private volatile int refreshInterval;
    private AtomicInteger sinceLastReadSec = new AtomicInteger();
    private static final int CHECK_PERIOD_SEC = 10;
    private volatile ServiceState serviceState = ServiceState.NOT_RESOLVED;
    private volatile ReadState readState = ReadState.IDLE;

    private byte[] sessionRandom = new byte[8];
    private byte[] sessionKey = new byte[1];
    private byte[] meshName = "unpaired".getBytes(StandardCharsets.UTF_8);
    private byte[] meshPassword = "1234".getBytes(StandardCharsets.UTF_8);
    private short meshID = 0;

    public EgloConnectHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
    }

    private enum ServiceState {
        NOT_RESOLVED,
        RESOLVING,
        RESOLVED,
    }

    private enum ReadState {
        IDLE,
        READING,
    }

    private void cancelScheduledTask() {
        if (scheduledTask != null) {
            scheduledTask.cancel(true);
            scheduledTask = null;
        }
    }

    private void executePeridioc() {
        sinceLastReadSec.addAndGet(CHECK_PERIOD_SEC);
        execute();
    }
    /*
     * 
     * private boolean isTimeToRead() {
     * int sinceLastRead = sinceLastReadSec.get();
     * logger.debug("Time since last update: {} sec", sinceLastRead);
     * return sinceLastRead >= refreshInterval;
     * }
     */

    private void connect() {

        // TODO def connect(self, mesh_name = None, mesh_password = None):
        // if mesh_name : self.mesh_name = mesh_name.encode ()
        // if mesh_password : self.mesh_password = mesh_password.encode ()
        // assert len(self.mesh_name) <= 16, "mesh_name can hold max 16 bytes"
        // assert len(self.mesh_password) <= 16, "mesh_password can hold max 16 bytes"

        logger.info("connect(): Connect to device {}...", address);

        device.connect();

        // TODO self.btdevice.setDelegate (Delegate (self))

        BluetoothCharacteristic pairChar = device.getCharacteristic(EgloConnectBindingConstants.PAIR_CHAR_UUID);
        for (int i = 0; i < 8; i++) {
            sessionRandom[i] = (byte) (Math.random() * 256);
        }
        try {
            byte[] message = EgloConnectPacketHelper.makePairPacket(configuration.get().meshName,
                    configuration.get().meshPassword, sessionRandom);
            pairChar.setValue(message);
            device.writeCharacteristic(pairChar);
        } catch (Exception e) {
            logger.error("connect(): exception\n{}", e.getMessage());
        }

        BluetoothCharacteristic statusChar = device.getCharacteristic(EgloConnectBindingConstants.STATUS_CHAR_UUID);
        byte[] status = { 1 };
        statusChar.setValue(status);
        device.writeCharacteristic(statusChar);

        // TODO? reply = bytearray (pair_char.read ())
        pairChar = device.getCharacteristic(EgloConnectBindingConstants.PAIR_CHAR_UUID);
        byte[] response = pairChar.getByteValue();
        if (response[0] == 0xd) {
            try {

                sessionKey = EgloConnectPacketHelper.makeSessionKey(configuration.get().meshName,
                        configuration.get().meshPassword, sessionRandom, Arrays.copyOfRange(response, 1, 9));
                logger.info("connect(): Connected to device {}!", address);
            } catch (Exception e) {
                logger.error("connect(): exception\n{}", e.getMessage());
            }
            // TODO return True
        } else {
            if (response[0] == 0xe) {
                logger.error("connect(): Auth error from device {}: check name and password.", address);
            } else {
                logger.error("connect(): Auth error from device {}: check name and password.", address);
                this.disconnect();
            }
            // TODO return False
        }

        /*
         * original
         * logger.warn("connect(): Connect to device {}...", address);
         * if (!device.connect()) {
         * logger.warn("connect(): Connecting to device {} failed", address);
         * }
         */
    }

    private void disconnect() {

        logger.info("connect(): Disconnecting device {}!", address);
        device.disconnect();
        sessionKey = new byte[1];

        /*
         * logger.warn("Disconnect from device {}...", address);
         * if (!device.disconnect()) {
         * logger.warn("Disconnect from device {} failed", address);
         * }
         */
    }

    /*
     * private void read() {
     * switch (serviceState) {
     * case NOT_RESOLVED:
     * discoverServices();
     * break;
     * case RESOLVED:
     * switch (readState) {
     * case IDLE:
     * logger.warn("read(): Read data from device {}...", address);
     * String uuid = "";
     * BluetoothCharacteristic characteristic = device.getCharacteristic(uuid);
     * if (characteristic != null && device.readCharacteristic(characteristic)) {
     * readState = ReadState.READING;
     * } else {
     * logger.warn("read(): Read data from device {} failed", address);
     * disconnect();
     * }
     * break;
     * default:
     * break;
     * }
     * default:
     * break;
     * }
     * }
     */

    private synchronized void execute() {
        BluetoothDevice.ConnectionState connectionState = device.getConnectionState();
        logger.info("execute(): Device {} state is {}, serviceState {}, readState {}", address, connectionState,
                serviceState, readState);

        switch (connectionState) {
            case DISCOVERED:
            case DISCONNECTED:
                connect();
                break;
            case CONNECTED:
                break;
            default:
                break;
        }
    }

    @Override
    public void initialize() {

        logger.info("initialize(): Initialize");
        super.initialize();
        configuration = Optional.of(getConfigAs(EgloConnectConfiguration.class));
        logger.info("initialize(): Using configuration: {}", configuration.get());
        logger.info("initialize(): Mesh Name: {}, Mesh Password: {}", configuration.get().meshName,
                configuration.get().meshPassword);
        cancelScheduledTask();

        // TODO welche config wird denn jetzt benutzt

        // TODO remote mit einbingen damit in gleichem mesh
        // python init zeugs
        connect();
        try {
            setMesh(configuration.get().meshName, configuration.get().meshPassword, "1234567890123456");
        } catch (Exception e) {
            logger.error("initialize(): exception\n{}", e.getMessage());
        }
        disconnect();

        configuration.ifPresent(cfg -> {
            refreshInterval = cfg.refreshInterval;

            logger.info("initialize(): Start scheduled task to read device in every {} seconds", refreshInterval);
            scheduledTask = scheduler.scheduleWithFixedDelay(this::executePeridioc, CHECK_PERIOD_SEC, CHECK_PERIOD_SEC,
                    TimeUnit.SECONDS);
        });

        sinceLastReadSec.set(refreshInterval); // update immediately

        /*
         * scheduler.execute(() -> {
         * boolean thingReachable = false; // <background task with long running initialization here>
         * // when done do:
         * if (thingReachable) {
         * updateStatus(ThingStatus.ONLINE);
         * } else {
         * updateStatus(ThingStatus.OFFLINE);
         * }
         * });
         */
    }
    /*
     * 
     * private void discoverServices() {
     * //TODO
     * logger.warn("discoverServices(): Discover services for device {}", address);
     * 
     * UUID cmd_uuid = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1912");
     * BluetoothCharacteristic characteristic = device.getCharacteristic(cmd_uuid);
     * 
     * // characteristic.setValue()
     * 
     * characteristic.getByteValue();
     * 
     * device.writeCharacteristic(characteristic);
     * }
     */

    private boolean setMesh(String newMeshName, String newMeshPassword, String newMeshLongTermKey) throws Exception {

        if (this.sessionKey.length == 1) {
            logger.warn("setMesh(): Device {} not connected!", address);
            return false;
        }
        if (newMeshName.length() > 16) {
            logger.warn("setMesh(): Mesh Name {} is too long!", newMeshName);
            return false;
        }
        if (newMeshPassword.length() > 16) {
            logger.warn("setMesh(): Mesh Password {} is too long!", newMeshPassword);
            return false;
        }
        if (newMeshLongTermKey.length() > 16) {
            logger.warn("setMesh(): Mesh Long Term Key {} is too long!", newMeshLongTermKey);
            return false;
        }

        BluetoothCharacteristic pairChar = device.getCharacteristic(EgloConnectBindingConstants.PAIR_CHAR_UUID);
        // # FIXME : Removing the delegate as a workaround to a bluepy.btle.BTLEException
        // # similar to https://github.com/IanHarvey/bluepy/issues/182 That may be
        // # a bluepy bug or I'm using it wrong or both ...

        // TODO self.btdevice.setDelegate (None)

        byte[] message = EgloConnectPacketHelper.encrypt(this.sessionKey, newMeshName.getBytes(StandardCharsets.UTF_8));
        byte[] tmp = new byte[1 + message.length];
        tmp[0] = 0x4;
        for (int i = 0; i < message.length; i++) {
            tmp[i + 1] = message[i];
        }
        message = tmp;
        pairChar.setValue(message);
        device.writeCharacteristic(pairChar);

        message = EgloConnectPacketHelper.encrypt(this.sessionKey, newMeshPassword.getBytes(StandardCharsets.UTF_8));
        tmp = new byte[1 + message.length];
        tmp[0] = 0x5;
        for (int i = 0; i < message.length; i++) {
            tmp[i + 1] = message[i];
        }
        message = tmp;
        pairChar.setValue(message);
        device.writeCharacteristic(pairChar);

        message = EgloConnectPacketHelper.encrypt(this.sessionKey, newMeshLongTermKey.getBytes(StandardCharsets.UTF_8));
        tmp = new byte[1 + message.length];
        tmp[0] = 0x6;
        for (int i = 0; i < message.length; i++) {
            tmp[i + 1] = message[i];
        }
        pairChar.setValue(message);
        device.writeCharacteristic(pairChar);
        TimeUnit.SECONDS.sleep(1);

        pairChar = device.getCharacteristic(EgloConnectBindingConstants.PAIR_CHAR_UUID);
        byte[] response = pairChar.getByteValue();

        // TODO self.btdevice.setDelegate (Delegate (self))

        if (response[0] == 0x07) {
            this.meshName = newMeshName.getBytes(StandardCharsets.UTF_8);
            this.meshPassword = newMeshPassword.getBytes(StandardCharsets.UTF_8);
            logger.info("setMesh(): Mesh network settings accepted.");
            return true;
        } else {
            logger.warn("setMesh(): Mesh network settings change failed!");
            return false;
        }
    }

    private void writeCommand(byte command, byte[] data, short dest) throws Exception {

        if (this.sessionKey.length == 1) {
            logger.warn("setMesh(): Device {} not connected!", address);
            return;
        }

        byte[] packet = EgloConnectPacketHelper.makeCommandPacket(this.sessionKey, address.toString(), dest, command,
                data);

        BluetoothCharacteristic commandChar = device.getCharacteristic(EgloConnectBindingConstants.COMMAND_CHAR_UUID);

        try {
            logger.info("writeCommand(): {}: Writing command {} data {}", address.toString(), command, data);
            commandChar.setValue(packet);
            device.writeCharacteristic(commandChar);
        } catch (Exception e) {
            logger.warn("writeCommand(): {}: (Re)load characteristics", address.toString());
            commandChar = device.getCharacteristic(EgloConnectBindingConstants.COMMAND_CHAR_UUID);
            logger.info("writeCommand(): {}: Writing command {} data {}", address.toString(), command, data);
            commandChar.setValue(packet);
            device.writeCharacteristic(commandChar);
        }
    }

    private void writeCommand(byte command, byte[] data) throws Exception {
        short dest = meshID;
        this.writeCommand(command, data, dest);
    }

    private byte[] readStatus() throws Exception {
        BluetoothCharacteristic statusChar = device.getCharacteristic(EgloConnectBindingConstants.STATUS_CHAR_UUID);
        byte[] packet = statusChar.getByteValue();
        return EgloConnectPacketHelper.decryptPacket(this.sessionKey, address.toString(), packet);
    }
}
