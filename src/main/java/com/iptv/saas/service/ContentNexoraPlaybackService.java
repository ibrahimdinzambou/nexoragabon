package com.iptv.saas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.domain.UserSession;
import com.iptv.saas.web.ApiException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ContentNexoraPlaybackService {
    private final ExternalApiProxyService proxy;
    private final StreamingService streams;
    private final ObjectMapper mapper;

    public ContentNexoraPlaybackService(
            ExternalApiProxyService proxy,
            StreamingService streams,
            ObjectMapper mapper
    ) {
        this.proxy = proxy;
        this.streams = streams;
        this.mapper = mapper;
    }

    public UserSession open(
            UserEntity user,
            String type,
            String itemId,
            String playerUrl,
            String referer
    ) {
        requireHttpUrl(playerUrl, "URL de lecteur Content-Nexora invalide");
        requireHttpUrl(referer, "URL de provenance Content-Nexora invalide");
        try {
            byte[] body = mapper.writeValueAsBytes(Map.of(
                    "player_url", playerUrl,
                    "referer", referer
            ));
            ExternalApiProxyService.ProxyResponse response = proxy.forward(
                    ExternalApiProxyService.Target.CONTENT,
                    "/api/external/content/api/resolve",
                    null,
                    "POST",
                    "application/json",
                    "application/json",
                    body
            );
            if (response.status() < 200 || response.status() >= 300) {
                throw ApiException.providerUnavailable("Le resolveur Content-Nexora a refuse la source");
            }
            JsonNode resolved = mapper.readTree(response.body());
            if (!resolved.path("resolved").asBoolean(false)) {
                throw ApiException.streamUnavailable("Cette source ne fournit pas de flux video direct");
            }
            String streamUrl = resolved.path("stream_url").asText("").strip();
            String kind = resolved.path("kind").asText("video");
            requireHttpUrl(streamUrl, "Le resolveur Content-Nexora a renvoye une URL invalide");
            Map<String, String> headers = new LinkedHashMap<>();
            JsonNode requestHeaders = resolved.path("request_headers");
            if (requestHeaders.isObject()) {
                requestHeaders.fields().forEachRemaining(entry -> {
                    if (entry.getValue().isTextual()) {
                        headers.put(entry.getKey(), entry.getValue().asText());
                    }
                });
            }
            return streams.openContentNexora(user, type, itemId, streamUrl, kind, headers);
        } catch (ApiException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.providerUnavailable("Resolution Content-Nexora interrompue");
        } catch (Exception exception) {
            throw ApiException.providerUnavailable("Resolution Content-Nexora impossible");
        }
    }

    private void requireHttpUrl(String value, String message) {
        try {
            URI uri = URI.create(value == null ? "" : value.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw ApiException.validation(message);
        }
    }
}
