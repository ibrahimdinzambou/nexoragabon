package com.iptv.saas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.domain.UserSession;
import com.iptv.saas.web.ApiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentNexoraPlaybackServiceTests {
    @Test
    void opensOnlyAStreamResolvedAndVerifiedByTheInternalService() throws Exception {
        ExternalApiProxyService proxy = mock(ExternalApiProxyService.class);
        StreamingService streams = mock(StreamingService.class);
        ContentNexoraPlaybackService service = new ContentNexoraPlaybackService(
                proxy,
                streams,
                new ObjectMapper()
        );
        UserEntity user = new UserEntity();
        UserSession expected = new UserSession();
        byte[] response = """
                {
                  "resolved": true,
                  "kind": "hls",
                  "stream_url": "https://cdn.example/master.m3u8",
                  "request_headers": {"Referer": "https://french-stream.example/title"}
                }
                """.getBytes(StandardCharsets.UTF_8);
        when(proxy.forward(
                eq(ExternalApiProxyService.Target.CONTENT),
                eq("/api/external/content/api/resolve"),
                eq(null),
                eq("POST"),
                eq("application/json"),
                eq("application/json"),
                any(byte[].class)
        )).thenReturn(new ExternalApiProxyService.ProxyResponse(200, "application/json", "no-store", response));
        when(streams.openContentNexora(
                eq(user),
                eq("series"),
                eq("episode-1"),
                eq("https://cdn.example/master.m3u8"),
                eq("hls"),
                any()
        )).thenReturn(expected);

        UserSession actual = service.open(
                user,
                "series",
                "episode-1",
                "https://vidzy.example/embed/1",
                "https://french-stream.example/title"
        );

        assertEquals(expected, actual);
        verify(streams).openContentNexora(
                eq(user),
                eq("series"),
                eq("episode-1"),
                eq("https://cdn.example/master.m3u8"),
                eq("hls"),
                eq(java.util.Map.of("Referer", "https://french-stream.example/title"))
        );
    }

    @Test
    void rejectsAnEmbedFallbackAsANativeStreamSession() throws Exception {
        ExternalApiProxyService proxy = mock(ExternalApiProxyService.class);
        StreamingService streams = mock(StreamingService.class);
        ContentNexoraPlaybackService service = new ContentNexoraPlaybackService(
                proxy,
                streams,
                new ObjectMapper()
        );
        byte[] response = """
                {"resolved": false, "kind": "embed", "stream_url": "https://vidzy.example/embed/1"}
                """.getBytes(StandardCharsets.UTF_8);
        when(proxy.forward(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ExternalApiProxyService.ProxyResponse(200, "application/json", "no-store", response));

        ApiException error = assertThrows(ApiException.class, () -> service.open(
                new UserEntity(),
                "movie",
                "movie-1",
                "https://vidzy.example/embed/1",
                "https://french-stream.example/title"
        ));

        assertEquals("stream_unavailable", error.code());
    }
}
