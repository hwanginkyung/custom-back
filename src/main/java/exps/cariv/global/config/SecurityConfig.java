package exps.cariv.global.config;


import exps.cariv.global.jwt.JwtAuthenticationFilter;
import exps.cariv.global.jwt.JwtTokenProvider;
import exps.cariv.global.logging.MdcFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;

    @Value("${app.security.allowed-origins:}")
    private List<String> allowedOrigins;

    @Value("${app.security.allow-docs:false}")
    private boolean allowDocs;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        return http
                .csrf(AbstractHttpConfigurer::disable)

                .headers(h -> {
                    h.frameOptions(f -> f.deny());
                    h.contentTypeOptions(Customizer.withDefaults());
                    h.referrerPolicy(r -> r
                            .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
                    h.addHeaderWriter(new StaticHeadersWriter(
                            "Permissions-Policy", "geolocation=(), microphone=(), camera=()"));
                    h.contentSecurityPolicy(csp -> csp
                            .policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; base-uri 'self'; frame-ancestors 'none'; form-action 'self'"));
                    h.httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(31536000));
                })

                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> {
                    auth.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll();
                    auth.requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                            .requestMatchers("/", "/error", "/favicon.ico").permitAll()
                            .requestMatchers(
                                    org.springframework.http.HttpMethod.POST,
                                    "/api/auth/login",
                                    "/api/auth/refresh",
                                    "/api/auth/signup"
                            ).permitAll();

                    if (allowDocs) {
                        auth.requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/swagger-resources/**",
                                "/webjars/**",
                                "/h2-console/**"
                        ).permitAll();
                    } else {
                        auth.requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/swagger-resources/**",
                                "/webjars/**",
                                "/h2-console/**"
                        ).denyAll();
                    }

                    auth.requestMatchers("/api/master/**").hasRole("MASTER")
                            .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "MASTER")
                            .requestMatchers("/api/**").authenticated()
                            .anyRequest().denyAll();
                })

                .addFilterAfter(new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new MdcFilter(), JwtAuthenticationFilter.class)

                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "X-Request-Id"));

        List<String> mergedOrigins = new ArrayList<>();
        if (allowedOrigins != null) {
            for (String origin : allowedOrigins) {
                if (origin == null) continue;
                String trimmed = origin.trim();
                if (!trimmed.isBlank()) mergedOrigins.add(trimmed);
            }
        }
        // 개발 FE 기본 허용 (명시 요청)
        mergedOrigins.add("http://localhost:5173");
        mergedOrigins.add("http://127.0.0.1:5173");
        // 운영/스테이징 FE 허용
        mergedOrigins.add("https://front-test-orcin-eta.vercel.app");
        mergedOrigins.add("https://saas-fronts-one.vercel.app");

        config.setAllowedOrigins(mergedOrigins);
        config.setAllowedOriginPatterns(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "https://*.vercel.app"
        ));
        config.setAllowCredentials(true);

        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
