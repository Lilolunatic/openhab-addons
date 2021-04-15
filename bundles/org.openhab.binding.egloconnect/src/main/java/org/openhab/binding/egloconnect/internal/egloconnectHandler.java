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

import static org.openhab.binding.egloconnect.internal.egloconnectCommand.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.api.Response.*;
import org.openhab.binding.bluetooth.BluetoothCharacteristic;
import org.openhab.binding.bluetooth.BluetoothDevice;
import org.openhab.binding.bluetooth.ConnectedBluetoothHandler;
import org.openhab.binding.bluetooth.notification.BluetoothConnectionStatusNotification;
import org.openhab.core.common.NamedThreadFactory;
import org.openhab.core.library.types.HSBType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link egloconnectHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Zeno Berkhan - Initial contribution
 */

public class egloconnectHandler extends ConnectedBluetoothHandler implements ResponseListener {

    // TODO timeout + errorhandling nicht zu lang auf commands warten sonst alles putt
    // TODO widget initial fehlen elemente -> init wert von white/color variable
    // TODO widget updaten/ read state
    // TODO entweder in bluetooth binding oder außerhalb anzeigen, nicht beides

    private final Logger logger = LoggerFactory.getLogger(egloconnectHandler.class);

    private Optional<egloconnectConfiguration> configuration = Optional.empty();
    private @Nullable ScheduledFuture<?> scheduledTask;
    private volatile int refreshInterval;
    private AtomicInteger sinceLastReadSec = new AtomicInteger();
    private static final int CHECK_PERIOD_SEC = 10;
    private volatile ServiceState serviceState = ServiceState.NOT_RESOLVED;
    private volatile ReadState readState = ReadState.IDLE;

    private byte[] sessionRandom = new byte[8];
    private byte[] sessionKey = new byte[0];
    private short meshID = 0;

    private egloconnectCommand egloconnectCommand = new egloconnectCommand();

    private @Nullable ExecutorService commandExecutor;

    public egloconnectHandler(Thing thing) {
        super(thing);
    }

