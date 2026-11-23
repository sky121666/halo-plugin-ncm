package cn.sky.ncm.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 对应上游 util/ncbl.js 的 encryptNCBL：PC 客户端日志加密容器（scrobble_v1 听歌上报用）。
 * <pre>
 * header(70) = "NCBL" | ver u32 | headerLen u16 | uuid(16) | keyB(32) | baseSeq u32 | lastSeq u32 | trailingLen u32
 * metaBlock  = 0x4343 u16 | len u16 | ChaCha20(keyB, meta)
 * frames     = [ len u16 | seq u32 | ChaCha20(keyA, compressed[...0x8000]) ]...
 * keyB = RSA(keyA)（无填充，32 字节模数）
 * </pre>
 * 压缩：上游在 Node 22.15+ 用 zstd，旧版本用 gzip；这里用 gzip。
 */
public final class Ncbl {

    static final byte[] MAGIC = "NCBL".getBytes(UTF_8);
    static final int VERSION = 3;
    static final int HEADER_FIXED_LEN = 70;
    static final int META_BLOCK_TYPE = 0x4343;
    static final int MAX_FRAME = 0x8000;
    static final BigInteger RSA_N =
        new BigInteger("fd90bd466ff9bc8a3fec2fbcf263b90d5c564879fa5d7aab89b31c1d5cb4139d", 16);
    static final BigInteger RSA_E = BigInteger.valueOf(65537);

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ncbl() {
    }

    public static byte[] encrypt(String meta, String body) {
        byte[] keyA = new byte[32];
        RANDOM.nextBytes(keyA);
        byte[] uuid = new byte[16];
        RANDOM.nextBytes(uuid);
        uuid[6] = (byte) ((uuid[6] & 0x0f) | 0x40);
        uuid[8] = (byte) ((uuid[8] & 0x3f) | 0x80);
        int baseSeq = RANDOM.nextInt(0x10000);
        return encrypt(meta.getBytes(UTF_8), gzip(body.getBytes(UTF_8)), keyA, uuid, baseSeq);
    }

    static byte[] encrypt(byte[] meta, byte[] compressed, byte[] keyA, byte[] uuid, int baseSeq) {
        keyA = keyA.clone();
        if ((keyA[0] & 0xff) >= 0xa3) {
            keyA[0] = (byte) 0xa2;
        }
        byte[] keyB = rsaWrap(keyA);
        byte[] nonce = Arrays.copyOfRange(uuid, 0, 12);
        int counter = (int) ((le32(uuid, 12) & 0xffffffffL) >>> 2);

        byte[] metaCipher = chacha20(keyB, counter, nonce, meta);
        ByteBuffer metaBlock = le(4 + metaCipher.length);
        metaBlock.putShort((short) META_BLOCK_TYPE).putShort((short) metaCipher.length).put(metaCipher);

        List<byte[]> frames = new ArrayList<>();
        int seq = baseSeq;
        int off = 0;
        do {
            byte[] slice = Arrays.copyOfRange(compressed, off, Math.min(off + MAX_FRAME, compressed.length));
            byte[] cipher = chacha20(keyA, counter, nonce, slice);
            ByteBuffer f = le(6 + cipher.length);
            f.putShort((short) cipher.length).putInt(seq).put(cipher);
            frames.add(f.array());
            seq++;
            off += MAX_FRAME;
        } while (off < compressed.length);
        int trailingLen = frames.stream().mapToInt(a -> a.length).sum();
        int frameCount = seq - baseSeq;

        ByteBuffer header = le(HEADER_FIXED_LEN);
        header.put(MAGIC).putInt(VERSION).putShort((short) (HEADER_FIXED_LEN + metaBlock.capacity()))
            .put(uuid).put(keyB).putInt(baseSeq).putInt(baseSeq + frameCount - 1).putInt(trailingLen);

        ByteBuffer out = ByteBuffer.allocate(HEADER_FIXED_LEN + metaBlock.capacity() + trailingLen);
        out.put(header.array()).put(metaBlock.array());
        frames.forEach(out::put);
        return out.array();
    }

    /** 无填充 RSA，输出 32 字节大端。 */
    static byte[] rsaWrap(byte[] keyA) {
        byte[] r = new BigInteger(1, keyA).modPow(RSA_E, RSA_N).toByteArray();
        byte[] out = new byte[32];
        int n = Math.min(32, r.length);
        System.arraycopy(r, r.length - n, out, 32 - n, n);
        return out;
    }

    /** 原始 ChaCha20（RFC 7539，96 位 nonce，32 位块计数器）。 */
    static byte[] chacha20(byte[] key, int counter, byte[] nonce, byte[] data) {
        if (data.length == 0) {
            return data;
        }
        try {
            Cipher c = Cipher.getInstance("ChaCha20");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new ChaCha20ParameterSpec(nonce, counter));
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] gzip(byte[] data) {
        var bos = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }

    static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    static int le32(byte[] b, int i) {
        return ByteBuffer.wrap(b, i, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
