package com.baafoo.server.auth;

import com.baafoo.core.model.User;
import com.baafoo.server.storage.StorageService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    // C-2: reduced from 24h to 4h to limit the blast radius of a stolen JWT
    // (token is stored in localStorage on the SPA client, which is reachable
    // by XSS). A shorter window reduces the value of an exfiltrated token.
    // Operators wanting longer sessions should set auth.tokenExpiryHours and
    // pair it with a refresh-token mechanism (not implemented yet).
    private static final long DEFAULT_TOKEN_EXPIRY_MS = 4 * 60 * 60 * 1000L;
    private static final long MAX_TOKEN_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L;

    private final StorageService storage;
    private final SecretKey jwtKey;
    private final boolean authEnabled;
    private final boolean localBypass;
    private final Map<String, String> apiKeyRoleMap;
    private final SecureRandom random = new SecureRandom();

    public enum Role {
        ADMIN("admin"), DEVELOPER("developer"), TESTER("tester"), GUEST("guest");

        private final String value;
        Role(String value) { this.value = value; }
        public String getValue() { return value; }

        public static Role fromValue(String value) {
            for (Role r : values()) {
                if (r.value.equals(value)) return r;
            }
            return null;
        }

        public static boolean isValid(String value) {
            return fromValue(value) != null;
        }
    }

    public enum Resource {
        RULE("rule"), SCENE("scene"), ENVIRONMENT("environment"),
        MQ_RELATIONSHIP("mq-relationship"),
        RECORDING("recording"), USER("user");

        private final String value;
        Resource(String value) { this.value = value; }
        public String getValue() { return value; }

        public static Resource fromValue(String value) {
            for (Resource r : values()) {
                if (r.value.equals(value)) return r;
            }
            return null;
        }
    }

    public enum Action {
        READ("read"), CREATE("create"), UPDATE("update"), DELETE("delete"),
        ASSOCIATE("associate"), ACTIVATE("activate"), IMPORT_EXPORT("import_export");

        private final String value;
        Action(String value) { this.value = value; }
        public String getValue() { return value; }

        public static Action fromValue(String value) {
            for (Action a : values()) {
                if (a.value.equals(value)) return a;
            }
            return null;
        }
    }

    public AuthService(StorageService storage, String jwtSecret, boolean authEnabled, boolean localBypass,
                       Map<String, String> apiKeyRoleMap) {
        this.storage = storage;
        this.authEnabled = authEnabled;
        this.localBypass = localBypass;
        this.apiKeyRoleMap = apiKeyRoleMap != null ? apiKeyRoleMap : new HashMap<String, String>();

        if (jwtSecret != null && jwtSecret.length() >= 32) {
            this.jwtKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        } else {
            // L-5: secret too short (HS256 requires >= 32 bytes per RFC 8725
            // §3.2). Fall back to a random key so the server still boots, but
            // warn loudly: every restart rotates the key, which invalidates
            // all outstanding JWTs (forces re-login for every user) and
            // breaks any persisted JWT-based session in the web console.
            // Operators must set auth.jwtSecret to a stable >=32-byte value
            // in production (e.g. `openssl rand -base64 48`).
            this.jwtKey = Keys.secretKeyFor(SignatureAlgorithm.HS256);
            log.warn("No custom JWT secret configured or secret too short ({} chars, need >=32), "
                    + "using auto-generated key. Tokens will not survive restarts. "
                    + "Set auth.jwtSecret to a stable >=32-byte value in production.",
                    jwtSecret == null ? 0 : jwtSecret.length());
        }

        log.info("AuthService initialized: authEnabled={}, localBypass={}", authEnabled, localBypass);
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    public AuthResult authenticate(String authHeader, String apiKeyHeader, String remoteAddr) {
        if (!authEnabled) {
            return new AuthResult(true, "admin", "Authentication disabled");
        }

        if (localBypass && isLocalAddress(remoteAddr)) {
            return new AuthResult(true, "admin", "Local bypass");
        }

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            return validateJwtToken(token);
        }

        if (apiKeyHeader != null && !apiKeyHeader.isEmpty()) {
            return validateApiKey(apiKeyHeader);
        }

        // No credentials provided — reject instead of falling back to guest.
        // Previously this returned AuthResult(true, "guest", ...) which allowed
        // anonymous read access to all API endpoints including user lists.
        return new AuthResult(false, null, "Authentication required");
    }

    private boolean isLocalAddress(String addr) {
        return "127.0.0.1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr) || "::1".equals(addr) || "localhost".equals(addr);
    }

    private AuthResult validateJwtToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(jwtKey)
                    .requireIssuer("ehre")
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            // Ehre SSO unified JWT format: username and roleCode are custom claims
            // (not subject/role). Fall back to legacy format for backward compat.
            String username = claims.get("username", String.class);
            if (username == null) {
                username = claims.getSubject();
            }
            String role = claims.get("roleCode", String.class);
            if (role == null) {
                role = claims.get("role", String.class);
            }

            // Verify audience
            String aud = claims.getAudience();
            if (aud == null || !"BAAFOO".equals(aud)) {
                return new AuthResult(false, null, "Invalid token: audience mismatch");
            }

            if (username == null || role == null) {
                return new AuthResult(false, null, "Invalid token: missing claims");
            }

            return new AuthResult(true, role, "JWT authenticated", username);
        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return new AuthResult(false, null, "Invalid or expired token");
        }
    }

    private AuthResult validateApiKey(String apiKey) {
        // H-7: use constant-time comparison for the configured API keys to
        // avoid timing side-channels. The previous Map.get(apiKey) returned
        // in O(1) but leaked key-prefix information via per-byte early-exit
        // in the underlying String.hashCode + equals path.
        if (apiKey != null) {
            byte[] apiKeyBytes = apiKey.getBytes(StandardCharsets.UTF_8);
            for (Map.Entry<String, String> entry : apiKeyRoleMap.entrySet()) {
                if (MessageDigest.isEqual(apiKeyBytes, entry.getKey().getBytes(StandardCharsets.UTF_8))) {
                    return new AuthResult(true, entry.getValue(), "API Key authenticated");
                }
            }
        }

        User user = storage.getUserByApiKey(apiKey);
        if (user != null) {
            return new AuthResult(true, user.getRole(), "API Key authenticated", user.getUsername());
        }

        return new AuthResult(false, null, "Invalid API key");
    }

    public LoginResult login(String username, String password, Long expiresInMs) {
        if (!authEnabled) {
            String token = generateToken("system", "admin", DEFAULT_TOKEN_EXPIRY_MS);
            return new LoginResult(true, token, "admin", "Authentication disabled, admin token issued");
        }

        User user = storage.getUserByUsername(username);
        if (user == null) {
            return new LoginResult(false, null, null, "Invalid username or password");
        }

        if (!verifyPassword(password, user.getPassword())) {
            return new LoginResult(false, null, null, "Invalid username or password");
        }

        // Migrate legacy SHA-256 hash to bcrypt on successful login
        if (needsRehash(user.getPassword())) {
            String newHash = hashPassword(password);
            storage.updateUserPassword(username, newHash);
            log.info("Upgraded password hash from SHA-256 to bcrypt for user: {}", username);
        }

        long expiry = expiresInMs != null && expiresInMs > 0
                ? Math.min(expiresInMs, MAX_TOKEN_EXPIRY_MS)
                : DEFAULT_TOKEN_EXPIRY_MS;

        String token = generateToken(username, user.getRole(), expiry);
        storage.updateUserLastLogin(username);

        return new LoginResult(true, token, user.getRole(), "Login successful");
    }

    private String generateToken(String username, String role, long expiryMs) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setSubject(username)
                .claim("username", username)
                .claim("role", role)
                .claim("roleCode", role)
                .claim("userId", username)
                .claim("displayName", username)
                .claim("roleId", role)
                .setIssuer("ehre")
                .setAudience("BAAFOO")
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + expiryMs))
                .signWith(jwtKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public String hashPassword(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt(12));
    }

    /**
     * Verify a password against a stored hash.
     * Supports both bcrypt (new format, starts with $2a$/$2b$/$2y$) and
     * legacy SHA-256 (old format, Base64(salt):Base64(hash)).
     * Legacy hashes that verify successfully should be rehashed via
     * {@link #needsRehash(String)}.
     */
    public boolean verifyPassword(String password, String storedHash) {
        if (storedHash == null || storedHash.isEmpty()) return false;

        // bcrypt format: $2a$, $2b$, $2y$ prefix
        if (storedHash.startsWith("$2a$") || storedHash.startsWith("$2b$") || storedHash.startsWith("$2y$")) {
            try {
                return BCrypt.checkpw(password, storedHash);
            } catch (Exception e) {
                log.error("bcrypt password verification failed: {}", e.getMessage());
                return false;
            }
        }

        // Legacy SHA-256 format: Base64(salt):Base64(hash)
        try {
            String[] parts = storedHash.split(":");
            if (parts.length != 2) return false;
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            byte[] hashed = md.digest(password.getBytes(StandardCharsets.UTF_8));
            String computedHash = Base64.getEncoder().encodeToString(hashed);
            // H-7: constant-time comparison to avoid timing leak of the
            // stored hash via per-byte early-exit in String.equals.
            return MessageDigest.isEqual(
                    computedHash.getBytes(StandardCharsets.UTF_8),
                    parts[1].getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Password verification failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if a stored hash uses the legacy SHA-256 format and should be
     * rehashed to bcrypt on next login.
     */
    public boolean needsRehash(String storedHash) {
        return storedHash != null && !storedHash.isEmpty()
                && !storedHash.startsWith("$2a$")
                && !storedHash.startsWith("$2b$")
                && !storedHash.startsWith("$2y$");
    }

    public String generateApiKey() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return "bf_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static PasswordValidation validatePassword(String password) {
        if (password == null || password.isEmpty()) {
            return new PasswordValidation(false, "密码不能为空");
        }
        if (password.length() < 8) {
            return new PasswordValidation(false, "密码长度不能少于8位");
        }
        // L-6: upper bound on password length. 64 chars caps the bcrypt
        // computation cost (bcrypt itself only uses the first 72 bytes, but
        // a multi-KB password would still waste CPU on the pre-hash) and
        // prevents an attacker from submitting pathologically long inputs
        // to DoS the login endpoint. 64 chars is also the OWASP-recommended
        // maximum for bcrypt-backed credentials.
        if (password.length() > 64) {
            return new PasswordValidation(false, "密码长度不能超过64位");
        }
        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;
        for (char c : password.toCharArray()) {
            if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else hasSpecial = true;
        }
        if (!hasUpper) {
            return new PasswordValidation(false, "密码必须包含至少一个大写字母", "user.password_uppercase");
        }
        if (!hasLower) {
            return new PasswordValidation(false, "密码必须包含至少一个小写字母", "user.password_lowercase");
        }
        if (!hasDigit) {
            return new PasswordValidation(false, "密码必须包含至少一个数字", "user.password_digit");
        }
        if (!hasSpecial) {
            return new PasswordValidation(false, "密码必须包含至少一个特殊字符", "user.password_special");
        }
        return new PasswordValidation(true, null);
    }

    public static boolean hasPermission(Role role, Resource resource, Action action) {
        if (role == Role.ADMIN) return true;
        if (action == Action.READ) return true;

        if (resource == Resource.RULE) {
            if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE || action == Action.IMPORT_EXPORT) {
                return role == Role.DEVELOPER;
            }
        }

        if (resource == Resource.SCENE) {
            if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE || action == Action.ACTIVATE) {
                return role == Role.DEVELOPER || role == Role.TESTER;
            }
        }

        if (resource == Resource.ENVIRONMENT) {
            if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE || action == Action.ASSOCIATE) {
                return false;
            }
        }

        if (resource == Resource.RECORDING) {
            if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
                return role == Role.DEVELOPER || role == Role.TESTER;
            }
        }

        if (resource == Resource.USER) {
            return false;
        }

        return false;
    }

    public static boolean hasPermission(String role, String resource, String action) {
        return hasPermission(Role.fromValue(role), Resource.fromValue(resource), Action.fromValue(action));
    }

    /**
     * Simplified permission check that maps HTTP method to action.
     * GET → read, POST → create, PUT → update, DELETE → delete.
     *
     * @param role       the user's role string
     * @param httpMethod the HTTP method (GET, POST, PUT, DELETE)
     * @param path       the request path (used to determine resource type)
     * @return true if the role has permission for the implied action on the inferred resource
     */
    public static boolean checkPermission(String role, String httpMethod, String path) {
        if (role == null || httpMethod == null || path == null) return false;

        // Map HTTP method to action
        String action;
        switch (httpMethod.toUpperCase()) {
            case "GET":
            case "HEAD":
            case "OPTIONS":
                action = "read";
                break;
            case "POST":
                action = "create";
                break;
            case "PUT":
            case "PATCH":
                action = "update";
                break;
            case "DELETE":
                action = "delete";
                break;
            default:
                action = "read";
                break;
        }

        // Infer resource from path
        String resource = inferResourceFromPath(path);

        return hasPermission(role, resource, action);
    }

    /**
     * Infer the resource type from the API path.
     * E.g., "/__baafoo__/api/rules/xxx" → "rule"
     */
    static String inferResourceFromPath(String path) {
        if (path == null) return "rule";
        String prefix = "/__baafoo__/api/";
        if (!path.startsWith(prefix)) return "rule";
        String sub = path.substring(prefix.length());

        if (sub.startsWith("rules")) return "rule";
        if (sub.startsWith("scenes")) return "scene";
        if (sub.startsWith("environments")) return "environment";
        if (sub.startsWith("mq-relationships")) return "mq-relationship";
        if (sub.startsWith("recordings")) return "recording";
        if (sub.startsWith("users")) return "user";
        if (sub.startsWith("agents")) return "rule";
        if (sub.startsWith("status")) return "rule";
        if (sub.startsWith("auth")) return "rule";
        if (sub.startsWith("agent/")) return "rule";
        if (sub.startsWith("rulesets")) return "rule";
        if (sub.startsWith("logs")) return "recording";
        return "rule";
    }

    public static class AuthResult {
        public final boolean success;
        public final String role;
        public final String message;
        public final String username;

        public AuthResult(boolean success, String role, String message) {
            this(success, role, message, null);
        }

        public AuthResult(boolean success, String role, String message, String username) {
            this.success = success;
            this.role = role;
            this.message = message;
            this.username = username;
        }

        public String getRole() { return role; }
        public String getUsername() { return username; }
        public String getMessage() { return message; }
        public boolean isSuccess() { return success; }
    }

    public static class LoginResult {
        public final boolean success;
        public final String token;
        public final String role;
        public final String message;

        public LoginResult(boolean success, String token, String role, String message) {
            this.success = success;
            this.token = token;
            this.role = role;
            this.message = message;
        }

        public String getToken() { return token; }
        public String getRole() { return role; }
        public String getMessage() { return message; }
        public boolean isSuccess() { return success; }
    }

    public static class PasswordValidation {
        public final boolean valid;
        public final String message;
        public final String errorCode;

        public PasswordValidation(boolean valid, String message) {
            this(valid, message, null);
        }

        public PasswordValidation(boolean valid, String message, String errorCode) {
            this.valid = valid;
            this.message = message;
            this.errorCode = errorCode;
        }

        public String getMessage() { return message; }
        public boolean isValid() { return valid; }
        public String getErrorCode() { return errorCode; }
    }
}
