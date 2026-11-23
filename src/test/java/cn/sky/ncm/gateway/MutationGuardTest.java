package cn.sky.ncm.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class MutationGuardTest {

    @Test
    void readTiersAllowAnyMethod() {
        assertNull(NcmGateway.mutationGuard(HttpMethod.GET, null, "public"));
        assertNull(NcmGateway.mutationGuard(HttpMethod.GET, null, "personal"));
        assertNull(NcmGateway.mutationGuard(HttpMethod.POST, null, "personal"));
    }

    @Test
    void mutatingTiersNeedPostAndHeader() {
        for (String tier : new String[] {"write", "auth", "admin"}) {
            assertEquals(405, NcmGateway.mutationGuard(HttpMethod.GET, "1", tier).status(), tier);
            assertEquals(403, NcmGateway.mutationGuard(HttpMethod.POST, null, tier).status(), tier);
            assertEquals(403, NcmGateway.mutationGuard(HttpMethod.POST, "true", tier).status(), tier);
            assertNull(NcmGateway.mutationGuard(HttpMethod.POST, " 1 ", tier), tier);
        }
        assertEquals(405, NcmGateway.mutationGuard(HttpMethod.PUT, "1", "write").status());
    }
}
