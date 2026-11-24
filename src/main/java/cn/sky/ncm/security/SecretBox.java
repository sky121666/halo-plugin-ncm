package cn.sky.ncm.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Set;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.PluginsRootGetter;

/**
 * 敏感字段的加密（AES-256-GCM），存储格式为 "enc:v1:" + base64(iv | 密文 | tag)。
 * 凭据本身存在 Halo Secret 里（见 {@link SecretStore}），但 Halo 的 Secret 在数据库中并不加密，
 * 所以写入 Secret 前再用这里的密钥加密一层。
 * <p>
 * 密钥来源，按优先级：
 * <ol>
 *   <li>环境变量 NCM_SECRET_KEY（任意字符串，取 SHA-256 作为密钥）</li>
 *   <li>密钥文件：环境变量 NCM_SECRET_FILE 指定的路径，否则 {Halo 工作目录}/plugin-ncm/secret.key；
 *       不存在时自动生成</li>
 *   <li>以上都不可用时只放在内存里：插件重启后已绑定的账号需要重新绑定（后台设备状态会提示）</li>
 * </ol>
 * 密钥和数据库分开存放，单独泄露数据库备份不会暴露 Cookie。
 */
@Slf4j
@Component
public class SecretBox {

    public static final String PREFIX = "enc:v1:";
    static final int IV_LEN = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;
    private final String source;

    @Autowired
    public SecretBox(PluginsRootGetter pluginsRoot) {
        this(pluginsRoot.get().toAbsolutePath().normalize().getParent(),
            System.getenv("NCM_SECRET_KEY"), System.getenv("NCM_SECRET_FILE"));
    }

    SecretBox(Path workDir, String env, String explicitKeyFile) {
        if (env != null && !env.isBlank()) {
            this.key = new SecretKeySpec(sha256(env), "AES");
            this.source = "env";
            return;
        }
        Path file = explicitKeyFile == null || explicitKeyFile.isBlank()
            ? workDir.resolve("plugin-ncm").resolve("secret.key")
            : Path.of(explicitKeyFile).toAbsolutePath();
        byte[] k = null;
        String src = "file";
        try {
            k = loadOrCreate(file);
        } catch (IOException | RuntimeException e) {
            log.error("[ncm] 无法读写密钥文件 {}，密钥只保存在内存中，插件重启后需要重新绑定账号。"
                + "请设置环境变量 NCM_SECRET_KEY 或 NCM_SECRET_FILE", file, e);
            k = new byte[32];
            RANDOM.nextBytes(k);
            src = "memory";
        }
        this.key = new SecretKeySpec(k, "AES");
        this.source = src;
    }

    /** 测试用。 */
    SecretBox(byte[] key, String source) {
        this.key = new SecretKeySpec(key, "AES");
        this.source = source;
    }

    /** env / file / memory */
    public String source() {
        return source;
    }

    public boolean persistent() {
        return !"memory".equals(source);
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] sealed = c.doFinal(plain.getBytes(UTF_8));
            byte[] out = Arrays.copyOf(iv, IV_LEN + sealed.length);
            System.arraycopy(sealed, 0, out, IV_LEN, sealed.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 解密。没有 "enc:v1:" 前缀的值视为旧版明文，原样返回。
     *
     * @throws SecretException 密钥不对或数据被篡改
     */
    public String decrypt(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return value;
        }
        try {
            byte[] all = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, IV_LEN));
            return new String(c.doFinal(all, IV_LEN, all.length - IV_LEN), UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new SecretException("无法解密（密钥已更换或数据损坏）", e);
        }
    }

    static byte[] loadOrCreate(Path file) throws IOException {
        if (Files.exists(file)) {
            byte[] k = Base64.getDecoder().decode(Files.readString(file).trim());
            if (k.length != 32) {
                throw new IOException("密钥文件内容无效: " + file);
            }
            return k;
        }
        Files.createDirectories(file.getParent());
        byte[] k = new byte[32];
        RANDOM.nextBytes(k);
        String encoded = Base64.getEncoder().encodeToString(k);
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
             var writer = Channels.newWriter(channel, UTF_8)) {
            writer.write(encoded);
        } catch (UnsupportedOperationException ignored) {
            Files.writeString(file, encoded, StandardOpenOption.CREATE_NEW);
        }
        log.info("[ncm] 已生成 Cookie 加密密钥: {}（请和数据库一起备份，丢失后需要重新绑定账号）", file);
        return k;
    }

    public static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static class SecretException extends RuntimeException {
        public SecretException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
