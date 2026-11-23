package cn.sky.ncm.client;

public class UnsupportedCryptoException extends RuntimeException {
    public UnsupportedCryptoException(String crypto) {
        super("加密方式 " + crypto + " 尚未移植");
    }
}
