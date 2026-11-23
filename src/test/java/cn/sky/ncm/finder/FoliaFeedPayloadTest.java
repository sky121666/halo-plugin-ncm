package cn.sky.ncm.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class FoliaFeedPayloadTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void homeKeepsCatalogueAndPageCursorWithoutForwardingUpstreamFields() throws Exception {
        var upstream = json.readTree("""
            {"cookie":"fixture","data":{"cursor":{"page":2},"hasMore":true,"blocks":[
              {"blockCode":"PICKS","action":"internal-action","creatives":[{"resources":[
                {"resourceType":"list","resourceId":"987654321012345678",
                 "uiElement":{"mainTitle":{"title":"推荐歌单"},"image":{"imageUrl":"http://p1.music.126.net/cover.jpg"}},
                 "resourceExtInfo":{"playCount":42,"token":"fixture"}},
                {"resourceType":"song","resourceExtInfo":{"songData":{"id":123,"name":"试听曲目"}}},
                {"resourceType":"video","resourceId":"456"}
              ]}]}
            ]}}
            """);

        var payload = json.valueToTree(FoliaFeedPayload.home(upstream));
        var page = payload.path("data");
        var resources = page.path("blocks").get(0).path("creatives").get(0).path("resources");
        assertEquals("{\"page\":2}", page.path("cursor").asText());
        assertEquals(2, resources.size());
        assertEquals("987654321012345678", resources.get(0).path("resourceId").asText());
        assertEquals("https://p1.music.126.net/cover.jpg", resources.get(0).at("/uiElement/image/imageUrl").asText());
        assertEquals("123", resources.get(1).at("/resourceExtInfo/songData/id").asText());
        assertFalse(payload.toString().contains("fixture"));
        assertFalse(payload.toString().contains("internal-action"));
    }
}
