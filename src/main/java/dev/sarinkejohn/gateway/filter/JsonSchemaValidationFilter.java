package dev.sarinkejohn.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.web.server.ServerWebExchange;
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

    public JsonSchemaValidationFilter() { }

    @PostConstruct
    public void initSchema() {
        try {
            ClassPathResource r = new ClassPathResource("schema/gateway-headers-schema.json");
            JsonNode schemaNode = mapper.readTree(r.getInputStream());
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            this.schema = factory.getSchema(schemaNode.get("definitions").get("headers"));
            log.info("Loaded JSON schema for headers validation");
        } catch (Exception e) {
            log.error("Failed to load JSON schema (filter will be disabled): {}", e.getMessage(), e);
            this.schema = null;
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (schema == null) {
            log.warn("Schema not loaded, skipping header validation");
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        Map<String, String> headers = new HashMap<>();

        // normalize headers into case-sensitive keys as per schema
        headers.put("Authorization", request.getHeaders().getFirst("Authorization"));
        headers.put("Content-Type", request.getHeaders().getFirst("Content-Type"));
        headers.put("Channel", request.getHeaders().getFirst("Channel"));
        headers.put("AppId", request.getHeaders().getFirst("AppId"));
        headers.put("AppVersion", request.getHeaders().getFirst("AppVersion"));
        headers.put("RequestId", request.getHeaders().getFirst("RequestId"));

        JsonNode headerNode = mapper.valueToTree(headers);

        Set<ValidationMessage> errors = schema.validate(headerNode);

        if (!errors.isEmpty()) {
            List<String> details = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.toList());

            log.debug("Header schema validation failed: {}", details);

            return writeError(exchange.getResponse(),
                    "ERR_1000001",
                    "Missing mandatory parameters",
                    "One or few mandatory parameters are missing in the request");
        }

        return chain.filter(exchange);
    }

    private Mono<Void> writeError(ServerHttpResponse response,
                                  String errorCode,
                                  String errorMessage,
                                  String errorDescription) {
        response.setStatusCode(HttpStatus.BAD_REQUEST);
        response.getHeaders().set("Content-Type", "application/json");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", errorCode);
        body.put("errorMessage", errorMessage);
        body.put("errorDescription", errorDescription);

        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body);
        } catch (Exception e) {
            bytes = ("{\"errorCode\":\"" + errorCode +
                    "\",\"errorMessage\":\"" + errorMessage +
                    "\",\"errorDescription\":\"" + errorDescription + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }

        DataBufferFactory bufferFactory = response.bufferFactory();
        DataBuffer buffer = bufferFactory.wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
