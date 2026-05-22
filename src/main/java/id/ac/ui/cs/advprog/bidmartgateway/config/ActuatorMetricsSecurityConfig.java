package id.ac.ui.cs.advprog.bidmartgateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;

@Configuration
@Profile("!test")
@EnableWebFluxSecurity
public class ActuatorMetricsSecurityConfig {

    @Bean
    @Order(1)
    SecurityWebFilterChain prometheusSecurity(
            ServerHttpSecurity http,
            @Value("${bidmart.metrics.basic-user:}") String username,
            @Value("${bidmart.metrics.basic-password:}") String password) {
        http.securityMatcher(new PathPatternParserServerWebExchangeMatcher("/actuator/prometheus"));
        if (username.isBlank() || password.isBlank()) {
            http.authorizeExchange(exchanges -> exchanges.anyExchange().permitAll());
        } else {
            http
                    .authorizeExchange(exchanges -> exchanges.anyExchange().authenticated())
                    .httpBasic(Customizer.withDefaults());
        }
        http.csrf(ServerHttpSecurity.CsrfSpec::disable);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityWebFilterChain permitAllSecurity(ServerHttpSecurity http) {
        http
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .csrf(ServerHttpSecurity.CsrfSpec::disable);
        return http.build();
    }

    @Bean
    @ConditionalOnExpression("!'${bidmart.metrics.basic-user:}'.isBlank() && !'${bidmart.metrics.basic-password:}'.isBlank()")
    ReactiveUserDetailsService metricsUserDetailsService(
            @Value("${bidmart.metrics.basic-user}") String username,
            @Value("${bidmart.metrics.basic-password}") String password) {
        UserDetails metricsUser = User.builder()
                .username(username)
                .password("{noop}" + password)
                .roles("METRICS")
                .build();
        return new MapReactiveUserDetailsService(metricsUser);
    }
}
