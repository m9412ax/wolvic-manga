/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * m115 protocol crypto, ported 1:1 from github.com/SheltonZhu/115driver
 * (pkg/crypto/m115). 115's app downurl endpoint requires this obfuscation on
 * the request and the same scheme to unwrap the response. The legacy plain
 * webapi endpoint is throttled/blocked since 2026, which is why the old
 * Manga115Api.resolveDirectUrl returned nothing and the reader showed no files.
 *
 * Wire format:
 *   Encode(plain, key) = base64( RSA_pkcs1v15( key[16] || obfuscate(plain,key) ) )
 *   Decode(b64, key)   = deobfuscate( RSA_pub( base64_decode(b64) )[16:], key )
 *
 * No BouncyCastle needed - BigInteger.modPow is enough (the "decrypt" side
 * also uses the public exponent, matching the Go driver).
 */
final class M115Crypto {

    private static final BigInteger N = new BigInteger(
            "8686980c0f5a24c4b9d43020cd2c22703ff3f450756529058b1cf88f09b86021"
          + "36477198a6e2683149659bd122c33592fdb5ad47944ad1ea4d36c6b172aad633"
          + "8c3bb6ac6227502d010993ac967d1aef00f0c8e038de2e4d3bc2ec368af2e9f1"
          + "0a6f1eda4f7262f136420c07c331b871bf139f74f3010e3c4fe57df3afb71683", 16);
    private static final BigInteger E = new BigInteger("10001", 16);
    private static final int KEY_LEN = (N.bitLength() + 7) / 8; // 256

    private static final byte[] XOR_KEY_SEED = {
            (byte)0xf0, (byte)0xe5, (byte)0x69, (byte)0xae, (byte)0xbf, (byte)0xdc, (byte)0xbf, (byte)0x8a,
            (byte)0x1a, (byte)0x45, (byte)0xe8, (byte)0xbe, (byte)0x7d, (byte)0xa6, (byte)0x73, (byte)0xb8,
            (byte)0xde, (byte)0x8f, (byte)0xe7, (byte)0xc4, (byte)0x45, (byte)0xda, (byte)0x86, (byte)0xc4,
            (byte)0x9b, (byte)0x64, (byte)0x8b, (byte)0x14, (byte)0x6a, (byte)0xb4, (byte)0xf1, (byte)0xaa,
            (byte)0x38, (byte)0x01, (byte)0x35, (byte)0x9e, (byte)0x26, (byte)0x69, (byte)0x2c, (byte)0x86,
            (byte)0x00, (byte)0x6b, (byte)0x4f, (byte)0xa5, (byte)0x36, (byte)0x34, (byte)0x62, (byte)0xa6,
            (byte)0x2a, (byte)0x96, (byte)0x68, (byte)0x18, (byte)0xf2, (byte)0x4a, (byte)0xfd, (byte)0xbd,
            (byte)0x6b, (byte)0x97, (byte)0x8f, (byte)0x4d, (byte)0x8f, (byte)0x89, (byte)0x13, (byte)0xb7,
            (byte)0x6c, (byte)0x8e, (byte)0x93, (byte)0xed, (byte)0x0e, (byte)0x0d, (byte)0x48, (byte)0x3e,
            (byte)0xd7, (byte)0x2f, (byte)0x88, (byte)0xd8, (byte)0xfe, (byte)0xfe, (byte)0x7e, (byte)0x86,
            (byte)0x50, (byte)0x95, (byte)0x4f, (byte)0xd1, (byte)0xeb, (byte)0x83, (byte)0x26, (byte)0x34,
            (byte)0xdb, (byte)0x66, (byte)0x7b, (byte)0x9c, (byte)0x7e, (byte)0x9d, (byte)0x7a, (byte)0x81,
            (byte)0x32, (byte)0xea, (byte)0xb6, (byte)0x33, (byte)0xde, (byte)0x3a, (byte)0xa9, (byte)0x59,
            (byte)0x34, (byte)0x66, (byte)0x3b, (byte)0xaa, (byte)0xba, (byte)0x81, (byte)0x60, (byte)0x48,
            (byte)0xb9, (byte)0xd5, (byte)0x81, (byte)0x9c, (byte)0xf8, (byte)0x6c, (byte)0x84, (byte)0x77,
            (byte)0xff, (byte)0x54, (byte)0x78, (byte)0x26, (byte)0x5f, (byte)0xbe, (byte)0xe8, (byte)0x1e,
            (byte)0x36, (byte)0x9f, (byte)0x34, (byte)0x80, (byte)0x5c, (byte)0x45, (byte)0x2c, (byte)0x9b,
            (byte)0x76, (byte)0xd5, (byte)0x1b, (byte)0x8f, (byte)0xcc, (byte)0xc3, (byte)0xb8, (byte)0xf5,
    };

    private static final byte[] XOR_CLIENT_KEY = {
            (byte)0x78, (byte)0x06, (byte)0xad, (byte)0x4c,
            (byte)0x33, (byte)0x86, (byte)0x5d, (byte)0x18,
            (byte)0x4c, (byte)0x01, (byte)0x3f, (byte)0x46,
    };

