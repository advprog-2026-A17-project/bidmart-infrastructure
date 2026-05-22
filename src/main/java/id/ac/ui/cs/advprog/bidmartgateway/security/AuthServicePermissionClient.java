package id.ac.ui.cs.advprog.bidmartgateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class AuthServicePermissionClient implements AuthPermissionClient {

    private final WebClient webClient;

    public AuthServicePermissionClient(
            WebClient.Builder webClientBuilder,
            @Value("${GATEWAY_AUTH_SERVICE_URL:${AUTH_SERVICE_URL:http://localhost:8080}}") String authServiceUrl
    ) {
        this.webClient = webClientBuilder.baseUrl(authServiceUrl).build();
    }

    @Override
    public Mono<Boolean> hasPermission(String email, String permission) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/auth/permissions/check")
                        .queryParam("email", email)
                        .queryParam("permission", permission)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> Boolean.TRUE.equals(response.get("allowed")))
                .onErrorReturn(false);
    }
}
