package com.pdflearning.backend.dataport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class JsonHttpClient {
    private final URI baseUri;
    private final String authHeader;
    private final String authValue;
    private final Duration timeout;
    private final HttpClient client;
    private final ObjectMapper mapper;

    JsonHttpClient(
            String baseUrl,
            String authHeader,
            String authValue,
            Duration timeout,
            HttpClient client,
            ObjectMapper mapper) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("数据库 HTTP 地址不能为空");
        }
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.authHeader = authHeader;
        this.authValue = authValue;
        this.timeout = timeout;
        this.client = client;
        this.mapper = mapper;
    }

    JsonNode request(String method, String path, JsonNode body) {
        try {
            var builder = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (authValue != null && !authValue.isBlank()) {
                builder.header(authHeader, authValue);
            }
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new DataPortException(
                        "数据库请求失败: " + path + "，HTTP " + response.statusCode() + "，" + response.body());
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DataPortException("数据库请求被中断: " + path, exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new DataPortException("数据库请求失败: " + path, exception);
        }
    }
}
