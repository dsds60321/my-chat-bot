package com.mychatbot.security;

import com.mychatbot.knowledge.KnowledgeService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
public class ApiSecurity {
    @Bean
    ReactiveUserDetailsService users(@Value("${pet.api.user:developer}") String username,
            @Value("${pet.api.password:}") String password, @Value("${pet.api.projects:}") String projects,
            @Value("${pet.api.admin.user:}") String adminUser, @Value("${pet.api.admin.password:}") String adminPassword,
            @Value("${pet.api.admin.projects:}") String adminProjects,
            @Value("${pet.api.knowledge.direct-publish-users:}") String directPublishUsers) {
        UserDetails employee = account(username, password, projects, false, canPublish(username, directPublishUsers));
        UserDetails admin = adminUser.equalsIgnoreCase(username) || adminPassword.equals(password)
                ? null : account(adminUser, adminPassword, adminProjects, true, canPublish(adminUser, directPublishUsers));
        return name -> Mono.justOrEmpty(Stream.of(employee, admin).filter(a -> a != null && a.getUsername().equals(name)).findFirst());
    }

    private static boolean canPublish(String username, String allowedUsers) {
        return Arrays.stream(allowedUsers.split(",")).map(String::strip)
                .anyMatch(name -> !name.isEmpty() && !name.equals("*") && name.equals(username));
    }

    private static UserDetails account(String username, String password, String projects, boolean admin, boolean directPublish) {
        if (username.isBlank() || password.isBlank()) return null;
        var granted = projects.isBlank() ? KnowledgeService.SUPPORTED_PROJECTS.stream()
                : Arrays.stream(projects.split(",")).map(String::strip).filter(KnowledgeService.SUPPORTED_PROJECTS::contains);
        List<String> authorities = new ArrayList<>(granted.map(project -> "PROJECT_" + project).toList());
        if (admin && authorities.isEmpty()) return null;
        authorities.add("ROLE_EMPLOYEE");
        if (admin) authorities.add("ROLE_KNOWLEDGE_ADMIN");
        if (directPublish) authorities.add("KNOWLEDGE_DIRECT_PUBLISH");
        return User.withUsername(username)
                .password(PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(password))
                .authorities(authorities.toArray(String[]::new)).build();
    }

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of("http://127.0.0.1:1420", "http://localhost:1420", "http://tauri.localhost", "tauri://localhost"));
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        cors.setExposedHeaders(List.of("Retry-After", "X-Request-Id", "Content-Disposition"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return http.cors(c -> c.configurationSource(source))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .csrf(c -> c.requireCsrfProtectionMatcher(exchange -> {
                    HttpMethod method = exchange.getRequest().getMethod();
                    boolean safe = List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE).contains(method);
                    boolean apiPost = method == HttpMethod.POST && exchange.getRequest().getPath().value().startsWith("/api/");
                    return safe || apiPost ? ServerWebExchangeMatcher.MatchResult.notMatch() : ServerWebExchangeMatcher.MatchResult.match();
                }))
                .authorizeExchange(a -> a.pathMatchers("/api/knowledge/admin", "/api/knowledge/admin/**").hasRole("KNOWLEDGE_ADMIN")
                        .pathMatchers("/api/**").hasRole("EMPLOYEE").anyExchange().denyAll())
                .httpBasic(Customizer.withDefaults())
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint((exchange, error) -> {
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                }).accessDeniedHandler((exchange, error) -> {
                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                    return exchange.getResponse().setComplete();
                }))
                .build();
    }
}
