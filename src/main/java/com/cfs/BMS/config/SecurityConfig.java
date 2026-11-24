package com.cfs.BMS.config;

import com.cfs.BMS.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // public reads
                        .requestMatchers(HttpMethod.GET, paths("movies/**", "cities/**", "theaters/**",
                                "shows/**", "screens/**", "seats/**")).permitAll()
                        .requestMatchers(paths("users/register", "users/login")).permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                                "/actuator/health", "/actuator/info").permitAll()
                        // seat availability / status per show stays public (must precede the booking GET rule below)
                        .requestMatchers(HttpMethod.GET, paths("bookings/show/**")).permitAll()
                        // booking details and history are private to their owner
                        .requestMatchers(HttpMethod.GET, paths("bookings/**")).authenticated()
                        // bookings require auth; POST holds, creates, confirms
                        .requestMatchers(HttpMethod.POST, paths("bookings/**")).authenticated()
                        .requestMatchers(HttpMethod.PUT, paths("bookings/**")).authenticated()
                        .anyRequest().permitAll()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Expands each suffix under both API prefixes ("/api" legacy and "/api/v1"). Written out explicitly because
     * a "**" in the middle of a pattern (e.g. "/api/**&#47;movies") is rejected by Spring's PathPatternParser.
     */
    private static String[] paths(String... suffixes) {
        String[] out = new String[suffixes.length * 2];
        for (int i = 0; i < suffixes.length; i++) {
            out[2 * i] = "/api/" + suffixes[i];
            out[2 * i + 1] = "/api/v1/" + suffixes[i];
        }
        return out;
    }
}
