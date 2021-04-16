package org.openhab.binding.bluetooth.egloconnect;

import static org.junit.jupiter.api.Assertions.*;
import static org.openhab.binding.bluetooth.egloconnect.internal.EgloConnectPacketHelper.hexStringToByteArray;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.*;
import org.openhab.binding.bluetooth.egloconnect.internal.EgloConnectPacketHelper;

public class EgloConnectPacketHelperTest {

    static byte[] key;
    static byte[] val;
    static byte[] nonce;
    static byte[] payload;
    static String address;
    static short dest_id;
    static byte command;
    static byte[] data;
    static byte[] packet;
    static String mesh_name;
    static String mesh_password;
    static byte[] session_random;
    static byte[] response_random;
    static byte[] arg;
    static byte[] s;

    @BeforeAll
    static void initAll() throws Exception {
        key = "1234567890123456".getBytes(StandardCharsets.UTF_8);
        val = "ACAB".getBytes(StandardCharsets.UTF_8);
        nonce = "12345".getBytes(StandardCharsets.UTF_8);
        payload = "12345".getBytes(StandardCharsets.UTF_8);
        address = "A4:C1:38:46:10:4E";
        dest_id = 6;
        command = 6;
        data = "12345678901234567890".getBytes(StandardCharsets.UTF_8);
        s = "ABC".getBytes(StandardCharsets.UTF_8);
        mesh_name = "name";
        mesh_password = "password";
        arg = s;
        session_random = s;
        response_random = s;
    }

    @Test
    public void testEncrypt() throws Exception {
        assertArrayEquals(hexStringToByteArray("1b06052d1c680f18cf3fd3dea98fa63d"),
                EgloConnectPacketHelper.encrypt(key, val));
    }

    @Test
    public void testMakeChecksum() throws Exception {
        // nonce+payload < 16
        assertArrayEquals(hexStringToByteArray("95605d685e0c11d823d0c5fe1806d21d"),
                EgloConnectPacketHelper.makeChecksum(key, nonce, payload));
        // nonce+payload > 16
        byte[] nonce_ = "12345678".getBytes(StandardCharsets.UTF_8);
        byte[] payload_ = "123456789".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(hexStringToByteArray("9c401d11bc8b3575a83e436464b637c2"),
                EgloConnectPacketHelper.makeChecksum(key, nonce_, payload_));
    }

    @Test
    public void testCryptPayload() throws Exception {
        assertArrayEquals(hexStringToByteArray("aee3a2a200"),
                EgloConnectPacketHelper.cryptPayload(key, nonce, payload));
    }

    @Test
    public void testMakeCommandPacket() throws Exception {
        assertArrayEquals(hexStringToByteArray("4142439793a288d5c1fd2716e023fc0cab97f235ff1eba7aff10ff63cada"),
                EgloConnectPacketHelper.makeCommandPacket(key, address, dest_id, command, data, s));
    }

    @Test
    public void testDecryptPacket() throws Exception {
        assertArrayEquals(hexStringToByteArray("de691a0000026bdc60014e8a034d7f3c00ff0000"),
                EgloConnectPacketHelper.decryptPacket(hexStringToByteArray("3c153c72faeb67edf9d9cf09dd91efe5"), address,
                        hexStringToByteArray("de691a0000026be215822f3687f3247cc73c56df")));
    }

    @Test
    public void testMakePairPacket() throws Exception {
        assertArrayEquals(hexStringToByteArray("0c4142431f41f3a0345dee65"),
                EgloConnectPacketHelper.makePairPacket(mesh_name, mesh_password, session_random));
    }

    @Test
    public void testMakeSessionKey() throws Exception {
        assertArrayEquals(hexStringToByteArray("f2de1bbf9cc46d92df00ac5564630006"),
                EgloConnectPacketHelper.makeSessionKey(mesh_name, mesh_password, session_random, response_random));
    }

    @Test
    public void testCrc16() {
        assertEquals(34128, EgloConnectPacketHelper.crc16(arg));
    }
}
