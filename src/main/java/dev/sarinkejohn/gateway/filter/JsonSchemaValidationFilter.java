package dev.sarinkejohn.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class JsonSchemaValidationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JsonSchemaValidationFilter.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonSchema schema = null;

    // No checked exceptions from constructor
    public JsonSchemaValidationFilter() { }

    @PostConstruct
    public void initSchema() {
        try {
            ClassPathResource r = new ClassPathResource("schema/fund-transfer-schema.json");
            JsonNode schemaNode = mapper.readTree(r.getInputStream());
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            this.schema = factory.getSchema(schemaNode);
            log.info("Loaded JSON schema for fund-transfer");
        } catch (Exception e) {
            // Log but do not prevent the app from starting
            log.error("Failed to load JSON schema (filter will be disabled): {}", e.getMessage(), e);
            this.schema = null;
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        HttpMethod method = request.getMethod();

        log.debug("JsonSchemaValidationFilter invoked for path={} method={}", path, method);

        // Only validate JSON POSTs for the path prefix (adjust prefix as needed)
        if (method != HttpMethod.POST || path == null || !path.startsWith("/v2/fund-transfer")) {
            log.debug("Skipping validation: not POST or path does not match");
            return chain.filter(exchange);
        }

        if (schema == null) {
            log.warn("Schema not loaded, skipping validation");
            return chain.filter(exchange);
        }

        // Only validate content-type application/json (if present)
        MediaType contentType = request.getHeaders().getContentType();
        if (contentType != null && !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            log.debug("Skipping validation: Content-Type {} not compatible with application/json", contentType);
            return chain.filter(exchange);
        }

        DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();

        return DataBufferUtils.join(request.getBody())
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    String bodyStr = new String(bytes, StandardCharsets.UTF_8);
                    String trimmed = bodyStr == null ? "" : bodyStr.trim();

                    if (trimmed.isEmpty()) {
                        log.debug("Empty request body");
                        return writeBadRequest(exchange.getResponse(), bufferFactory, "Empty request body");
                    }

                    char first = trimmed.charAt(0);
                    if (first != '{' && first != '[') {
                        log.debug("Invalid JSON root char: {}", first);
                        return writeBadRequest(exchange.getResponse(), bufferFactory, "Invalid payload format: expected JSON object or array");
                    }

                    JsonNode root;
                    try {
                        root = mapper.readTree(bodyStr);
                    } catch (Exception e) {
                        log.debug("Invalid JSON payload: {}", e.getMessage());
                        return writeBadRequest(exchange.getResponse(), bufferFactory, "Invalid JSON payload: " + e.getMessage());
                    }

                    List<String> details = new ArrayList<>();

                    if (root.isArray()) {
                        int idx = 0;
                        for (JsonNode el : root) {
                            Set<ValidationMessage> errs = schema.validate(el);
                            if (!errs.isEmpty()) {
                                for (ValidationMessage vm : errs) {
                                    details.add("[" + idx + "] " + vm.getMessage());
                                }
                            }
                            idx++;
                        }
                    } else if (root.isObject()) {
                        Set<ValidationMessage> errs = schema.validate(root);
                        if (!errs.isEmpty()) {
                            details.addAll(errs.stream().map(ValidationMessage::getMessage).collect(Collectors.toList()));
                        }
                    } else {
                        return writeBadRequest(exchange.getResponse(), bufferFactory, "Invalid JSON root: must be object or array");
                    }

                    if (!details.isEmpty()) {
                        log.debug("Schema validation failed: {}", details);
                        return writeBadRequest(exchange.getResponse(), bufferFactory, "Schema validation failed", details);
                    }

                    // Cache body for downstream
                    byte[] newBodyBytes = bodyStr.getBytes(StandardCharsets.UTF_8);
                    Flux<DataBuffer> cachedBodyFlux = Flux.defer(() -> Mono.just(bufferFactory.wrap(newBodyBytes)));

                    ServerHttpRequest decorated = new ServerHttpRequestDecorator(request) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return cachedBodyFlux;
                        }
                    };

                    return chain.filter(exchange.mutate().request(decorated).build());
                })
                .switchIfEmpty(writeBadRequest(exchange.getResponse(), bufferFactory, "Empty request body"))
                .onErrorResume(ex -> {
                    log.error("Unexpected error in schema filter: {}", ex.getMessage(), ex);
                    return writeBadRequest(exchange.getResponse(), exchange.getResponse().bufferFactory(), "Internal validation error: " + ex.getMessage());
                });
    }

    private Mono<Void> writeBadRequest(ServerHttpResponse response, DataBufferFactory bufferFactory, String message) {
        return writeBadRequest(response, bufferFactory, message, null);
    }

    private Mono<Void> writeBadRequest(ServerHttpResponse response, DataBufferFactory bufferFactory, String message, List<String> details) {
        response.setStatusCode(org.springframework.http.HttpStatus.BAD_REQUEST);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        if (details != null && !details.isEmpty()) body.put("details", details);

        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body);
        } catch (Exception e) {
            bytes = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = bufferFactory.wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
