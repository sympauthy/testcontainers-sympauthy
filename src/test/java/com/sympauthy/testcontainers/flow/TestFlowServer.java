package com.sympauthy.testcontainers.flow;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-JVM stub of SympAuthy's HTTP surface, backed by the JDK {@link HttpServer}, so the whole
 * {@link InteractiveFlow} loop — including the GET {@code ?state=} vs POST {@code Authorization:
 * State} transport — can be exercised without Docker. Routes are registered per test; every request
 * is recorded for assertions.
 */
final class TestFlowServer implements AutoCloseable {

    record RecordedRequest(String method, String path, String query, Map<String, String> headers, String body) {

        String stateQueryParam() {
            return queryParam(query, "state");
        }

        static String queryParam(String query, String name) {
            if (query == null) {
                return null;
            }
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                String key = equals < 0 ? pair : pair.substring(0, equals);
                if (key.equals(name)) {
                    String value = equals < 0 ? "" : pair.substring(equals + 1);
                    return URLDecoder.decode(value, StandardCharsets.UTF_8);
                }
            }
            return null;
        }
    }

    record Response(int status, String body, Map<String, String> headers) {

        static Response json(int status, String body) {
            return new Response(status, body, Map.of("Content-Type", "application/json"));
        }

        static Response seeOther(String location) {
            return new Response(303, null, Map.of("Location", location));
        }
    }

    @FunctionalInterface
    interface Responder {
        Response respond(RecordedRequest request);
    }

    private final HttpServer server;
    private final String baseUrl;
    private final Map<String, Responder> routes = new HashMap<>();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    TestFlowServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    String baseUrl() {
        return baseUrl;
    }

    TestFlowServer route(String method, String path, Responder responder) {
        routes.put(method + " " + path, responder);
        return this;
    }

    RecordedRequest firstRequest(String method, String path) {
        return requests.stream()
                .filter(request -> request.method().equals(method) && request.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No recorded request for " + method + " " + path));
    }

    private void handle(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((key, values) -> {
            if (!values.isEmpty()) {
                headers.put(key, values.get(0));
            }
        });
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        RecordedRequest request = new RecordedRequest(
                exchange.getRequestMethod(), uri.getPath(), uri.getQuery(), headers, body);
        requests.add(request);

        Responder responder = routes.get(request.method() + " " + request.path());
        if (responder == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        Response response = responder.respond(request);
        response.headers().forEach((key, value) -> exchange.getResponseHeaders().add(key, value));
        if (response.body() == null) {
            exchange.sendResponseHeaders(response.status(), -1);
        } else {
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
