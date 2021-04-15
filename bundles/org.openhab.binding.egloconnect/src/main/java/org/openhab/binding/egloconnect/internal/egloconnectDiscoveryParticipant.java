package org.openhab.binding.egloconnect.internal;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
 * @author Zeno Berkhan - Initial contribution
 */
@NonNullByDefault
@Component
public class egloconnectDiscoveryParticipant implements BluetoothDiscoveryParticipant {

    private final Logger logger = LoggerFactory.getLogger(egloconnectDiscoveryParticipant.class);

    @Override
    public Set<ThingTypeUID> getSupportedThingTypeUIDs() {
        return Collections.singleton(egloconnectBindingConstants.THING_TYPE_AWOX_BULB);
    }

    @Override
    public @Nullable ThingUID getThingUID(BluetoothDiscoveryDevice device) {
        if (device.getConnectionState() == BluetoothDevice.ConnectionState.CONNECTED && isAwoxDevice(device)) {
            return new ThingUID(egloconnectBindingConstants.THING_TYPE_AWOX_BULB, device.getAdapter().getUID(),
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
