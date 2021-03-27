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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.api.Response.*;
import org.openhab.binding.bluetooth.BluetoothCharacteristic;
import org.openhab.binding.bluetooth.BluetoothCompletionStatus;
import org.openhab.binding.bluetooth.BluetoothDevice;
import org.openhab.binding.bluetooth.ConnectedBluetoothHandler;
import org.openhab.binding.bluetooth.notification.BluetoothConnectionStatusNotification;
import org.openhab.binding.egloconnect.internal.command.EgloConnectCommand;
import org.openhab.binding.egloconnect.internal.command.EgloConnectCommand.*;
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
 * The {@link EgloConnectHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author oshl - Initial contribution
 */
@NonNullByDefault
public class EgloConnectHandler extends ConnectedBluetoothHandler implements ResponseListener {

    // TODO timeout nicht zu lang auf commands warten sonst alles putt
    // TODO schauen mit welchen ui elementen am besten ALLE channels ansteuern
    // TODO doppelter brightness channel

    private final Logger logger = LoggerFactory.getLogger(EgloConnectHandler.class);

    // private @Nullable EgloConnectConfiguration configuration;

    private Optional<EgloConnectConfiguration> configuration = Optional.empty();
    private @Nullable ScheduledFuture<?> scheduledTask;
    private volatile int refreshInterval;
    private AtomicInteger sinceLastReadSec = new AtomicInteger();
    private static final int CHECK_PERIOD_SEC = 10;
    private volatile ServiceState serviceState = ServiceState.NOT_RESOLVED;
    private volatile ReadState readState = ReadState.IDLE;

    private byte[] sessionRandom = new byte[8];
    private byte[] sessionKey = new byte[0];
    private byte[] meshName = "unpaired".getBytes(StandardCharsets.UTF_8);
    private byte[] meshPassword = "1234".getBytes(StandardCharsets.UTF_8);
    private short meshID = 0;

    private EgloConnectCommand egloConnectCommand = new EgloConnectCommand();

    /*
     * private final Lock stateLock = new ReentrantLock();
     * private final Condition stateCondition = stateLock.newCondition();
     *
     * private CommandState commandState = CommandState.NEW;
     */

    private @Nullable ExecutorService commandExecutor;

    public EgloConnectHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        try {
            switch (channelUID.getId()) {
                case EgloConnectBindingConstants.CHANNEL_ID_POWER:
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
                                EgloConnectBindingConstants.CHANNEL_ID_POWER, command.getClass());
                    }
                    break;
                case EgloConnectBindingConstants.CHANNEL_ID_COLOR:
                    if (command instanceof HSBType) {
                        this.setColor(((HSBType) command).getRed(), ((HSBType) command).getGreen(),
                                ((HSBType) command).getBlue());
                    } else {
                        logger.warn("handleCommand(): {} can't handle command of type: {}",
                                EgloConnectBindingConstants.CHANNEL_ID_COLOR, command.getClass());
                    }
                    break;
                case EgloConnectBindingConstants.CHANNEL_ID_COLOR_BRIGHTNESS:
                    if (command instanceof PercentType) {
                        this.setBrightness((PercentType) command);
                    } else {
                        logger.warn("handleCommand(): {} can't handle command of type: {}",
                                EgloConnectBindingConstants.CHANNEL_ID_COLOR_BRIGHTNESS, command.getClass());
                    }
                    break;
                case EgloConnectBindingConstants.CHANNEL_ID_WHITE_BRIGHTNESS:
                    if (command instanceof PercentType) {
                        this.setWhiteBrightness((PercentType) command);
                    } else {
                        logger.warn("handleCommand(): {} can't handle command of type: {}",
                                EgloConnectBindingConstants.CHANNEL_ID_WHITE_BRIGHTNESS, command.getClass());
                    }
                    break;
                case EgloConnectBindingConstants.CHANNEL_ID_WHITE_TEMPERATURE:
                    if (command instanceof PercentType) {
                        this.setWhiteTemperature((PercentType) command);
                    } else {
                        logger.warn("handleCommand(): {} can't handle command of type: {}",
                                EgloConnectBindingConstants.CHANNEL_ID_WHITE_TEMPERATURE, command.getClass());
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
        try {

            logger.info("connect(): Connect to device {}...", address);

            if (device.getConnectionState() != BluetoothDevice.ConnectionState.CONNECTED) {
                logger.warn("connect(): Device {} not connected!", address);
                egloConnectCommand.updateCommandState(CommandState.FAIL);
                return;
            }
            if (!resolved) {
                logger.warn("connect(): Services of device {} not resolved!", address);
                egloConnectCommand.updateCommandState(CommandState.FAIL);
                return;
            }
            BluetoothCharacteristic pairChar = device.getCharacteristic(EgloConnectBindingConstants.PAIR_CHAR_UUID);
            if (pairChar == null) {
                logger.warn("connect(): Characteristic {} not found!", EgloConnectBindingConstants.PAIR_CHAR_UUID);
                egloConnectCommand.updateCommandState(CommandState.FAIL);
                return;
            }
            BluetoothCharacteristic statusChar = device.getCharacteristic(EgloConnectBindingConstants.STATUS_CHAR_UUID);
            if (statusChar == null) {
                logger.warn("connect(): Characteristic {} not found!", EgloConnectBindingConstants.PAIR_CHAR_UUID);
                egloConnectCommand.updateCommandState(CommandState.FAIL);
                return;
            }

            for (int i = 0; i < 8; i++) {
                sessionRandom[i] = (byte) (Math.random() * 256);
            }
            byte[] message = EgloConnectPacketHelper.makePairPacket(configuration.get().meshName,
                    configuration.get().meshPassword, sessionRandom);
            pairChar.setValue(message);
            device.writeCharacteristic(pairChar);
            egloConnectCommand.updateCommandState(CommandState.QUEUED);

            // TODO timout + errorhandling statupdate whatever

            egloConnectCommand.awaitCommandStates(CommandState.FAIL, CommandState.SENT);

            egloConnectCommand.updateCommandState(CommandState.NEW);
            byte[] status = { 1 };
            statusChar.setValue(status);
            device.writeCharacteristic(statusChar);
            egloConnectCommand.updateCommandState(CommandState.QUEUED);

            egloConnectCommand.awaitCommandStates(CommandState.FAIL, CommandState.SENT);

            device.readCharacteristic(pairChar);

            egloConnectCommand.awaitCommandStates(CommandState.FAIL, CommandState.SUCCESS);

            byte[] response = pairChar.getByteValue();
            if (response[0] == 0xd) {
                try {

                    sessionKey = EgloConnectPacketHelper.makeSessionKey(configuration.get().meshName,
                            configuration.get().meshPassword, sessionRandom, Arrays.copyOfRange(response, 1, 9));
                    logger.info("connect(): Connected to device {}!", address);
                } catch (Exception e) {
                    logger.warn("connect(): exception\n{}", e.getMessage());
                }
            } else {
                if (response[0] == 0xe) {
                    logger.warn("connect(): Auth error from device {}: check name and password.", address);
                } else {
                    logger.warn("connect(): Unexpected error.", address);
                }
                this.disconnect();
            }
            return;
        } catch (Exception e) {
            logger.error("connect(): exception\n{}", e.getMessage());
            this.disconnect();
        } finally {
            logger.info("connect(): Command State: {}", egloConnectCommand.getCommandState());
            egloConnectCommand.updateCommandState(CommandState.NEW);
        }
    }

    @Override
    public void onConnectionStateChange(BluetoothConnectionStatusNotification connectionNotification) {
        super.onConnectionStateChange(connectionNotification);
        switch (connectionNotification.getConnectionState()) {
            case DISCOVERED:
                break;
            case CONNECTED:
                logger.info("connect(): BLEConnected, resolved: {}.", resolved);
                if (resolved) {
                    commandExecutor.execute(this::connect);
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
        commandExecutor.execute(this::connect);
    }

    @Override
    public void onCharacteristicWriteComplete(BluetoothCharacteristic characteristic,
            BluetoothCompletionStatus status) {
        super.onCharacteristicWriteComplete(characteristic, status);

        switch (status) {
            case SUCCESS:
                egloConnectCommand.updateCommandState(CommandState.SENT);
                break;
            default:
                egloConnectCommand.updateCommandState(CommandState.FAIL);
                break;
        }
    }

    @Override
    public void onCharacteristicReadComplete(BluetoothCharacteristic characteristic, BluetoothCompletionStatus status) {
        super.onCharacteristicReadComplete(characteristic, status);

        switch (status) {
            case SUCCESS:
                egloConnectCommand.updateCommandState(CommandState.SUCCESS);
                break;
            default:
                egloConnectCommand.updateCommandState(CommandState.FAIL);
                break;
        }
    }

    private void disconnect() {

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

        // TODO remote mit einbingen damit in gleichem mesh

        commandExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory(thing.getUID().getAsString(), true));

        if (device.getConnectionState() == BluetoothDevice.ConnectionState.CONNECTED && resolved) {
            commandExecutor.execute(this::connect);
        }
    }

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
        if (pairChar == null)
            return false;
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

        device.readCharacteristic(pairChar);
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

    private void writeCommand(byte command, byte[] data, short dest) {

        try {

            if (this.sessionKey.length == 0) {
                logger.warn("writeCommand(): Device {} not high level connected!", address);
                // updateCommandState(CommandState.FAIL);
                egloConnectCommand.updateCommandState(CommandState.FAIL);
                return;
            }
            BluetoothCharacteristic commandChar = device
                    .getCharacteristic(EgloConnectBindingConstants.COMMAND_CHAR_UUID);
            if (commandChar == null) {
                logger.warn("writeCommand(): Characteristic {} not found!",
                        EgloConnectBindingConstants.COMMAND_CHAR_UUID);
                egloConnectCommand.updateCommandState(CommandState.FAIL);
                return;
            }

            byte[] packet = EgloConnectPacketHelper.makeCommandPacket(this.sessionKey, address.toString(), dest,
                    command, data);
            logger.info("writeCommand(): {}: Writing command {} data {}", address.toString(), command, data);
            commandChar.setValue(packet);
            device.writeCharacteristic(commandChar);
            egloConnectCommand.updateCommandState(CommandState.QUEUED);

            // TODO timout + errorhandling statupdate whatever

            egloConnectCommand.awaitCommandStates(CommandState.FAIL, CommandState.SENT);

        } catch (Exception e) {
            logger.error("writeCommand(): exception\n{}", e.getMessage());
        } finally {
            logger.info("writeCommand(): Command State: {}", egloConnectCommand.getCommandState());
            egloConnectCommand.updateCommandState(CommandState.NEW);
        }
    }

    private void writeCommand(byte command, byte[] data) {
        short dest = meshID;
        this.writeCommand(command, data, dest);
    }

    private byte[] readStatus() throws Exception {
        // TODO add async await logic
        BluetoothCharacteristic statusChar = device.getCharacteristic(EgloConnectBindingConstants.STATUS_CHAR_UUID);
        if (statusChar == null) {
            return new byte[0];
        }
        device.readCharacteristic(statusChar);
        byte[] packet = statusChar.getByteValue();
        return EgloConnectPacketHelper.decryptPacket(this.sessionKey, address.toString(), packet);
    }

    private void turnOn() {
        byte[] data = new byte[1];
        data[0] = 0x01;
        commandExecutor.execute(() -> writeCommand(EgloConnectBindingConstants.C_POWER, data));
    }

    private void turnOff() {
        byte[] data = new byte[1];
        data[0] = 0x00;
        commandExecutor.execute(() -> writeCommand(EgloConnectBindingConstants.C_POWER, data));
    }

    private void setColor(PercentType redPercent, PercentType greenPercent, PercentType bluePercent) {
        byte red = (byte) ((redPercent.intValue() * 255) / 100);
        byte green = (byte) ((greenPercent.intValue() * 255) / 100);
        byte blue = (byte) ((bluePercent.intValue() * 255) / 100);
        byte[] data = { 0x04, red, green, blue };
        commandExecutor.execute(() -> writeCommand(EgloConnectBindingConstants.C_COLOR, data));
    }

    private void setBrightness(PercentType brightness) {
        // brightness in %
        byte[] data = { brightness.byteValue() };
        commandExecutor.execute(() -> writeCommand(EgloConnectBindingConstants.C_COLOR_BRIGHTNESS, data));
    }

    private void setWhiteBrightness(PercentType brightnessPercent) {
        // brightness in 1-127
        byte brightness = (byte) ((brightnessPercent.intValue() * 127) / 100);
        byte[] data = { brightness };
        commandExecutor.execute(() -> writeCommand(EgloConnectBindingConstants.C_WHITE_BRIGHTNESS, data));
    }

    private void setWhiteTemperature(PercentType temperature) {
        // brightness in %
        byte[] data = { temperature.byteValue() };
        commandExecutor.execute(() -> writeCommand(EgloConnectBindingConstants.C_WHITE_TEMPERATURE, data));
    }

    // private void setWhite(temperature, brightness) {
    // //brightness in %
    // byte[] data = new byte[1];
    // data[] ={
    // temperature
    // } ;
    // commandExecutor.execute(() -> writeCommand(EgloConnectBindingConstants.C_WHITE_TEMPERATURE, data));
    // data[] ={
    // brightness
    // } ;
    // commandExecutor.execute(() -> writeCommand(EgloConnectBindingConstants.C_WHITE_BRIGHTNESS, data));
    // }
}
