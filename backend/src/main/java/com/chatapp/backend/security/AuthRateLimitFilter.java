package com.chatapp.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP token-bucket throttle for the unauthenticated auth endpoints
 * (/api/auth/login and /api/auth/register). Blunts online password
 * brute-force and account enumeration with no external dependency.
 *
 * Each client IP gets a bucket of {@value #CAPACITY} tokens, refilled one token
 * every {@value #REFILL_INTERVAL_MS} ms — i.e. a burst of 10 then ~10/min
 * sustained. When empty, the request is rejected with 429.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final int CAPACITY = 10;
    private static final long REFILL_INTERVAL_MS = 6_000; // 1 token / 6s
    private static final int MAX_TRACKED_IPS = 50_000;    // guard against unbounded growth

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static final class Bucket {
        double tokens = CAPACITY;
        long lastRefill = System.currentTimeMillis();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !("/api/auth/login".equals(path) || "/api/auth/register".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!tryConsume(clientIp(request))) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"message\":\"Too many attempts — please wait a minute and try again.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean tryConsume(String ip) {
        if (buckets.size() > MAX_TRACKED_IPS) {
            // Drop buckets idle long enough to be guaranteed full again.
            long cutoff = System.currentTimeMillis() - CAPACITY * REFILL_INTERVAL_MS;
            buckets.entrySet().removeIf(e -> e.getValue().lastRefill < cutoff);
        }
        Bucket bucket = buckets.computeIfAbsent(ip, k -> new Bucket());
        synchronized (bucket) {
            long now = System.currentTimeMillis();
            double tokens = Math.min(CAPACITY,
                    bucket.tokens + (now - bucket.lastRefill) / (double) REFILL_INTERVAL_MS);
            bucket.lastRefill = now;
            if (tokens < 1) {
                bucket.tokens = tokens;
                return false;
            }
            bucket.tokens = tokens - 1;
            return true;
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