    private static final SecureRandom RNG = new SecureRandom();
    private M115Crypto() {}

    static final class Key {
        final byte[] b = new byte[16];
        Key() { RNG.nextBytes(b); }
        Key(byte[] src) { System.arraycopy(src, 0, b, 0, 16); }
    }

    static Key generateKey() { return new Key(); }

    static String encode(byte[] input, Key key) {
        byte[] buf = new byte[16 + input.length];
        System.arraycopy(key.b, 0, buf, 0, 16);
        System.arraycopy(input, 0, buf, 16, input.length);
        xorTransform(buf, 16, buf.length - 16, deriveKey(key.b, 4));
        reverse(buf, 16, buf.length);
        xorTransform(buf, 16, buf.length - 16, XOR_CLIENT_KEY);
        return android.util.Base64.encodeToString(rsaCrypt(buf), android.util.Base64.DEFAULT)
                .replaceAll("\\s", "");
    }

    static byte[] decode(String input, Key key) throws Exception {
        byte[] data = android.util.Base64.decode(input.trim(), android.util.Base64.DEFAULT);
        data = rsaPublic(data); // public exponent + zero-strip, matches Go rsaDecrypt
        if (data.length < 17) throw new IllegalStateException("m115 decode: too short");
        byte[] out = Arrays.copyOfRange(data, 16, data.length);
        byte[] innerKey = Arrays.copyOfRange(data, 0, 16);
        xorTransform(out, 0, out.length, deriveKey(innerKey, 12));
        reverse(out, 0, out.length);
        xorTransform(out, 0, out.length, deriveKey(key.b, 4));
        return out;
    }

    private static byte[] deriveKey(byte[] seed, int size) {
        byte[] key = new byte[size];
        for (int i = 0; i < size; i++) {
            key[i] = (byte) ((seed[i] + XOR_KEY_SEED[size * i]) & 0xff);
            key[i] ^= XOR_KEY_SEED[size * (size - i - 1)];
        }
        return key;
    }

    /** Faithful port of Go xorTransform: first (dataSize % 4) bytes use index
     *  i%keySize; the rest use (i-mod)%keySize. */
    private static void xorTransform(byte[] data, int start, int len, byte[] key) {
        int mod = len % 4;
        for (int i = 0; i < mod; i++) data[start + i] ^= key[i % key.length];
        for (int i = mod; i < len; i++) data[start + i] ^= key[(i - mod) % key.length];
    }

    private static void reverse(byte[] b, int from, int to) {
        for (int i = from, j = to - 1; i < j; i++, j--) {
            byte t = b[i]; b[i] = b[j]; b[j] = t;
        }
    }

    /** RSA with the embedded public key. Used for both encrypt (PKCS#1 v1.5
     *  padding with non-zero random bytes) and the driver's "decrypt" (plain
     *  modPow with E, then strip up to the first 0 byte). */
    private static byte[] rsaCrypt(byte[] input) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int pos = 0;
        while (pos < input.length) {
            int slice = Math.min(KEY_LEN - 11, input.length - pos);
            byte[] block = new byte[KEY_LEN];
            block[0] = 0; block[1] = 2;
            int padSize = KEY_LEN - slice - 3;
            byte[] pad = new byte[padSize];
            RNG.nextBytes(pad);
            for (int i = 0; i < padSize; i++) block[2 + i] = (byte) ((pad[i] % 0xff) + 0x01);
            block[padSize + 2] = 0;
            System.arraycopy(input, pos, block, padSize + 3, slice);

            byte[] enc = new BigInteger(1, block).modPow(E, N).toByteArray();
            // Left-pad to KEY_LEN (BigInteger drops leading zeros).
            if (enc.length < KEY_LEN) {
                byte[] fixed = new byte[KEY_LEN];
                System.arraycopy(enc, 0, fixed, KEY_LEN - enc.length, enc.length);
                enc = fixed;
            } else if (enc.length > KEY_LEN) {
                enc = Arrays.copyOfRange(enc, enc.length - KEY_LEN, enc.length);
            }
            out.write(enc, 0, enc.length);
            pos += slice;
        }
        return out.toByteArray();
    }

    /** Go driver's rsaDecrypt is modPow(E,N) with zero-stripping, NOT a real
     *  private-key decrypt. Keep a separate method for clarity at call site. */
    static byte[] rsaPublic(byte[] input) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int pos = 0;
        while (pos < input.length) {
            int slice = Math.min(KEY_LEN, input.length - pos);
            byte[] block = Arrays.copyOfRange(input, pos, pos + slice);
            byte[] dec = new BigInteger(1, block).modPow(E, N).toByteArray();
            int start = -1;
            for (int i = 1; i < dec.length; i++) {
                if (dec[i] == 0) { start = i + 1; break; }
            }
            if (start >= 0 && start < dec.length) out.write(dec, start, dec.length - start);
            pos += slice;
        }
        return out.toByteArray();
    }
}
