package id.ac.ui.cs.advprog.bidmartgateway.security;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface AuthPermissionClient {
    Mono<Boolean> hasPermission(String email, String permission);
}
