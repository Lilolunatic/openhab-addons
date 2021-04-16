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

import java.util.UUID;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bluetooth.BluetoothBindingConstants;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link EgloConnectBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author oshl - Initial contribution
 */
@NonNullByDefault
public class EgloConnectBindingConstants {

    private static final String BINDING_ID = "egloconnect";

    // List of all Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_BULB = new ThingTypeUID(BluetoothBindingConstants.BINDING_ID,
            "awox_bulb");

    // List of all Channel ids
    public static final String CHANNEL_ID_POWER = "power";
    public static final String CHANNEL_ID_WHITE_TEMPERATURE = "white_temperature";
    public static final String CHANNEL_ID_WHITE_BRIGHTNESS = "white_brightness";
    public static final String CHANNEL_ID_COLOR = "color";
    public static final String CHANNEL_ID_COLOR_BRIGHTNESS = "color_brightness";

    public static final UUID STATUS_CHAR_UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1911");
    public static final UUID COMMAND_CHAR_UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1912");
    public static final UUID PAIR_CHAR_UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1914");

    public static final byte C_POWER = (byte) 0xd0;
    public static final byte C_WHITE_TEMPERATURE = (byte) 0xf0;
    public static final byte C_WHITE_BRIGHTNESS = (byte) 0xf1;
    public static final byte C_COLOR = (byte) 0xe2;
    public static final byte C_COLOR_BRIGHTNESS = (byte) 0xf2;
}
