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

import java.util.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.BluetoothBindingConstants;
import org.openhab.binding.bluetooth.BluetoothDevice;
import org.openhab.binding.bluetooth.discovery.BluetoothDiscoveryDevice;
import org.openhab.binding.bluetooth.discovery.BluetoothDiscoveryParticipant;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This discovery participant is able to recognize awox devices and create discovery results for them.
 *
 * @author Nina Hartmann - Initial contribution
 */
@NonNullByDefault
@Component
public class EgloConnectDiscoveryParticipant implements BluetoothDiscoveryParticipant {
    private final Logger logger = LoggerFactory.getLogger(EgloConnectDiscoveryParticipant.class);

    @Override
    public Set<ThingTypeUID> getSupportedThingTypeUIDs() {
        return Collections.singleton(EgloConnectBindingConstants.THING_TYPE_BULB);
    }

    @Override
    public @Nullable ThingUID getThingUID(BluetoothDiscoveryDevice device) {
        //TODO bulb und remote unterscheiden
        if (device.getConnectionState() == BluetoothDevice.ConnectionState.CONNECTED && isAwoxDevice(device)) {
            return new ThingUID(EgloConnectBindingConstants.THING_TYPE_BULB, device.getAdapter().getUID(),
                    device.getAddress().toString().toLowerCase().replace(":", ""));
        }
        return null;
    }

    @Override
    public @Nullable DiscoveryResult createResult(BluetoothDiscoveryDevice device) {
        ThingUID thingUID = getThingUID(device);
        if (thingUID == null) {
            return null;
        }
        String label = "EGLO Connect Device";
        Map<String, Object> properties = new HashMap<>();
        properties.put(BluetoothBindingConstants.CONFIGURATION_ADDRESS, device.getAddress().toString());
        properties.put(Thing.PROPERTY_VENDOR, "Awox");

        return DiscoveryResultBuilder.create(thingUID).withProperties(properties)
                .withRepresentationProperty(BluetoothBindingConstants.CONFIGURATION_ADDRESS)
                .withBridge(device.getAdapter().getUID()).withLabel(label).build();
    }

    @Override
    public boolean requiresConnection(BluetoothDiscoveryDevice device) {
        return isAwoxDevice(device);
    }

    @Override
    public int order() {
        return 0;
    }

    private boolean isAwoxDevice(BluetoothDiscoveryDevice device) {
        if (device.getAddress().toString().toUpperCase().equals("A4:C1:38:46:10:4E")) {
            logger.info("Discovered eglo device with Adress: {} and ManufactureId: {}", device.getAddress(),
                    device.getManufacturerId());
        }

        if (device.getManufacturerId() != null && device.getManufacturerId() == 352) {
            logger.info("Discovered eglo device with Adress: {} and ManufactureId: {}", device.getAddress(),
                    device.getManufacturerId());
            return true;
        }
        return false;
    }
}
