package com.iptv.saas.web;

import com.iptv.saas.service.ExternalApiProxyService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
public class ExternalApiProxyController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExternalApiProxyController.class);
    private static final byte[] BAD_GATEWAY_BODY =
            "{\"error\":\"Service externe temporairement indisponible\",\"code\":\"upstream_failure\"}"
                    .getBytes(StandardCharsets.UTF_8);

    private final ExternalApiProxyService proxy;

    public ExternalApiProxyController(ExternalApiProxyService proxy) {
        this.proxy = proxy;
    }

    @RequestMapping(
            value = "/api/external/content/**",
            method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST}
    )
    public ResponseEntity<byte[]> content(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        return forward(ExternalApiProxyService.Target.CONTENT, request, body);
    }

    @RequestMapping(
            value = "/api/external/anime/**",
            method = {RequestMethod.GET, RequestMethod.HEAD}
    )
    public ResponseEntity<byte[]> anime(HttpServletRequest request) {
        return forward(ExternalApiProxyService.Target.ANIME, request, null);
    }

    private ResponseEntity<byte[]> forward(
            ExternalApiProxyService.Target target,
            HttpServletRequest request,
            byte[] body
    ) {
        try {
            ExternalApiProxyService.ProxyResponse response = proxy.forward(
                    target,
                    request.getRequestURI(),
                    request.getQueryString(),
                    request.getMethod(),
                    request.getHeader(HttpHeaders.ACCEPT),
                    request.getContentType(),
                    body
            );
            return ResponseEntity.status(response.status())
                    .header(HttpHeaders.CONTENT_TYPE, response.contentType())
                    .header(HttpHeaders.CACHE_CONTROL, response.cacheControl())
                    .header("X-Nexora-Upstream", target.name().toLowerCase())
                    .body(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return badGateway(target, exception);
        } catch (Exception exception) {
            return badGateway(target, exception);
        }
    }

    private ResponseEntity<byte[]> badGateway(ExternalApiProxyService.Target target, Exception exception) {
        LOGGER.warn("Relais interne {} indisponible: {}", target, exception.getMessage());
        return ResponseEntity.status(502)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Nexora-Upstream", target.name().toLowerCase())
                .body(BAD_GATEWAY_BODY);
    }
}
