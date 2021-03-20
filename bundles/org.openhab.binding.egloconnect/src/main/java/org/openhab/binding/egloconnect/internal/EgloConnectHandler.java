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

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.BeaconBluetoothHandler;
import org.openhab.binding.bluetooth.BluetoothCharacteristic;
import org.openhab.binding.bluetooth.BluetoothDevice;
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
public class EgloConnectHandler extends BeaconBluetoothHandler {

    private static final String DATA_UUID = "00010203-0405-0607-0809-0a0b0c0d1910";
    private final UUID uuid = UUID.fromString(DATA_UUID);

    private final Logger logger = LoggerFactory.getLogger(EgloConnectHandler.class);

    private @Nullable EgloConnectConfiguration config;

    private Optional<EgloConnectConfiguration> configuration = Optional.empty();
    private @Nullable ScheduledFuture<?> scheduledTask;
    private volatile int refreshInterval;
    private AtomicInteger sinceLastReadSec = new AtomicInteger();
    private static final int CHECK_PERIOD_SEC = 10;
    private volatile ServiceState serviceState = ServiceState.NOT_RESOLVED;
    private volatile ReadState readState = ReadState.IDLE;

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

    private boolean isTimeToRead() {
        int sinceLastRead = sinceLastReadSec.get();
        logger.debug("Time since last update: {} sec", sinceLastRead);
        return sinceLastRead >= refreshInterval;
    }

    private void connect() {
        logger.warn("connect(): Connect to device {}...", address);
        if (!device.connect()) {
            logger.warn("connect(): Connecting to device {} failed", address);
        }
    }

    private void disconnect() {
        logger.warn("Disconnect from device {}...", address);
        if (!device.disconnect()) {
            logger.warn("Disconnect from device {} failed", address);
        }
    }

    private void read() {
        switch (serviceState) {
            case NOT_RESOLVED:
                discoverServices();
                break;
            case RESOLVED:
                switch (readState) {
                    case IDLE:
                        logger.warn("read(): Read data from device {}...", address);
                        BluetoothCharacteristic characteristic = device.getCharacteristic(uuid);
                        if (characteristic != null && device.readCharacteristic(characteristic)) {
                            readState = ReadState.READING;
                        } else {
                            logger.warn("read(): Read data from device {} failed", address);
                            disconnect();
                        }
                        break;
                    default:
                        break;
                }
            default:
                break;
        }
    }

    private synchronized void execute() {
        BluetoothDevice.ConnectionState connectionState = device.getConnectionState();
        logger.warn("execute(): Device {} state is {}, serviceState {}, readState {}", address, connectionState,
                serviceState, readState);

        switch (connectionState) {
            case DISCOVERED:
            case DISCONNECTED:
                if (isTimeToRead()) {
                    connect();
                }
                break;
            case CONNECTED:
                // read();
                testCharBla();
                break;
            default:
                break;
        }
    }

    @Override
    public void initialize() {

        logger.warn("initialize(): Initialize");
        super.initialize();
        configuration = Optional.of(getConfigAs(EgloConnectConfiguration.class));
        logger.warn("initialize(): Using configuration: {}", configuration.get());
        cancelScheduledTask();

        configuration.ifPresent(cfg -> {
            refreshInterval = cfg.refreshInterval;

            logger.warn("initialize(): Start scheduled task to read device in every {} seconds", refreshInterval);
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

    private void discoverServices() {
        logger.warn("discoverServices(): Discover services for device {}", address);

        UUID cmd_uuid = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1912");
        BluetoothCharacteristic characteristic = device.getCharacteristic(cmd_uuid);

        // characteristic.setValue()

        characteristic.getByteValue();

        device.writeCharacteristic(characteristic);
    }

    private void testCharBla() {
        logger.warn("testCharBla(): test color char cmd kack for device {}", address);

        UUID cmd_uuid = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1912");
        BluetoothCharacteristic characteristic = device.getCharacteristic(cmd_uuid);

        // characteristic.setValue()
        byte[] data = characteristic.getByteValue();

        device.writeCharacteristic(characteristic);
    }

    private boolean writeCharacteristic(BluetoothCharacteristic characteristic, byte[] data) {
        characteristic.setValue(data);
        return device.writeCharacteristic(characteristic);
    }
}

/*
 * 
 * def make_command_packet (key, address, dest_id, command, data):
 * """
 * Args :
 * key: The encryption key, 16 bytes.
 * address: The mac address as a string.
 * dest_id: The mesh id of the command destination as a number.
 * command: The command as a number.
 * data: The parameters for the command as bytes.
 * """
 * # Sequence number, just need to be different, idea from https://github.com/nkaminski/csrmesh
 * s = urandom (3)
 * 
 * # Build nonce
 * a = bytearray.fromhex(address.replace (":",""))
 * a.reverse()
 * nonce = bytes(a[0:4] + b'\x01' + s)
 * 
 * # Build payload
 * dest = struct.pack ("<H", dest_id)
 * 
 * payload = (dest + struct.pack('B', command) + b'\x60\x01' + data).ljust(15, b'\x00')
 * 
 * # Compute checksum
 * check = make_checksum (key, nonce, payload)
 * 
 * # Encrypt payload
 * payload = crypt_payload (key, nonce, payload)
 * 
 * # Make packet
 * packet = s + check[0:2] + payload
 * return packet
 * 
 * 
 * def writeCommand (self, command, data, dest = None):
 * """
 * Args:
 * command: The command, as a number.
 * data: The parameters for the command, as bytes.
 * dest: The destination mesh id, as a number. If None, this lightbulb's
 * mesh id will be used.
 * """
 * assert (self.session_key)
 * if dest == None: dest = self.mesh_id
 * packet = pckt.make_command_packet (self.session_key, self.mac, dest, command, data)
 * command_char = self.btdevice.getCharacteristics (uuid=COMMAND_CHAR_UUID)[0]
 * logger.info ("Writing command %i data %s", command, repr (data))
 * command_char.write (packet)
 * 
 * 
 * def setColor (self, red, green, blue):
 * """
 * Args :
 * red, green, blue: between 0 and 0xff
 * """
 * data = struct.pack ('BBBB', 0x04, red, green, blue)
 * self.writeCommand (C_COLOR, data)
 * 
 * 
 * 
 */
