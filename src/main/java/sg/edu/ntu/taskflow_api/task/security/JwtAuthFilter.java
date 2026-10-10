package sg.edu.ntu.taskflow_api.task.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

import sg.edu.ntu.taskflow_api.task.dto.ClaimsDto;
import sg.edu.ntu.taskflow_api.task.exception.InvalidTokenException;

public class JwtAuthFilter extends OncePerRequestFilter {

    /**
     * Request attribute holding the InvalidTokenException when a token was
     * rejected. RestAuthenticationEntryPoint reads it to report why.
     */
    public static final String INVALID_TOKEN_ATTRIBUTE = "jwt.invalid-token";

    private static final String BEARER_PREFIX = "Bearer ";

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        logger.info("JwtAuthFilter initialized with JwtService");
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        // If there is no Bearer Authorization header, continue the chain.
        // SecurityConfig will decide whether the route requires authentication.
        // The scheme name is case-insensitive (RFC 7235).
        if (authHeader == null
                || !authHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            filterChain.doFilter(request, response);
            return;
        }

        // Extract the JWT token from the Authorization header
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();

        try {
            // Validate token and extract claims
            ClaimsDto claims = jwtService.extractClaims(token);

            // Convert the roles from the token into Spring Security authorities
            List<SimpleGrantedAuthority> authorities = claims.roles().stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();

            // Create an authentication token using the extracted username and authorities
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    claims.username(), null, authorities);

            // Embed the authentication details into the token
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            // Set the authentication in the SecurityContext for the current request
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (InvalidTokenException ex) {
            SecurityContextHolder.clearContext();
            request.setAttribute(INVALID_TOKEN_ATTRIBUTE, ex);
            logger.warn("Rejected token on {}: {}", request.getRequestURI(), ex.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}