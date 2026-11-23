package cn.sky.ncm.api.more;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HttpsAudioTest {

    @Test
    void upgradesNeteaseCdnOnly() {
        assertEquals("https://m701.music.126.net/a/b.mp3", ToolApis.httpsAudio("http://m701.music.126.net/a/b.mp3"));
        assertEquals("https://m10.music.163.com/x.flac", ToolApis.httpsAudio("http://m10.music.163.com/x.flac"));
        assertEquals("http://example.com/a.mp3", ToolApis.httpsAudio("http://example.com/a.mp3"));
        assertEquals("http://evilmusic.126.net/a.mp3", ToolApis.httpsAudio("http://evilmusic.126.net/a.mp3"));
        assertEquals("https://m701.music.126.net/a.mp3", ToolApis.httpsAudio("https://m701.music.126.net/a.mp3"));
        assertEquals(null, ToolApis.httpsAudio(null));
    }
}
