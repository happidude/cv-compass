package com.cvcompass.backend;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.ObjectMapper;

/** Real login/CSRF setup for existing HTTP integration tests; never bypasses filters. */
final class TestUserSession {
    private final String cookies;
    private final String token;

    private TestUserSession(String cookies, String token) {
        this.cookies = cookies;
        this.token = token;
    }

    static TestUserSession login(HttpClient client, int port, JdbcTemplate jdbc, ObjectMapper json)
            throws Exception {
        jdbc.update(
                "INSERT INTO \"CV_USERS\"(\"USERNAME\",\"PASSWORD_HASH\",\"ROLE\") VALUES ('synthetic-http',?,'USER') ON CONFLICT DO NOTHING",
                new BCryptPasswordEncoder(4).encode("synthetic-http-test-only"));
        var cookies = new LinkedHashMap<String, String>();
        String base = "http://localhost:" + port;
        var csrf =
                client.send(
                        HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        collect(csrf, cookies);
        String token = json.readTree(csrf.body()).path("token").asString();
        var login =
                client.send(
                        HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                                .header("Cookie", join(cookies))
                                .header("X-XSRF-TOKEN", token)
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "username=synthetic-http&password=synthetic-http-test-only"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        if (login.statusCode() != 204) throw new IllegalStateException("TEST_LOGIN_FAILED");
        collect(login, cookies);
        var fresh =
                client.send(
                        HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf"))
                                .header("Cookie", join(cookies))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        collect(fresh, cookies);
        return new TestUserSession(
                join(cookies), json.readTree(fresh.body()).path("token").asString());
    }

    HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).header("Cookie", cookies).header("X-XSRF-TOKEN", token);
    }

    private static void collect(HttpResponse<?> response, Map<String, String> cookies) {
        for (String value : response.headers().allValues("Set-Cookie")) {
            String pair = value.split(";", 2)[0];
            int equals = pair.indexOf('=');
            cookies.put(pair.substring(0, equals), pair.substring(equals + 1));
        }
    }

    private static String join(Map<String, String> cookies) {
        return String.join(
                "; ",
                cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
    }
}
