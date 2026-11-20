package com.codeshield.sample;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.commons.text.WordUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sample Lambda for exercising CodeShield AI, served over HTTPS through a Lambda Function URL.
 *
 * <pre>
 *   GET /                     HTML status page: greeting, running library versions, self-tests
 *   GET /api/hello?name=jane  JSON greeting
 *   GET /api/health           JSON health: status, library versions, self-test results (503 if any fail)
 * </pre>
 *
 * Direct invocations ({"name": "..."}) still return the plain greeting map, so the CI/CD smoke test
 * keeps working.
 *
 * The vulnerabilities live in the outdated DEPENDENCY VERSIONS (see pom.xml), which is what Amazon Inspector
 * detects. The code deliberately never passes caller input into the vulnerable APIs: input is never logged,
 * there is no StringSubstitutor / interpolation, no polymorphic Jackson typing, and YAML is only parsed from
 * bundled or constant data. Caller input is length-limited and HTML-escaped before it is echoed.
 */
public class Handler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Logger LOG = LogManager.getLogger(Handler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Object> CONFIG = loadConfig();
    private static final int MAX_NAME_LENGTH = 100;

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        if (!isHttpEvent(event)) {
            // Direct invoke (CLI / CI smoke test): plain greeting map
            return greeting(stringValue(event == null ? null : event.get("name")));
        }
        try {
            return route(event);
        } catch (Exception e) {
            LOG.error("Unhandled error", e);
            return json(500, Map.of("error", "Internal server error"));
        }
    }

    // ── HTTP routing (Lambda Function URL / API Gateway HTTP API payload v2) ────────────────

    private Map<String, Object> route(Map<String, Object> event) throws Exception {
        String method = httpMethod(event);
        String path   = stringValue(event.get("rawPath"));
        if (path.isEmpty()) {
            path = "/";
        }
        // Log metadata only, never query values
        LOG.info("{} {}", method, path.length() > 100 ? path.substring(0, 100) : path);

        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return json(405, Map.of("error", "Method not allowed", "allowed", "GET"));
        }

        String name = queryParam(event, "name");
        switch (path) {
            case "/":
                return html(200, renderPage(greeting(name), DependencyCheck.runAll()));
            case "/api/hello":
                return json(200, greeting(name));
            case "/api/health": {
                List<DependencyCheck.Result> checks = DependencyCheck.runAll();
                boolean ok = checks.stream().allMatch(DependencyCheck.Result::ok);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", ok ? "UP" : "DEGRADED");
                body.put("service", CONFIG.getOrDefault("service", "codesheildsample"));
                body.put("timestamp", Instant.now().toString());
                body.put("dependencies", checks.stream().map(DependencyCheck.Result::toMap).toList());
                return json(ok ? 200 : 503, body);
            }
            case "/favicon.ico":
                return Map.of("statusCode", 204);
            default:
                return json(404, Map.of("error", "Not found",
                        "routes", List.of("/", "/api/hello?name=<name>", "/api/health")));
        }
    }

    // ── Business logic: uses each library for real so an upgrade that breaks it shows up ────

    private Map<String, Object> greeting(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }
        LOG.info("Greeting request, name length={}", name.length());   // never log the value itself

        String greetingName = Strings.isNullOrEmpty(name) ? "World" : WordUtils.capitalizeFully(name);   // guava, commons-text

        @SuppressWarnings("unchecked")
        List<String> features = (List<String>) CONFIG.getOrDefault("features", List.of());               // snakeyaml config

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", CONFIG.getOrDefault("greeting", "Hello") + ", " + greetingName + "!");
        response.put("service", CONFIG.getOrDefault("service", "codesheildsample"));
        response.put("features", CollectionUtils.isEmpty(features) ? List.of() : features);                // commons-collections
        response.put("timestamp", Instant.now().toString());
        return response;
    }

    // ── HTML page ──────────────────────────────────────────────────────────────────────────

    private String renderPage(Map<String, Object> greeting, List<DependencyCheck.Result> checks) {
        boolean allOk = checks.stream().allMatch(DependencyCheck.Result::ok);
        StringBuilder rows = new StringBuilder();
        for (DependencyCheck.Result r : checks) {
            rows.append("<tr><td><code>").append(esc(r.artifact())).append("</code></td>")
                .append("<td><code>").append(esc(r.version())).append("</code></td>")
                .append("<td>").append(esc(r.test())).append("</td>")
                .append("<td class=\"").append(r.ok() ? "ok" : "fail").append("\">")
                .append(r.ok() ? "&#10004; pass" : "&#10008; " + esc(r.error())).append("</td></tr>\n");
        }
        String message = esc(String.valueOf(greeting.get("message")));
        String service = esc(String.valueOf(greeting.get("service")));

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>%s · status</title>
              <style>
                body { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; margin: 0; background: #f6f7f9; color: #1f2937; }
                main { max-width: 860px; margin: 2rem auto; padding: 0 1rem; }
                .card { background: #fff; border: 1px solid #e5e7eb; border-radius: 10px; padding: 1.25rem 1.5rem; margin-bottom: 1rem; }
                h1 { font-size: 1.4rem; margin: 0 0 .25rem; }
                .badge { display: inline-block; padding: .2rem .6rem; border-radius: 999px; font-weight: 600; font-size: .85rem; }
                .up { background: #dcfce7; color: #166534; } .down { background: #fee2e2; color: #991b1b; }
                .msg { font-size: 1.6rem; font-weight: 600; margin: .5rem 0; }
                table { width: 100%%; border-collapse: collapse; font-size: .9rem; }
                th, td { text-align: left; padding: .45rem .5rem; border-bottom: 1px solid #f0f1f3; }
                th { color: #6b7280; font-weight: 600; }
                .ok { color: #166534; font-weight: 600; } .fail { color: #991b1b; font-weight: 600; }
                form { display: flex; gap: .5rem; align-items: center; flex-wrap: wrap; }
                input[type=text] { padding: .4rem .6rem; border: 1px solid #d1d5db; border-radius: 6px; min-width: 14rem; }
                button { padding: .45rem .9rem; border: 0; border-radius: 6px; background: #2563eb; color: #fff; font-weight: 600; cursor: pointer; }
                small, .muted { color: #6b7280; } a { color: #2563eb; }
              </style>
            </head>
            <body>
            <main>
              <div class="card">
                <h1>%s</h1>
                <span class="badge %s" role="status">%s</span>
                <span class="muted">&nbsp;Served by AWS Lambda (Java 17) at %s</span>
              </div>

              <div class="card">
                <p class="msg">%s</p>
                <form method="get" action="/">
                  <label for="name">Your name</label>
                  <input id="name" name="name" type="text" maxlength="100" placeholder="e.g. jane doe">
                  <button type="submit">Greet me</button>
                </form>
              </div>

              <div class="card">
                <h2 style="font-size:1.05rem;margin-top:0">Libraries running in this deployment</h2>
                <p class="muted" style="margin-top:0">Versions are read from the jars actually loaded by the function. Each library is exercised on every request.</p>
                <table>
                  <thead><tr><th scope="col">Library</th><th scope="col">Version</th><th scope="col">Self-test</th><th scope="col">Result</th></tr></thead>
                  <tbody>
            %s
                  </tbody>
                </table>
              </div>

              <p><small>JSON: <a href="/api/hello?name=CodeShield">/api/hello?name=CodeShield</a> · <a href="/api/health">/api/health</a></small></p>
            </main>
            </body>
            </html>
            """.formatted(service, service,
                          allOk ? "up" : "down", allOk ? "&#9679; UP" : "&#9679; DEGRADED",
                          esc(Instant.now().toString()), message, rows);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────

    private static boolean isHttpEvent(Map<String, Object> event) {
        return event != null && event.get("requestContext") instanceof Map<?, ?> rc && rc.get("http") instanceof Map;
    }

    private static String httpMethod(Map<String, Object> event) {
        Object rc = event.get("requestContext");
        if (rc instanceof Map<?, ?> rcMap && rcMap.get("http") instanceof Map<?, ?> http) {
            return stringValue(http.get("method")).toUpperCase();
        }
        return "GET";
    }

    private static String queryParam(Map<String, Object> event, String key) {
        Object qs = event.get("queryStringParameters");
        return qs instanceof Map<?, ?> q ? stringValue(q.get(key)) : "";
    }

    private static String stringValue(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String esc(String s) {
        return StringEscapeUtils.escapeHtml4(s == null ? "" : s);   // commons-text
    }

    private Map<String, Object> json(int status, Object body) {
        String payload;
        try {
            payload = MAPPER.writeValueAsString(body);                // jackson-databind
        } catch (Exception e) {
            LOG.warn("Could not serialise response", e);
            payload = "{\"error\":\"serialisation failed\"}";
            status = 500;
        }
        return response(status, "application/json; charset=utf-8", payload);
    }

    private static Map<String, Object> html(int status, String body) {
        return response(status, "text/html; charset=utf-8", body);
    }

    private static Map<String, Object> response(int status, String contentType, String body) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("Cache-Control", "no-store");
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("statusCode", status);
        resp.put("headers", headers);
        resp.put("body", body);
        return resp;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadConfig() {
        try (InputStream in = Handler.class.getResourceAsStream("/config.yml")) {
            if (in == null) {
                return Map.of();
            }
            Object loaded = new Yaml().load(in);
            return loaded instanceof Map ? (Map<String, Object>) loaded : Map.of();
        } catch (Exception e) {
            LOG.warn("Could not load config.yml", e);
            return Map.of();
        }
    }
}
