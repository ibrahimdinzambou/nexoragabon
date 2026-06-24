package com.iptv.saas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EpornerContentServiceTests {
    @Test
    void legacyDisabledFlagDoesNotHideAdultsCategoryWhenApiUrlIsConfigured() {
        EpornerContentService service = service(false, mock(HttpClient.class));

        assertTrue(service.isEnabled());
        assertFalse(service.categories("movie").isEmpty());
    }

    @Test
    void loadsVideosEvenWhenLegacyDisabledFlagIsFalse() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        String payload = """
                {
                  "videos": [
                    {
                      "id": "abc123",
                      "title": "Sample Video",
                      "default_thumb": { "src": "https://static.example.test/thumb.jpg" },
                      "length_min": "12:34",
                      "length_sec": 754,
                      "views": 1200,
                      "rate": "95",
                      "added": "2026-06-24",
                      "keywords": "tag one, tag two"
                    }
                  ]
                }
                """;
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        List<Map<String, Object>> items = service(false, client)
                .items("movie", null, EpornerContentService.CATEGORY_ADULTS, null, "latest", 20, 1);

        assertEquals(1, items.size());
        assertEquals("eporner~video~abc123", items.get(0).get("id"));
        assertEquals("Sample Video", items.get(0).get("name"));
    }

    private EpornerContentService service(boolean enabled, HttpClient client) {
        return new EpornerContentService(
                new ObjectMapper(),
                new CatalogImageService(1_048_576, 16),
                enabled,
                "https://www.eporner.com/api/v2",
                5,
                1_048_576,
                60,
                300,
                "latest",
                client
        );
    }
}
