/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
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
import org.openhab.core.thing.ThingStatus;
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
        logger.debug("Connect to device {}...", address);
        if (!device.connect()) {
            logger.debug("Connecting to device {} failed", address);
        }
    }

    private void discoverServices() {
        logger.debug("Discover services for device {}", address);
        serviceState = ServiceState.RESOLVING;
        device.discoverServices();
    }

    private void disconnect() {
        logger.debug("Disconnect from device {}...", address);
        if (!device.disconnect()) {
            logger.debug("Disconnect from device {} failed", address);
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
                        logger.debug("Read data from device {}...", address);
                        BluetoothCharacteristic characteristic = device.getCharacteristic(uuid);
                        if (characteristic != null && device.readCharacteristic(characteristic)) {
                            readState = ReadState.READING;
                        } else {
                            logger.debug("Read data from device {} failed", address);
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
        logger.debug("Device {} state is {}, serviceState {}, readState {}", address, connectionState, serviceState,
                readState);

        switch (connectionState) {
            case DISCOVERED:
            case DISCONNECTED:
                if (isTimeToRead()) {
                    connect();
                }
                break;
            case CONNECTED:
                read();
                break;
            default:
                break;
        }
    }

    @Override
    public void initialize() {

        logger.debug("Initialize");
        super.initialize();
        configuration = Optional.of(getConfigAs(EgloConnectConfiguration.class));
        logger.debug("Using configuration: {}", configuration.get());
        cancelScheduledTask();

        configuration.ifPresent(cfg -> {
            refreshInterval = cfg.refreshInterval;

            logger.debug("Start scheduled task to read device in every {} seconds", refreshInterval);
            scheduledTask = scheduler.scheduleWithFixedDelay(this::executePeridioc, CHECK_PERIOD_SEC, CHECK_PERIOD_SEC,
                    TimeUnit.SECONDS);
        });

        sinceLastReadSec.set(refreshInterval); // update immediately

        BluetoothDevice.ConnectionState connectionState = device.getConnectionState();
        logger.error("Device {} state is {}, serviceState {}, readState {}", address, connectionState, serviceState,
                readState);

        scheduler.execute(() -> {
            boolean thingReachable = false; // <background task with long running initialization here>
            // when done do:
            if (thingReachable) {
                updateStatus(ThingStatus.ONLINE);
            } else {
                updateStatus(ThingStatus.OFFLINE);
            }
        });
    }
}
