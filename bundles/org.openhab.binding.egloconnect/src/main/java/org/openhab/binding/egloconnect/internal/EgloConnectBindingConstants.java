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

    // private static final String BINDING_ID = "bluetooth";

    // List of all Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_AWOX_BULB = new ThingTypeUID(BluetoothBindingConstants.BINDING_ID,
            "egloconnect");

    // List of all Channel ids
    public static final String CHANNEL_ID_COLOR = "color";
}
