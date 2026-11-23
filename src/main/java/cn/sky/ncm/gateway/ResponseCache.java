package cn.sky.ncm.gateway;

import cn.sky.ncm.client.NcmResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 公开接口的简单内存缓存。 */
@Component
public class ResponseCache {

    private static final int MAX_ENTRIES = 5000;

    private record Entry(NcmResponse value, long expireAt) {
    }

    private final Map<String, Entry> map = new ConcurrentHashMap<>();

    public NcmResponse get(String key) {
        Entry e = map.get(key);
        if (e == null) {
            return null;
        }
        if (e.expireAt() < System.currentTimeMillis()) {
            map.remove(key);
            return null;
        }
        return e.value();
    }

    public void put(String key, NcmResponse value, int ttlSeconds) {
        if (ttlSeconds <= 0 || value.status() != 200) {
            return;
        }
        if (map.size() >= MAX_ENTRIES) {
            map.clear();
        }
        map.put(key, new Entry(value, System.currentTimeMillis() + ttlSeconds * 1000L));
    }
}
