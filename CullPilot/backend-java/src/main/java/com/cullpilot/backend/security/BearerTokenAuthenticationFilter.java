package com.cullpilot.backend.security;

import com.cullpilot.backend.service.auth.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private final AuthService authService;

    public BearerTokenAuthenticationFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7).trim();
            authService.authenticateToken(token).ifPresent(userId -> {
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null, List.of());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        filterChain.doFilter(request, response);
    }
}
