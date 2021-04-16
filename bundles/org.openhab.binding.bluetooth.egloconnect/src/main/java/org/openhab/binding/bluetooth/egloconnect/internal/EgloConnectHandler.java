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
package org.openhab.binding.bluetooth.egloconnect.internal;

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
import org.openhab.binding.bluetooth.egloconnect.internal.command.EgloConnectCommand;
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
 * The {@link EgloConnectHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author oshl - Initial contribution
 */
@NonNullByDefault
public class EgloConnectHandler extends ConnectedBluetoothHandler implements ResponseListener {

    // TODO timeout + errorhandling nicht zu lang auf commands warten sonst alles putt
    // TODO widget initial fehlen elemente -> init wert von white/color variable
    // TODO widget updaten/ read state
    // TODO entweder in bluetooth binding oder außerhalb anzeigen, nicht beides

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
    private short meshID = 0;

    private EgloConnectCommand egloConnectCommand = new EgloConnectCommand();

    private @Nullable
    ExecutorService commandExecutor;

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
                egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.SENT);
                break;
            default:
                egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.FAIL);
                break;
        }
    }

    @Override
    public void onCharacteristicReadComplete(BluetoothCharacteristic characteristic, BluetoothCompletionStatus status) {
        super.onCharacteristicReadComplete(characteristic, status);

        switch (status) {
            case SUCCESS:
                egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.SUCCESS);
                break;
            default:
                egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.FAIL);
                break;
        }
    }


    private void connect() {
        //TODO abbruch bei fail

        try {

            logger.info("connect(): Connect to device {}...", address);

            if (device.getConnectionState() != BluetoothDevice.ConnectionState.CONNECTED) {
                logger.warn("connect(): Device {} not connected!", address);
                egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.FAIL);
                return;
            }
            if (!resolved) {
                logger.warn("connect(): Services of device {} not resolved!", address);
                egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.FAIL);
                return;
            }
            BluetoothCharacteristic pairChar = device.getCharacteristic(EgloConnectBindingConstants.PAIR_CHAR_UUID);
            if (pairChar == null) {
                logger.warn("connect(): Characteristic {} not found!", EgloConnectBindingConstants.PAIR_CHAR_UUID);
                egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.FAIL);
                return;
            }
            BluetoothCharacteristic statusChar = device.getCharacteristic(EgloConnectBindingConstants.STATUS_CHAR_UUID);
            if (statusChar == null) {
                logger.warn("connect(): Characteristic {} not found!", EgloConnectBindingConstants.PAIR_CHAR_UUID);
                egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.FAIL);
                return;
            }

            for (int i = 0; i < 8; i++) {
                sessionRandom[i] = (byte) (Math.random() * 256);
            }
            byte[] message = EgloConnectPacketHelper.makePairPacket(configuration.get().meshName,
                    configuration.get().meshPassword, sessionRandom);
            pairChar.setValue(message);
            device.writeCharacteristic(pairChar);
            egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.QUEUED);

            egloConnectCommand.awaitCommandStates(EgloConnectCommand.CommandState.FAIL, EgloConnectCommand.CommandState.SENT);

            egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.NEW);
            byte[] status = {1};
            statusChar.setValue(status);
            device.writeCharacteristic(statusChar);
            egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.QUEUED);

            egloConnectCommand.awaitCommandStates(EgloConnectCommand.CommandState.FAIL, EgloConnectCommand.CommandState.SENT);

            egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.QUEUED);
            device.readCharacteristic(pairChar);

            egloConnectCommand.awaitCommandStates(EgloConnectCommand.CommandState.FAIL, EgloConnectCommand.CommandState.SUCCESS);

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
            egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.NEW);
        }
    }

    private void disconnect() {

        logger.info("connect(): Disconnecting device {}!", address);
        device.disconnect();
        sessionKey = new byte[1];
    }

    @Override
    public void initialize() {

        logger.info("initialize(): Initialize");

        super.initialize();
        configuration = Optional.of(getConfigAs(EgloConnectConfiguration.class));

        logger.info("initialize(): Using configuration: {}", configuration.get());
        logger.info("initialize(): Mesh Name: {}, Mesh Password: {}", configuration.get().meshName,
                configuration.get().meshPassword);

        //TODO remote mit einbingen damit in gleichem mesh

        commandExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory(thing.getUID().getAsString(), true));

        if (device.getConnectionState() == BluetoothDevice.ConnectionState.CONNECTED && resolved) {
            commandExecutor.execute(this::connect);
        }
        //scheduledTask = scheduler.scheduleWithFixedDelay(this::updateChannels, 0, refreshInterval, TimeUnit.SECONDS);
    }


    private void writeCommand(byte command, byte[] data, short dest) {

        try {

            if (this.sessionKey.length == 0) {
                logger.warn("writeCommand(): Device {} not high level connected!", address);
                // updateCommandState(CommandState.FAIL);
                egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.FAIL);
                return;
            }
            BluetoothCharacteristic commandChar = device
                    .getCharacteristic(EgloConnectBindingConstants.COMMAND_CHAR_UUID);
            if (commandChar == null) {
                logger.warn("writeCommand(): Characteristic {} not found!",
                        EgloConnectBindingConstants.COMMAND_CHAR_UUID);
                egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.FAIL);
                return;
            }

            byte[] packet = EgloConnectPacketHelper.makeCommandPacket(this.sessionKey, address.toString(), dest,
                    command, data);
            logger.info("writeCommand(): {}: Writing command {} data {}", address.toString(), command, data);
            commandChar.setValue(packet);
            device.writeCharacteristic(commandChar);
            egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.QUEUED);

            egloConnectCommand.awaitCommandStates(EgloConnectCommand.CommandState.FAIL, EgloConnectCommand.CommandState.SENT);

        } catch (Exception e) {
            logger.error("writeCommand(): exception\n{}", e.getMessage());
        } finally {
            logger.info("writeCommand(): Command State: {}", egloConnectCommand.getCommandState());
            egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.NEW);
        }
    }

    private void writeCommand(byte command, byte[] data) {
        short dest = meshID;
        this.writeCommand(command, data, dest);
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
        byte[] data = {0x04, red, green, blue};
        commandExecutor.execute(() -> writeCommand(EgloConnectBindingConstants.C_COLOR, data));
    }

    private void setBrightness(PercentType brightness) {
        // brightness in %
        byte[] data = {brightness.byteValue()};
        commandExecutor.execute(() -> writeCommand(EgloConnectBindingConstants.C_COLOR_BRIGHTNESS, data));
    }

    private void setWhiteBrightness(PercentType brightnessPercent) {
        // brightness in 1-127
        byte brightness = (byte) ((brightnessPercent.intValue() * 127) / 100);
        byte[] data = {brightness};
        commandExecutor.execute(() -> writeCommand(EgloConnectBindingConstants.C_WHITE_BRIGHTNESS, data));
    }

    private void setWhiteTemperature(PercentType temperature) {
        // brightness in %
        byte[] data = {temperature.byteValue()};
        commandExecutor.execute(() -> writeCommand(EgloConnectBindingConstants.C_WHITE_TEMPERATURE, data));
    }






    /*


    private byte[] readStatus() {
        // TODO add async await logic
        BluetoothCharacteristic statusChar = device.getCharacteristic(EgloConnectBindingConstants.STATUS_CHAR_UUID);
        if (statusChar == null) {
            return new byte[0];
        }
        egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.QUEUED);
        device.readCharacteristic(statusChar);
        egloConnectCommand.awaitCommandStates(EgloConnectCommand.CommandState.FAIL, EgloConnectCommand.CommandState.SUCCESS);

        byte[] packet = statusChar.getByteValue();
        try {
            return EgloConnectPacketHelper.decryptPacket(this.sessionKey, address.toString(), packet);
        } catch (Exception e) {
            e.printStackTrace();
            return new byte[0];
        }
    }


    private void updateChannels() {
        commandExecutor.execute(this::readStatus);

        updateState(EgloConnectBindingConstants.CHANNEL_ID_POWER, OnOffType.valueOf("ON"));
        updateState(EgloConnectBindingConstants.CHANNEL_ID_COLOR, QuantityType.valueOf(Double.valueOf(parser.getTemperature()), SIUnits.CELSIUS));
        updateState(EgloConnectBindingConstants.CHANNEL_ID_COLOR_BRIGHTNESS, QuantityType.valueOf(Double.valueOf(parser.getPressure()), Units.MILLIBAR));
        updateState(EgloConnectBindingConstants.CHANNEL_ID_WHITE_BRIGHTNESS, QuantityType.valueOf(Double.valueOf(parser.getCo2()), Units.PARTS_PER_MILLION));
        updateState(EgloConnectBindingConstants.CHANNEL_ID_WHITE_TEMPERATURE, QuantityType.valueOf(Double.valueOf(parser.getTvoc()), PARTS_PER_BILLION));
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
  */

 /*
    private boolean setMesh(String newMeshName, String newMeshPassword, String newMeshLongTermKey) throws Exception {

        // TODO config meshname ändern logik
        try {

            logger.info("setMesh(): New Mesh Parameters for device {}...", address);


            if (this.sessionKey.length == 0) {
                logger.warn("setMesh(): Device {} not high level connected!", address);
                egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.FAIL);
                return false;
            }
            if (newMeshName.length() > 16) {
                logger.warn("setMesh(): Mesh Name {} is too long!", newMeshName);
                egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.FAIL);
                return false;
            }
            if (newMeshPassword.length() > 16) {
                logger.warn("setMesh(): Mesh Password {} is too long!", newMeshPassword);
                egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.FAIL);
                return false;
            }
            if (newMeshLongTermKey.length() > 16) {
                logger.warn("setMesh(): Mesh Long Term Key {} is too long!", newMeshLongTermKey);
                egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.FAIL);
                return false;
            }
            BluetoothCharacteristic pairChar = device.getCharacteristic(EgloConnectBindingConstants.PAIR_CHAR_UUID);
            if (pairChar == null) {
                logger.warn("connect(): Characteristic {} not found!", EgloConnectBindingConstants.PAIR_CHAR_UUID);
                egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.FAIL);
                return false;
            }

            egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.NEW);
            byte[] message = EgloConnectPacketHelper.encrypt(this.sessionKey, newMeshName.getBytes(StandardCharsets.UTF_8));
            byte[] tmp = new byte[1 + message.length];
            tmp[0] = 0x4;
            for (int i = 0; i < message.length; i++) {
                tmp[i + 1] = message[i];
            }
            message = tmp;
            pairChar.setValue(message);
            egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.QUEUED);
            device.writeCharacteristic(pairChar);
            egloConnectCommand.awaitCommandStates(EgloConnectCommand.CommandState.FAIL, EgloConnectCommand.CommandState.SENT);

            egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.NEW);
            message = EgloConnectPacketHelper.encrypt(this.sessionKey, newMeshPassword.getBytes(StandardCharsets.UTF_8));
            tmp = new byte[1 + message.length];
            tmp[0] = 0x5;
            for (int i = 0; i < message.length; i++) {
                tmp[i + 1] = message[i];
            }
            message = tmp;
            pairChar.setValue(message);
            egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.QUEUED);
            device.writeCharacteristic(pairChar);
            egloConnectCommand.awaitCommandStates(EgloConnectCommand.CommandState.FAIL, EgloConnectCommand.CommandState.SENT);

            egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.NEW);
            message = EgloConnectPacketHelper.encrypt(this.sessionKey, newMeshLongTermKey.getBytes(StandardCharsets.UTF_8));
            tmp = new byte[1 + message.length];
            tmp[0] = 0x6;
            for (int i = 0; i < message.length; i++) {
                tmp[i + 1] = message[i];
            }
            pairChar.setValue(message);
            egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.QUEUED);
            device.writeCharacteristic(pairChar);
            egloConnectCommand.awaitCommandStates(EgloConnectCommand.CommandState.FAIL, EgloConnectCommand.CommandState.SENT);

            egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.QUEUED);
            device.readCharacteristic(pairChar);
            egloConnectCommand.awaitCommandStates(EgloConnectCommand.CommandState.FAIL, EgloConnectCommand.CommandState.SUCCESS);

            byte[] response = pairChar.getByteValue();

            if (response[0] == 0x07) {
                logger.info("setMesh(): Mesh network settings accepted.");
                return true;
            } else {
                logger.warn("setMesh(): Mesh network settings change failed!");
                return false;
            }
        } catch (Exception e) {
            logger.error("setMesh(): exception\n{}", e.getMessage());
            return false;
        } finally {
            logger.info("setMesh(): Command State: {}", egloConnectCommand.getCommandState());
            egloConnectCommand.updateCommandState(EgloConnectCommand.CommandState.NEW);
        }
    }


    //TODO setMesh debuggen, resoonse = 14 statt erwartet 7
    @Override
    public void handleConfigurationUpdate(Map<String, Object> configurationParameters) {

        logger.info("{} config updated!", getThing().getUID());
        String newMName = (@NonNull String) configurationParameters.get("meshName");
        String newMPassword = (@NonNull String) configurationParameters.get("meshPassword");
        //TODO
        String newMeshLongTermKey = "1234567890";

        if (device.getConnectionState() == BluetoothDevice.ConnectionState.CONNECTED && resolved) {
            try {
                if (!newMPassword.equals(configuration.get().meshPassword) || !newMName.equals(configuration.get().meshName)) {

                    logger.info("set new mesh name: {} and new password: {}", newMName, newMPassword);

                    if (setMesh(newMName, newMPassword, newMeshLongTermKey)) {
                        logger.info("setting new mesh stuff success");
                        if (configuration.isPresent()) {
                            configuration.get().meshName = newMName;
                            configuration.get().meshPassword = newMPassword;
                            //TODO ? ode rneu connect ?
                            super.handleConfigurationUpdate(configurationParameters);
                        }
                    } else {
                        logger.warn("setting new mesh stuff failed");
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }*/
}