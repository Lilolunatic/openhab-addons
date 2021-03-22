package org.openhab.binding.egloconnect.internal;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class EgloConnectPacketHelper {


    public static byte[] encrypt(final byte[] key, final byte[] value) throws Exception {
        if (key.length != 16 || value.length > 16) {
            //fehler werfen
            throw new IllegalArgumentException();
        }
        byte[] val = new byte[16];
        byte[] k = new byte[16];

        for (int i = 0; i < 16; i++) {
            // reverse val and key
            if (i < value.length) {
                val[15 - i] = value[i];
            }
            k[15 - i] = key[i];
        }
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"));

        val = cipher.doFinal(val);

        byte[] tmp = new byte[16];
        for (int i = 0; i < 16; i++) {
            // reverse val and key
            tmp[15 - i] = val[i];

        }
        return tmp;
    }


    public static byte[] makeChecksum(final byte[] key, final byte[] nonce, final byte[] payload) throws Exception {

        //int size = nonce.length + payload.length;
        //if (size < 16) size = 16;
        byte[] base = new byte[16];
        for (int i = 0; i < nonce.length; i++) {
            // concatenate
            base[i] = nonce[i];
        }
        base[nonce.length] = (byte) payload.length;

        //byte[] tmbBase = Arrays.copyOfRange(base, 0, 15); //15 falsch und unnötig geworden
        byte[] check = encrypt(key, base);


        for (int i = 0; i < payload.length; i = i + 16) {
            byte[] check_payload = new byte[16];
            for (int j = 0; j < 16; j++) {
                if (i + j < payload.length) {
                    check_payload[j] = payload[i + j];
                }
            }
            for (int j = 0; j < 16; j++) {
                check[j] = (byte) (check[j] ^ check_payload[j]);
            }
            check = encrypt(key, check);
        }
        return check;
    }


    public static byte[] cryptPayload(final byte[] key, final byte[] nonce, final byte[] payload) throws Exception {

        int size = nonce.length + 1;
        if (size < 16) size = 16;
        byte[] base = new byte[size];

        for (int i = 0; i < nonce.length; i++) {
            base[i + 1] = nonce[i];
        }

        byte[] result = new byte[payload.length];

        for (int i = 0; i < payload.length; i = i + 16) {
            byte[] enc_base = encrypt(key, base);
            for (int j = 0; j < 16 && i + j < payload.length; j++) {
                result[i + j] = (byte) (enc_base[j] ^ payload[i + j]);
            }
            base[0] += 1;
        }

        return result;
    }


    public static byte[] makeCommandPacket(final byte[] key, String address, short dest_id, byte command, final byte[] data, final byte[] random) throws Exception {


        byte[] s = new byte[3];
        if (random != null && random.length == 3) {
            s = random;
        } else {
            for (int i = 0; i < 3; i++) {
                s[i] = (byte) (Math.random() * 256);
            }
        }

        //Build nonce
        byte[] tmp = hexStringToByteArray(address.replace(":", ""));
        byte[] a = new byte[tmp.length];
        for (int i = 0; i < tmp.length; i++) {
            a[tmp.length - 1 - i] = tmp[i];

        }
        byte[] nonce = new byte[8];
        for (int i = 0; i < 8; i++) {
            if (i < 4) {
                nonce[i] = a[i];

            } else if (i == 4) {
                nonce[i] = 1;
            } else {
                nonce[i] = s[i - 5];
            }
        }

        //Build payload
        int size = 2 + 1 + 2 + data.length;
        if (size < 15) size = 15;
        byte[] payload = new byte[size];

        payload[0] = (byte) dest_id;
        payload[1] = (byte) (dest_id >>> 8);

        payload[2] = command;

        payload[3] = 0x60;
        payload[4] = 0x01;

        for (int i = 5; i < data.length + 5; i++) {
            payload[i] = data[i - 5];
        }

        //Compute checksum
        byte[] check = makeChecksum(key, nonce, payload);

        //Encrypt payload
        payload = cryptPayload(key, nonce, payload);

        //Make packet
        byte[] packet = new byte[3 + 2 + payload.length];
        packet[0] = s[0];
        packet[1] = s[1];
        packet[2] = s[2];

        packet[3] = check[0];
        packet[4] = check[1];

        for (int i = 0; i < payload.length; i++) {
            packet[i + 5] = payload[i];
        }

        return packet;
    }


    public static byte[] decryptPacket(final byte[] key, String address, final byte[] packet) throws Exception {

        //Build nonce
        byte[] tmp = hexStringToByteArray(address.replace(":", ""));
        byte[] a = new byte[tmp.length];
        for (int i = 0; i < tmp.length; i++) {
            a[tmp.length - 1 - i] = tmp[i];

        }
        byte[] nonce = new byte[8];
        for (int i = 0; i < 8; i++) {
            if (i < 3) {
                nonce[i] = a[i];
            } else {
                nonce[i] = packet[i - 3];
            }
        }


        //Decrypt Payload
        byte[] payload = cryptPayload(key, nonce, Arrays.copyOfRange(packet, 7, packet.length));

        //Compute checksum
        byte[] check = makeChecksum(key, nonce, payload);

        //Check bytes
        if (check[0] != packet[5] || check[1] != packet[6]) return null;

        //Decrypted packet
        byte[] dec_packet = new byte[7 + payload.length];
        for (int i = 0; i < dec_packet.length; i++) {
            if (i < 7) {
                dec_packet[i] = packet[i];
            } else {
                dec_packet[i] = payload[i - 7];
            }
        }

        return dec_packet;
    }


    public static byte[] makePairPacket(String mesh_name, String mesh_password, final byte[] session_random) throws Exception {

        byte[] m_n = new byte[16];
        byte[] tmp_n = mesh_name.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 16; i++) {
            if (i < tmp_n.length) {
                m_n[i] = tmp_n[i];
            }
        }

        byte[] m_p = new byte[16];
        byte[] tmp_p = mesh_password.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 16; i++) {
            if (i < tmp_p.length) {
                m_p[i] = tmp_p[i];
            }
        }

        byte[] s_r = new byte[16];
        for (int i = 0; i < session_random.length && i < 16; i++) {
            s_r[i] = session_random[i];
        }

        byte[] name_pass = new byte[16];
        for (int j = 0; j < 16; j++) {
            name_pass[j] = (byte) (m_n[j] ^ m_p[j]);
        }

        byte[] enc = encrypt(s_r, name_pass);

        byte[] packet = new byte[1 + session_random.length + 8];
        packet[0] = 0x0c;
        for (int i = 0; i < session_random.length; i++) {
            packet[i + 1] = session_random[i];
        }
        for (int i = 0; i < 8; i++) {
            packet[i + session_random.length + 1] = enc[i];
        }

        return packet;
    }


    public static byte[] makeSessionKey(String mesh_name, String mesh_password, final byte[] session_random, final byte[] response_random) throws Exception {

        byte[] random = new byte[session_random.length + response_random.length];
        for (int i = 0; i < random.length; i++) {
            if (i < session_random.length) {
                random[i] = session_random[i];
            } else {
                random[i] = response_random[i - session_random.length];
            }
        }

        byte[] m_n = new byte[16];
        byte[] tmp_n = mesh_name.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 16; i++) {
            if (i < tmp_n.length) {
                m_n[i] = tmp_n[i];
            }
        }

        byte[] m_p = new byte[16];
        byte[] tmp_p = mesh_password.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 16; i++) {
            if (i < tmp_p.length) {
                m_p[i] = tmp_p[i];
            }
        }

        byte[] name_pass = new byte[16];
        for (int j = 0; j < 16; j++) {
            name_pass[j] = (byte) (m_n[j] ^ m_p[j]);
        }


        return encrypt(name_pass, random);
    }


    public static int crc16(byte[] arg) {
        int[] polynom = {0x0, 0xa001};
        int crc = 0xffff;

        for (int a : arg) {
            for (int i = 0; i < 8; i++) {
                int ind = (crc ^ a) & 1;
                crc = (crc >>> 1) ^ polynom[ind];
                a = a >>> 1;
            }
        }
        return crc;
    }


    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

}