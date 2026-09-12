package com.pdflearning.backend.user;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UserServerTest {
    @Test
    void corsPreflightAllowsFrontendMethodsAndHeaders() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var jdbcUrl = "jdbc:h2:mem:user_server_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        UserTestDatabase.initialize(jdbcUrl);

        try (var server = UserServer.from(Map.of(
                "MYSQL_JDBC_URL", jdbcUrl,
                "MYSQL_USER", "sa",
                "MYSQL_PASSWORD", "test",
                "USER_SERVICE_PORT", Integer.toString(port),
                "USER_ALLOWED_ORIGIN", "http://localhost:5173"))) {
            server.start();
            var request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/auth/register"))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", "http://localhost:5173")
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "authorization,content-type")
                    .build();

            var response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.discarding());

            assertEquals(204, response.statusCode());
            assertEquals(
                    "GET, POST, PATCH, OPTIONS",
                    response.headers().firstValue("Access-Control-Allow-Methods").orElseThrow());
            assertEquals(
                    "Authorization, Content-Type",
                    response.headers().firstValue("Access-Control-Allow-Headers").orElseThrow());
        }
    }
}
