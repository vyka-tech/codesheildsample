package com.codeshield.sample;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import org.apache.commons.collections.CollectionUtils;
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
 * Sample Lambda for exercising CodeShield AI.
 *
 * The vulnerabilities in this project live in the outdated DEPENDENCY VERSIONS (see pom.xml),
 * which is what AWS Inspector detects. The code itself deliberately avoids passing caller input
 * into the vulnerable APIs (no logging of raw input, no StringSubstitutor, no polymorphic Jackson
 * typing, YAML only loaded from a bundled file), so the function is a scan target, not an
 * exploitable endpoint.
 *
 * Example event: {"name": "jane doe"}
 */
public class Handler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Logger LOG = LogManager.getLogger(Handler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Object> CONFIG = loadConfig();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        Object rawName = event == null ? null : event.get("name");
        String name = rawName == null ? "" : rawName.toString();

        // Log only metadata, never the raw caller value.
        LOG.info("Received request, name length={}", name.length());

        String greetingName = Strings.isNullOrEmpty(name) ? "World" : WordUtils.capitalizeFully(name);

        @SuppressWarnings("unchecked")
        List<String> features = (List<String>) CONFIG.getOrDefault("features", List.of());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", CONFIG.getOrDefault("greeting", "Hello") + ", " + greetingName + "!");
        response.put("service", CONFIG.getOrDefault("service", "codesheildsample"));
        response.put("features", CollectionUtils.isEmpty(features) ? List.of() : features);
        response.put("timestamp", Instant.now().toString());

        try {
            LOG.debug("Response JSON size={}", MAPPER.writeValueAsString(response).length());
        } catch (Exception e) {
            LOG.warn("Could not serialise response", e);
        }
        return response;
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