    @Override
    @NonNullByDefault
    public void handleCommand(ChannelUID channelUID, Command command) {
        try {
            switch (channelUID.getId()) {
                case egloconnectBindingConstants.CHANNEL_ID_POWER:
                    if (command instanceof OnOffType) {
                        switch ((OnOffType) command) {
                            case ON:
                                this.turnOn();
                                break;
                            case OFF:
                                this.turnOff();
                                break;
                        }
                    } else {
                        logger.warn("handleCommand(): {} can't handle command of type: {}",
                                egloconnectBindingConstants.CHANNEL_ID_POWER, command.getClass());
                    }
                    break;
                case egloconnectBindingConstants.CHANNEL_ID_COLOR:
                    if (command instanceof HSBType) {
                        this.setColor(((HSBType) command).getRed(), ((HSBType) command).getGreen(),
                                ((HSBType) command).getBlue());
                    } else {
                        logger.warn("handleCommand(): {} can't handle command of type: {}",
                                egloconnectBindingConstants.CHANNEL_ID_COLOR, command.getClass());
                    }
                    break;
                case egloconnectBindingConstants.CHANNEL_ID_COLOR_BRIGHTNESS:
                    if (command instanceof PercentType) {
                        this.setBrightness((PercentType) command);
                    } else {
                        logger.warn("handleCommand(): {} can't handle command of type: {}",
                                egloconnectBindingConstants.CHANNEL_ID_COLOR_BRIGHTNESS, command.getClass());
                    }
                    break;
                case egloconnectBindingConstants.CHANNEL_ID_WHITE_BRIGHTNESS:
                    if (command instanceof PercentType) {
                        this.setWhiteBrightness((PercentType) command);
                    } else {
                        logger.warn("handleCommand(): {} can't handle command of type: {}",
                                egloconnectBindingConstants.CHANNEL_ID_WHITE_BRIGHTNESS, command.getClass());
                    }
                    break;
                case egloconnectBindingConstants.CHANNEL_ID_WHITE_TEMPERATURE:
                    if (command instanceof PercentType) {
                        this.setWhiteTemperature((PercentType) command);
                    } else {
                        logger.warn("handleCommand(): {} can't handle command of type: {}",
                                egloconnectBindingConstants.CHANNEL_ID_WHITE_TEMPERATURE, command.getClass());
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            // catch exceptions and handle it in your binding
            logger.warn("handleCommand(): exception\n{}", e.getMessage());
        }
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

    @Override
    public void dispose() {
        dispose(commandExecutor);
        commandExecutor = null;
        dispose(scheduledTask);
        scheduledTask = null;
        super.dispose();
    }

    private static void dispose(@Nullable ExecutorService executorService) {
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    private static void dispose(@Nullable ScheduledFuture<?> scheduledTask) {
        if (scheduledTask != null) {
            scheduledTask.cancel(true);
        }
    }

    private void executePeridioc() {
        sinceLastReadSec.addAndGet(CHECK_PERIOD_SEC);
        execute();
    }

    private void connectEglo() {
        try {

            logger.info("connectEglo(): Connect to device {}...", address);

            if (device.getConnectionState() != BluetoothDevice.ConnectionState.CONNECTED) {
                logger.warn("connectEglo(): Device {} not connected!", address);
                egloconnectCommand.updateCommandState(CommandState.FAIL);
                return;
            }
            if (!device.isServicesDiscovered()) {
                logger.warn("connectEglo(): Services of device {} not resolved!", address);
                egloconnectCommand.updateCommandState(CommandState.FAIL);
                return;
            }
            BluetoothCharacteristic pairChar = device.getCharacteristic(egloconnectBindingConstants.PAIR_CHAR_UUID);
            if (pairChar == null) {
                logger.warn("connectEglo(): Characteristic {} not found!", egloconnectBindingConstants.PAIR_CHAR_UUID);
                egloconnectCommand.updateCommandState(CommandState.FAIL);
                return;
            }
            BluetoothCharacteristic statusChar = device.getCharacteristic(egloconnectBindingConstants.STATUS_CHAR_UUID);
            if (statusChar == null) {
                logger.warn("connectEglo(): Characteristic {} not found!", egloconnectBindingConstants.PAIR_CHAR_UUID);
                egloconnectCommand.updateCommandState(CommandState.FAIL);
                return;
            }

            for (int i = 0; i < 8; i++) {
                sessionRandom[i] = (byte) (Math.random() * 256);
            }
            byte[] message;
            if (configuration.isPresent()) {
                message = egloconnectPacketHelper.makePairPacket(configuration.get().meshName,
                        configuration.get().meshPassword, sessionRandom);
            } else {
                logger.warn("connectEglo(): Configuration for device {} not present!", device);
                return;
            }

            // NOTE : vorher pairChar.setValue(message);
            egloconnectCommand.updateCommandState(CommandState.QUEUED);

            device.writeCharacteristic(pairChar, message).whenComplete((datas, ex) -> {
                if (ex != null) {
                    logger.info("Failed to send command {}: {}", message, ex.getMessage());
                    egloconnectCommand.updateCommandState(CommandState.FAIL);
                } else {
                    egloconnectCommand.updateCommandState(CommandState.SENT);
                }
            });

            egloconnectCommand.awaitCommandStates(CommandState.FAIL, CommandState.SENT);

            egloconnectCommand.updateCommandState(CommandState.NEW);

            byte[] status = {1};

            // NOTE : vorher statusChar.setValue(status);
            egloconnectCommand.updateCommandState(CommandState.QUEUED);

            device.writeCharacteristic(statusChar, status).whenComplete((datas, ex) -> {
                if (ex != null) {
                    logger.info("Failed to send command {}: {}", status, ex.getMessage());
                    egloconnectCommand.updateCommandState(CommandState.FAIL);
                } else {
                    egloconnectCommand.updateCommandState(CommandState.SENT);
                }
            });

            egloconnectCommand.awaitCommandStates(CommandState.FAIL, CommandState.SENT);

            egloconnectCommand.updateCommandState(CommandState.NEW);

            final Object[] response = new Object[1];
            device.readCharacteristic(pairChar).whenComplete((data, ex) -> {
                if (data != null) {
                    response[0] = data;
                    egloconnectCommand.updateCommandState(CommandState.SUCCESS);
                } else {
                    egloconnectCommand.updateCommandState(CommandState.FAIL);
                }
            });

            egloconnectCommand.awaitCommandStates(CommandState.FAIL, CommandState.SUCCESS);

            if (((byte[]) response[0])[0] == 0xd) {
                try {
                    sessionKey = egloconnectPacketHelper.makeSessionKey(configuration.get().meshName,
                            configuration.get().meshPassword, sessionRandom,
                            Arrays.copyOfRange(((byte[]) response[0]), 1, 9));
                    logger.info("connect(): Connected to device {}!", address);
                } catch (Exception e) {
                    logger.warn("connect(): exception\n{}", e.getMessage());
                }
            } else {
                if (((byte[]) response[0])[0] == 0xe) {
                    logger.warn("connect(): Auth error from device {}: check name and password.", address);
                } else {
                    logger.warn("connectEglo(): Unexpected error. device {}", address);
                }
                this.disconnectEglo();
            }
        } catch (Exception e) {
            logger.error("connect(): exception\n{}", e.getMessage());
            this.disconnectEglo();
        } finally {
            logger.info("connect(): Command State: {}", egloconnectCommand.getCommandState());
            egloconnectCommand.updateCommandState(CommandState.NEW);
        }
    }

    @Override
    @NonNullByDefault
    public void onConnectionStateChange(BluetoothConnectionStatusNotification connectionNotification) {
        super.onConnectionStateChange(connectionNotification);
        switch (connectionNotification.getConnectionState()) {
            case DISCOVERED:
                break;
            case CONNECTED:
                logger.info("connect(): BLEConnected, resolved: {}.", device.isServicesDiscovered());
                if (device.isServicesDiscovered()) {
                    if (commandExecutor != null) {
                        commandExecutor.execute(this::connectEglo);
                    }
                }
                break;
            case DISCONNECTED:
                this.sessionKey = new byte[0];
                break;
            default:
                break;
        }
    }

    @Override
    public void onServicesDiscovered() {
        super.onServicesDiscovered();
        logger.info("connect(): BLEConnected, BLEServices discovered");
        if (commandExecutor != null) {
            commandExecutor.execute(this::connectEglo);
        }
    }

    private void disconnectEglo() {

        logger.info("connect(): Disconnecting device {}!", address);
        device.disconnect();
        sessionKey = new byte[1];
    }

    private synchronized void execute() {
        BluetoothDevice.ConnectionState connectionState = device.getConnectionState();
        logger.info("execute(): Device {} state is {}, serviceState {}, readState {}", address, connectionState,
                serviceState, readState);

        switch (connectionState) {
            case DISCOVERED:
            case DISCONNECTED:
                connectEglo();
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
        configuration = Optional.of(getConfigAs(egloconnectConfiguration.class));

        logger.info("initialize(): Using configuration: {}", configuration.get());
        logger.info("initialize(): Mesh Name: {}, Mesh Password: {}", configuration.get().meshName,
                configuration.get().meshPassword);

        // TODO remote mit einbingen damit in gleichem mesh

        commandExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory(thing.getUID().getAsString(), true));

        if (device.getConnectionState() == BluetoothDevice.ConnectionState.CONNECTED && device.isServicesDiscovered()) {
            commandExecutor.execute(this::connectEglo);
        }
    }

    private boolean setMesh(String newMeshName, String newMeshPassword, String newMeshLongTermKey) throws Exception {

        // TODO config meshname ändern logik
        // assert len(self.mesh_name) <= 16, "mesh_name can hold max 16 bytes"
        // assert len(self.mesh_password) <= 16, "mesh_password can hold max 16 bytes"

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

        BluetoothCharacteristic pairChar = device.getCharacteristic(egloconnectBindingConstants.PAIR_CHAR_UUID);
        if (pairChar == null)
            return false;

        byte[] message = egloconnectPacketHelper.encrypt(this.sessionKey, newMeshName.getBytes(StandardCharsets.UTF_8));
        byte[] tmp = new byte[1 + message.length];
        tmp[0] = 0x4;
        for (int i = 0; i < message.length; i++) {
            tmp[i + 1] = message[i];
        }
        message = tmp;
        // TODO pairChar.setValue(message); whencomplete
        device.writeCharacteristic(pairChar, message);

        message = egloconnectPacketHelper.encrypt(this.sessionKey, newMeshPassword.getBytes(StandardCharsets.UTF_8));
        tmp = new byte[1 + message.length];
        tmp[0] = 0x5;
        for (int i = 0; i < message.length; i++) {
            tmp[i + 1] = message[i];
        }
        message = tmp;
        // TODO pairChar.setValue(message); whencomplete
        device.writeCharacteristic(pairChar, message);

        message = egloconnectPacketHelper.encrypt(this.sessionKey, newMeshLongTermKey.getBytes(StandardCharsets.UTF_8));
        tmp = new byte[1 + message.length];
        tmp[0] = 0x6;
        for (int i = 0; i < message.length; i++) {
            tmp[i + 1] = message[i];
        }
        // TODO pairChar.setValue(message); whencomplete
        device.writeCharacteristic(pairChar, message);
        TimeUnit.SECONDS.sleep(1);

        final Object[] response = new Object[1];
        device.readCharacteristic(pairChar).whenComplete((data, ex) -> {
            if (data != null) {
                response[0] = data;
                egloconnectCommand.updateCommandState(CommandState.SUCCESS);
            } else {
                egloconnectCommand.updateCommandState(CommandState.FAIL);
            }
        });

        egloconnectCommand.awaitCommandStates(CommandState.FAIL, CommandState.SUCCESS);

        if (((byte[]) response[0])[0] == 0x07) {
            // this.meshName = newMeshName.getBytes(StandardCharsets.UTF_8);
            // this.meshPassword = newMeshPassword.getBytes(StandardCharsets.UTF_8);
            logger.info("setMesh(): Mesh network settings accepted.");
            return true;
        } else {
            logger.warn("setMesh(): Mesh network settings change failed!");
            return false;
        }
    }

    private void writeCommand(byte command, byte[] data, short dest) {

        try {

            if (this.sessionKey.length == 0) {
                logger.warn("writeCommand(): Device {} not high level connected!", address);
                // updateCommandState(CommandState.FAIL);
                egloconnectCommand.updateCommandState(CommandState.FAIL);
                return;
            }
            BluetoothCharacteristic commandChar = device
                    .getCharacteristic(egloconnectBindingConstants.COMMAND_CHAR_UUID);
            if (commandChar == null) {
                logger.warn("writeCommand(): Characteristic {} not found!",
                        egloconnectBindingConstants.COMMAND_CHAR_UUID);
                egloconnectCommand.updateCommandState(CommandState.FAIL);
                return;
            }

            byte[] packet = egloconnectPacketHelper.makeCommandPacket(this.sessionKey, address.toString(), dest,
                    command, data);
            logger.info("writeCommand(): {}: Writing command {} data {}", address.toString(), command, data);

            // NOTE : vorher commandChar.setValue(packet);
            egloconnectCommand.updateCommandState(CommandState.QUEUED);

            device.writeCharacteristic(commandChar, packet).whenComplete((datas, ex) -> {
                if (ex != null) {
                    logger.info("Failed to send command {}: {}", packet, ex.getMessage());
                    egloconnectCommand.updateCommandState(CommandState.FAIL);
                } else {
                    egloconnectCommand.updateCommandState(CommandState.SENT);
                }
            });

            egloconnectCommand.awaitCommandStates(CommandState.FAIL, CommandState.SUCCESS);

        } catch (Exception e) {
            logger.error("writeCommand(): exception\n{}", e.getMessage());
        } finally {
            logger.info("writeCommand(): Command State: {}", egloconnectCommand.getCommandState());
            egloconnectCommand.updateCommandState(CommandState.NEW);
        }
    }

    private void writeCommand(byte command, byte[] data) {
        short dest = meshID;
        this.writeCommand(command, data, dest);
    }

    private byte[] readStatus() throws Exception {
        // TODO add async await logic
        BluetoothCharacteristic statusChar = device.getCharacteristic(egloconnectBindingConstants.STATUS_CHAR_UUID);
        if (statusChar == null) {
            return new byte[0];
        }

        final Object[] response = new Object[1];
        device.readCharacteristic(statusChar).whenComplete((data, ex) -> {
            if (data != null) {
                response[0] = data;
                egloconnectCommand.updateCommandState(CommandState.SUCCESS);
            } else {
                egloconnectCommand.updateCommandState(CommandState.FAIL);
            }
        });

        egloconnectCommand.awaitCommandStates(CommandState.FAIL, CommandState.SUCCESS);

        return egloconnectPacketHelper.decryptPacket(this.sessionKey, address.toString(), ((byte[]) response[0]));
    }

    private void turnOn() {
        byte[] data = new byte[1];
        data[0] = 0x01;
        if (commandExecutor != null) {
            commandExecutor.execute(() -> writeCommand(egloconnectBindingConstants.C_POWER, data));
        }
    }

    private void turnOff() {
        byte[] data = new byte[1];
        if (commandExecutor != null) {
            commandExecutor.execute(() -> writeCommand(egloconnectBindingConstants.C_POWER, data));
        }
    }

    private void setColor(PercentType redPercent, PercentType greenPercent, PercentType bluePercent) {
        byte red = (byte) ((redPercent.intValue() * 255) / 100);
        byte green = (byte) ((greenPercent.intValue() * 255) / 100);
        byte blue = (byte) ((bluePercent.intValue() * 255) / 100);
        byte[] data = {0x04, red, green, blue};
        if (commandExecutor != null) {
            commandExecutor.execute(() -> writeCommand(egloconnectBindingConstants.C_COLOR, data));
        }
    }

    private void setBrightness(PercentType brightness) {
        // brightness in %
        byte[] data = {brightness.byteValue()};
        if (commandExecutor != null) {
            commandExecutor.execute(() -> writeCommand(egloconnectBindingConstants.C_COLOR_BRIGHTNESS, data));
        }
    }

    private void setWhiteBrightness(PercentType brightnessPercent) {
        // brightness in 1-127
        byte brightness = (byte) ((brightnessPercent.intValue() * 127) / 100);
        byte[] data = {brightness};
        if (commandExecutor != null) {
            commandExecutor.execute(() -> writeCommand(egloconnectBindingConstants.C_WHITE_BRIGHTNESS, data));
        }
    }

    private void setWhiteTemperature(PercentType temperature) {
        // brightness in %
        byte[] data = {temperature.byteValue()};
        if (commandExecutor != null) {
            commandExecutor.execute(() -> writeCommand(egloconnectBindingConstants.C_WHITE_TEMPERATURE, data));
        }
    }
}
