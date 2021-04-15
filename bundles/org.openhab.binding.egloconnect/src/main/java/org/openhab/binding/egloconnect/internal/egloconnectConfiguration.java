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

/**
 * The {@link egloconnectConfiguration} class contains fields mapping thing configuration parameters.
 *
 * @author Zeno Berkhan - Initial contribution
 */
@NonNullByDefault
public class egloconnectConfiguration {

    public String address = "";
    public String meshName = "unpaired";
    public String meshPassword = "1234";

    public int refreshInterval;

    // TODO mesh_name stuff undso

    @Override
    public String toString() {
        return "[address=" + address + ", refreshInterval=" + refreshInterval + "]";
    }
}
