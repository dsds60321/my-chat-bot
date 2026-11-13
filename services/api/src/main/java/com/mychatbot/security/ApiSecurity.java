package com.mychatbot.security;

import com.mychatbot.knowledge.ProjectCatalog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import java.util.Set;
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
    private final ProjectCatalog catalog;

    public ApiSecurity(ProjectCatalog catalog) { this.catalog = catalog; }

    @Bean
    ReactiveUserDetailsService configuredUsers(Environment environment, @Value("${pet.api.user:developer}") String username,
            @Value("${pet.api.password:}") String password, @Value("${pet.api.projects:}") String projects,
            @Value("${pet.api.admin.user:}") String adminUser, @Value("${pet.api.admin.password:}") String adminPassword,
            @Value("${pet.api.admin.projects:}") String adminProjects,
            @Value("${pet.api.knowledge.direct-publish-users:}") String directPublishUsers) {
        var accounts = Binder.get(environment).bind("pet.api.accounts", Bindable.listOf(Account.class));
        return accounts.isBound() || environment.containsProperty("pet.api.accounts") ? users(accounts.orElse(List.of()))
                : users(username, password, projects, adminUser, adminPassword, adminProjects, directPublishUsers);
    }

    public record Account(String username, String password, String grade, List<String> projects) {
        @Override public String toString() { return "Account[configuration omitted]"; }
    }

    ReactiveUserDetailsService users(List<Account> accounts) {
        record ConfiguredAccount(UserDetails user, String projects, boolean admin) {}
        var configured = new HashMap<String, ConfiguredAccount>();
        var names = new java.util.HashSet<String>();
        for (Account entry : accounts) {
            String name = entry.username();
            if (name == null || !name.matches("[^\\p{Cntrl}:\\s]{1,100}") || !names.add(name.toLowerCase(Locale.ROOT))
                    || entry.password() == null || entry.password().isBlank()
                    || entry.password().contains("${")
                    || !Set.of("admin", "member").contains(entry.grade() == null ? "" : entry.grade())) {
                throw new IllegalArgumentException("pet.api.accounts: 계정명, 비밀번호, grade 또는 계정명 중복을 확인해 주세요.");
            }
            var projects = entry.projects() == null ? List.<String>of() : entry.projects();
            if (projects.stream().anyMatch(project -> !ProjectCatalog.validId(project))) {
                throw new IllegalArgumentException("pet.api.accounts: projects의 프로젝트 ID를 확인해 주세요.");
            }
            boolean admin = entry.grade().equals("admin");
            configured.put(name, new ConfiguredAccount(account(name, entry.password(), admin, admin),
                    String.join(",", projects), admin));
        }
        return name -> Mono.fromCallable(() -> {
            var entry = configured.get(name);
            return entry == null ? null : withProjects(entry.user(), entry.projects(), entry.admin(), true);
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    ReactiveUserDetailsService users(String username, String password, String projects,
            String adminUser, String adminPassword, String adminProjects, String directPublishUsers) {
        UserDetails employee = account(username, password, false, canPublish(username, directPublishUsers));
        UserDetails admin = adminUser.equalsIgnoreCase(username) || adminPassword.equals(password)
                ? null : account(adminUser, adminPassword, true, canPublish(adminUser, directPublishUsers));
        return name -> Mono.fromCallable(() -> {
            if (employee != null && employee.getUsername().equals(name)) return withProjects(employee, projects, false, false);
            if (admin != null && admin.getUsername().equals(name)) return withProjects(admin, adminProjects, true, false);
            return null;
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private UserDetails withProjects(UserDetails user, String configured, boolean admin, boolean unified) {
        Set<String> available = catalog.projects();
        var granted = configured.isBlank() ? available.stream()
                : Arrays.stream(configured.split(",")).map(String::strip).filter(available::contains);
        var projectAuthorities = granted.map(project -> "PROJECT_" + project).toList();
        if (admin && !unified && projectAuthorities.isEmpty()) return null;
        var authorities = new ArrayList<>(projectAuthorities);
        if (admin && unified) {
            if (configured.isBlank()) authorities.add("CREATE_PROJECT_ALL");
            else Arrays.stream(configured.split(",")).forEach(project -> authorities.add("CREATE_PROJECT_" + project));
        }
        user.getAuthorities().forEach(authority -> authorities.add(authority.getAuthority()));
        return User.withUserDetails(user).authorities(authorities.toArray(String[]::new)).build();
    }

    private static boolean canPublish(String username, String allowedUsers) {
        return Arrays.stream(allowedUsers.split(",")).map(String::strip)
                .anyMatch(name -> !name.isEmpty() && !name.equals("*") && name.equals(username));
    }

    private static UserDetails account(String username, String password, boolean admin, boolean directPublish) {
        if (username.isBlank() || password.isBlank()) return null;
        List<String> authorities = new ArrayList<>();
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
