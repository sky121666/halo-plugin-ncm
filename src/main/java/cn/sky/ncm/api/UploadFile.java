package cn.sky.ncm.api;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 上传的文件（对应上游 express-fileupload 的 query.songFile / query.imgFile）。 */
public record UploadFile(String name, String mimetype, byte[] data) {

    public long size() {
        return data.length;
    }

    public String md5() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 对应 fileHelper.getFileExtension。 */
    public String extension() {
        if (name == null || name.isEmpty()) {
            return "mp3";
        }
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i + 1).toLowerCase() : "mp3";
    }

    /** 对应 fileHelper.sanitizeFilename。 */
    public static String sanitize(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "unknown";
        }
        return filename.replaceFirst("\\.[^.]+$", "").replaceAll("\\s", "").replace('.', '_');
    }
}
