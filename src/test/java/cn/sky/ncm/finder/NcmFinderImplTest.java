package cn.sky.ncm.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.sky.ncm.account.AccountService;
import cn.sky.ncm.setting.FinderSetting;
import cn.sky.ncm.setting.SettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class NcmFinderImplTest {

    final FinderSource source = mock(FinderSource.class);
    final SettingService settings = mock(SettingService.class);
    final AccountService accounts = mock(AccountService.class);
    final NcmFinderImpl finder = new NcmFinderImpl(source, settings, accounts);

    @Test
    void siteDataIsNotFetchedWhenSwitchIsOff() {
        when(settings.finder()).thenReturn(Mono.just(FinderSetting.CLOSED));
        StepVerifier.create(finder.siteRecentSongs(10)).verifyComplete();
        StepVerifier.create(finder.siteDailyRecommend()).verifyComplete();
        StepVerifier.create(finder.siteWeekRank()).verifyComplete();
        StepVerifier.create(finder.siteProfile()).verifyComplete();
        verify(source, never()).call(anyString(), anyMap());
        verify(accounts, never()).siteAccount();
    }

    @Test
    void settingErrorFailsClosed() {
        when(settings.finder()).thenReturn(Mono.error(new IllegalStateException("boom")));
        StepVerifier.create(finder.sitePlaylists()).verifyComplete();
        verify(source, never()).call(anyString(), anyMap());
    }

    @Test
    void invalidIdsNeverReachNetease() {
        StepVerifier.create(finder.song("abc")).verifyComplete();
        StepVerifier.create(finder.playlist("1 or 1=1")).verifyComplete();
        StepVerifier.create(finder.songs("x,y")).verifyComplete();
        assertEquals("", finder.playUrl("../etc"));
        verify(source, never()).call(anyString(), anyMap());
    }

    @Test
    void songsKeepRequestedOrder() throws Exception {
        var body = new ObjectMapper().readTree("{\"songs\":[{\"id\":2,\"name\":\"b\"},{\"id\":1,\"name\":\"a\"}]}");
        when(source.call(eq("song_detail"), eq(Map.of("ids", "1,2"))))
            .thenReturn(Mono.just(body));
        StepVerifier.create(finder.songs(" 1, 2 ,1"))
            .assertNext(s -> assertEquals("1", s.getId()))
            .assertNext(s -> assertEquals("2", s.getId()))
            .verifyComplete();
    }

    @Test
    void foliaArtistReadsTheRequestedPageOfWorks() throws Exception {
        var mapper = new ObjectMapper();
        var page = Map.of("id", "1", "offset", "50", "limit", "2");
        var songs = mapper.readTree("""
            {"songs":[{"id":51,"name":"Song"}],"total":90,"more":true}
            """);
        var albums = mapper.readTree("""
            {"hotAlbums":[{"id":7,"name":"Album","size":8}],"more":false}
            """);
        when(source.call("artist_songs", page)).thenReturn(Mono.just(songs));
        when(source.call("artist_album", page)).thenReturn(Mono.just(albums));

        StepVerifier.create(finder.foliaArtist("1", "songs", 50, 2))
            .assertNext(result -> {
                assertEquals(51, result.get("nextOffset"));
                assertEquals(90, result.get("total"));
                assertEquals(true, result.get("hasMore"));
            }).verifyComplete();
        StepVerifier.create(finder.foliaArtist("1", "albums", 50, 2))
            .assertNext(result -> {
                assertEquals(51, result.get("nextOffset"));
                assertEquals(false, result.get("hasMore"));
            }).verifyComplete();
    }
}
