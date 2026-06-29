package com.jadicale.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String DAV_CAPS = "1, 2, 3, calendar-access";
    private static final String CALDAV_ALLOW =
            "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, PROPFIND, PROPPATCH, REPORT";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // CalDAV clients probe OPTIONS without credentials
                .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/caldav/**").permitAll()
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Allow WebDAV methods that StrictHttpFirewall blocks by default
    @Bean
    public HttpFirewall caldavHttpFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowedHttpMethods(Arrays.asList(
                "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE",
                "PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK", "REPORT"
        ));
        return firewall;
    }

    // Inject DAV capability headers into all /caldav responses;
    // handle unauthenticated OPTIONS directly so clients can discover the server.
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> caldavHeadersFilter() {
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                    FilterChain chain) throws ServletException, IOException {
                resp.setHeader("DAV", DAV_CAPS);
                resp.setHeader("MS-Author-Via", "DAV");
                if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
                    resp.setHeader("Allow", CALDAV_ALLOW);
                    resp.setStatus(HttpServletResponse.SC_OK);
                    return;
                }
                chain.doFilter(req, resp);
            }
        });
        reg.addUrlPatterns("/caldav/*", "/caldav/**");
        reg.setOrder(1);
        return reg;
    }
}
