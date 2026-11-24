package cn.sky.ncm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class NcmEgressTest {
    @Test
    void acceptsOnlyKnownHttpsUploadOrigins() {
        assertEquals("https://nosup-hz1.127.net",
            NcmEgress.uploadOrigin("https://nosup-hz1.127.net:443/").toString());
        assertThrows(ResponseStatusException.class, () -> NcmEgress.uploadOrigin("https://example.test"));
        assertThrows(ResponseStatusException.class, () -> NcmEgress.uploadOrigin("http://nosup-hz1.127.net"));
        assertNull(NcmHttp.parse("https://proxy.example.test:8080"));
    }

    @Test
    void privateProxyAddressesNeedDeploymentTrust() throws Exception {
        var privateAddress = InetAddress.getByAddress(new byte[]{10, 0, 0, 2});
        assertFalse(NcmEgress.allowed(privateAddress, false));
        assertTrue(NcmEgress.allowed(privateAddress, true));
        var linkLocal = InetAddress.getByAddress(new byte[]{(byte) 169, (byte) 254, 1, 1});
        assertFalse(NcmEgress.allowed(linkLocal, true));
    }
}
