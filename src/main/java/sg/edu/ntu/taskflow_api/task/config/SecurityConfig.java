package sg.edu.ntu.taskflow_api.task.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import sg.edu.ntu.taskflow_api.task.security.JwtAuthFilter;
import sg.edu.ntu.taskflow_api.task.security.JwtService;
import sg.edu.ntu.taskflow_api.task.security.RestAccessDeniedHandler;
import sg.edu.ntu.taskflow_api.task.security.RestAuthenticationEntryPoint;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

        private final JwtService jwtService;
        private final RestAuthenticationEntryPoint authenticationEntryPoint;
        private final RestAccessDeniedHandler accessDeniedHandler;

        // Spring calls this constructor and supplies the beans
        public SecurityConfig(
                        JwtService jwtService,
                        RestAuthenticationEntryPoint authenticationEntryPoint,
                        RestAccessDeniedHandler accessDeniedHandler) {
                this.jwtService = jwtService;
                this.authenticationEntryPoint = authenticationEntryPoint;
                this.accessDeniedHandler = accessDeniedHandler;
        }

        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

                http
                                // REST APIs using stateless JWT auth do not need CSRF protection.
                                // CSRF is designed for browser-based session flows; since we never issue
                                // a session cookie, there is nothing for a cross-site request to hijack.
                                .csrf(csrf -> csrf.disable())

                                // Stateless: Spring Security will not create or use sessions.
                                // Every request must carry a valid JWT token.
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                                // 401 (missing or invalid token) and 403 (authenticated, not permitted)
                                // bodies are both produced by GlobalExceptionHandler, via these two classes.
                                .exceptionHandling(ex -> ex
                                                .authenticationEntryPoint(authenticationEntryPoint)
                                                .accessDeniedHandler(accessDeniedHandler))

                                // This service issues no tokens, so there is no public login route.
                                // Everything needs a valid token.
                                .authorizeHttpRequests(auth -> auth
                                                .anyRequest().authenticated())

                                // Register JWT filter to run before username/password authentication
                                .addFilterBefore(new JwtAuthFilter(jwtService),
                                                UsernamePasswordAuthenticationFilter.class);

                return http.build();
        }
}
