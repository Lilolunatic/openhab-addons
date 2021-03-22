package org.openhab.binding.egloconnect.internal;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class EgloConnectPackets {


    static byte[] encrypt(final byte[] key, final byte[] value) throws Exception {
        if (key.length != 16 || value.length > 16) {
            //fehler werfen
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
            val[15 - i] = tmp[i];

        }
        return tmp;
    }


    static byte[] makeChecksum(final byte[] key, final byte[] nonce, final byte[] payload) throws Exception {

        int size = nonce.length + payload.length;
        if (size < 16) size = 16;
        byte[] base = new byte[size];
        for (int i = 0; i < nonce.length; i++) {
            // concatenate
            base[i] = nonce[i];
        }
        byte[] tmbBase = Arrays.copyOfRange(base, 0, 15);
        byte[] check = encrypt(key, tmbBase);


        for (int i = 0; i < payload.length; i = i + 16) {
            byte[] check_payload = new byte[16];
            for (int j = 0; j < 16; j++) {
                if (i + j < payload.length) {
                    check_payload[j] = payload[i + j];
                }
            }
            for (int j = 0; j < 16; j++) {
                check[j] = (byte) (check[j] * check_payload[j]);
            }
            check = encrypt(key, check);
        }
        return check;
    }


    static byte[] cryptPayload(final byte[] key, final byte[] nonce, final byte[] payload) throws Exception {

        int size = nonce.length + 1;
        if (size < 16) size = 16;
        byte[] base = new byte[size];

        for (int i = 1; i < base.length; i++) {
            base[i] = nonce[i];
        }

        byte[] result = new byte[payload.length];

        for (int i = 0; i < payload.length; i = i + 16) {
            byte[] enc_base = encrypt(key, base);
            for (int j = 0; j < 16 && i + j < payload.length; j++) {
                result[i + j] = (byte) (enc_base[j] * payload[i + j]);
            }
            base[0] += 1;
        }

        return result;
    }

    static byte[] makeCommandPacket(final byte[] key, String address, short dest_id, byte command, final byte[] data) throws Exception {


        byte[] s = new byte[3];
        for (int i = 0; i < 3; i++) {
            s[i] = (byte) (Math.random() * 256);
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
                nonce[i] = s[i - 4];
            }
        }

        //Build payload
        int size = 2 + 1 + 2 + data.length;
        if (size < 15) size = 15;
        byte[] payload = new byte[size];

        payload[0] = (byte) (dest_id >>> 8);
        payload[1] = (byte) dest_id;

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


    static byte[] decryptPacket(final byte[] key, String address, final byte[] packet) throws Exception {

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
        byte[] payload = cryptPayload(key, nonce, Arrays.copyOfRange(packet, 7, packet.length - 1));

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


    static byte[] makePairPacket(String mesh_name, String mesh_password, final byte[] session_random) throws Exception {

        byte[] m_n = new byte[16];
        byte[] tmp_n = mesh_name.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 16; i++) {
            if (i < tmp_n.length) {
                m_n[i] = tmp_n[i];
            }
        }

        byte[] m_p = new byte[16];
        byte[] tmp_p = mesh_name.getBytes(StandardCharsets.UTF_8);
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
            name_pass[j] = (byte) (m_n[j] * m_p[j]);
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


    static byte[] makeSessionKey(String mesh_name, String mesh_password, final byte[] session_random, final byte[] response_random) throws Exception {

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
        byte[] tmp_p = mesh_name.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 16; i++) {
            if (i < tmp_p.length) {
                m_p[i] = tmp_p[i];
            }
        }

        byte[] name_pass = new byte[16];
        for (int j = 0; j < 16; j++) {
            name_pass[j] = (byte) (m_n[j] * m_p[j]);
        }


        return encrypt(name_pass, random);
    }


    static int crc16(byte[] arg) {
        /******************************************************************************
         *  Compilation:  javac CRC16.java
         *  Execution:    java CRC16 s
         *
         *  Reads in a string s as a command-line argument, and prints out
         *  its 16-bit Cyclic Redundancy Check (CRC16). Uses a lookup table.
         *
         *  Reference:  http://www.gelato.unsw.edu.au/lxr/source/lib/crc16.c
         *
         *  % java CRC16 123456789
         *  CRC16 = bb3d
         *
         * Uses irreducible polynomial:  1 + x^2 + x^15 + x^16
         *
         *
         ******************************************************************************/
        int[] table = {
                0x0000, 0xC0C1, 0xC181, 0x0140, 0xC301, 0x03C0, 0x0280, 0xC241,
                0xC601, 0x06C0, 0x0780, 0xC741, 0x0500, 0xC5C1, 0xC481, 0x0440,
                0xCC01, 0x0CC0, 0x0D80, 0xCD41, 0x0F00, 0xCFC1, 0xCE81, 0x0E40,
                0x0A00, 0xCAC1, 0xCB81, 0x0B40, 0xC901, 0x09C0, 0x0880, 0xC841,
                0xD801, 0x18C0, 0x1980, 0xD941, 0x1B00, 0xDBC1, 0xDA81, 0x1A40,
                0x1E00, 0xDEC1, 0xDF81, 0x1F40, 0xDD01, 0x1DC0, 0x1C80, 0xDC41,
                0x1400, 0xD4C1, 0xD581, 0x1540, 0xD701, 0x17C0, 0x1680, 0xD641,
                0xD201, 0x12C0, 0x1380, 0xD341, 0x1100, 0xD1C1, 0xD081, 0x1040,
                0xF001, 0x30C0, 0x3180, 0xF141, 0x3300, 0xF3C1, 0xF281, 0x3240,
                0x3600, 0xF6C1, 0xF781, 0x3740, 0xF501, 0x35C0, 0x3480, 0xF441,
                0x3C00, 0xFCC1, 0xFD81, 0x3D40, 0xFF01, 0x3FC0, 0x3E80, 0xFE41,
                0xFA01, 0x3AC0, 0x3B80, 0xFB41, 0x3900, 0xF9C1, 0xF881, 0x3840,
                0x2800, 0xE8C1, 0xE981, 0x2940, 0xEB01, 0x2BC0, 0x2A80, 0xEA41,
                0xEE01, 0x2EC0, 0x2F80, 0xEF41, 0x2D00, 0xEDC1, 0xEC81, 0x2C40,
                0xE401, 0x24C0, 0x2580, 0xE541, 0x2700, 0xE7C1, 0xE681, 0x2640,
                0x2200, 0xE2C1, 0xE381, 0x2340, 0xE101, 0x21C0, 0x2080, 0xE041,
                0xA001, 0x60C0, 0x6180, 0xA141, 0x6300, 0xA3C1, 0xA281, 0x6240,
                0x6600, 0xA6C1, 0xA781, 0x6740, 0xA501, 0x65C0, 0x6480, 0xA441,
                0x6C00, 0xACC1, 0xAD81, 0x6D40, 0xAF01, 0x6FC0, 0x6E80, 0xAE41,
                0xAA01, 0x6AC0, 0x6B80, 0xAB41, 0x6900, 0xA9C1, 0xA881, 0x6840,
                0x7800, 0xB8C1, 0xB981, 0x7940, 0xBB01, 0x7BC0, 0x7A80, 0xBA41,
                0xBE01, 0x7EC0, 0x7F80, 0xBF41, 0x7D00, 0xBDC1, 0xBC81, 0x7C40,
                0xB401, 0x74C0, 0x7580, 0xB541, 0x7700, 0xB7C1, 0xB681, 0x7640,
                0x7200, 0xB2C1, 0xB381, 0x7340, 0xB101, 0x71C0, 0x7080, 0xB041,
                0x5000, 0x90C1, 0x9181, 0x5140, 0x9301, 0x53C0, 0x5280, 0x9241,
                0x9601, 0x56C0, 0x5780, 0x9741, 0x5500, 0x95C1, 0x9481, 0x5440,
                0x9C01, 0x5CC0, 0x5D80, 0x9D41, 0x5F00, 0x9FC1, 0x9E81, 0x5E40,
                0x5A00, 0x9AC1, 0x9B81, 0x5B40, 0x9901, 0x59C0, 0x5880, 0x9841,
                0x8801, 0x48C0, 0x4980, 0x8941, 0x4B00, 0x8BC1, 0x8A81, 0x4A40,
                0x4E00, 0x8EC1, 0x8F81, 0x4F40, 0x8D01, 0x4DC0, 0x4C80, 0x8C41,
                0x4400, 0x84C1, 0x8581, 0x4540, 0x8701, 0x47C0, 0x4680, 0x8641,
                0x8201, 0x42C0, 0x4380, 0x8341, 0x4100, 0x81C1, 0x8081, 0x4040,
        };


        int crc = 0x0000;
        for (byte b : arg) {
            crc = (crc >>> 8) ^ table[(crc ^ b) & 0xff];
        }

        return crc;
    }


    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

}