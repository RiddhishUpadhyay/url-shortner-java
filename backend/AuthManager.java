package backend;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import io.javalin.http.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.config.RegisterBeanMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.mindrot.jbcrypt.BCrypt;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

public class AuthManager {
    private final Jdbi jdbi;

    public AuthManager() {
        this.jdbi = Config.getJdbi();
    }

    // 1. User Domain Model
    public static class User {
        private int id;
        private String username;
        private String passwordHash;
        private Instant createdAt;

        public User() {}

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPasswordHash() { return passwordHash; }
        public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    }

    // 2. Authentication Request DTO
    public static class AuthPayload {
        private String username;
        private String password;

        public AuthPayload() {}
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    // 3. BCrypt Hashing Utils
    public static class PasswordUtils {
        public static String hash(String plainPassword) {
            return BCrypt.hashpw(plainPassword, BCrypt.gensalt(12));
        }
        public static boolean verify(String plainPassword, String hashedPassword) {
            try {
                return BCrypt.checkpw(plainPassword, hashedPassword);
            } catch (Exception e) {
                return false;
            }
        }
    }

    // 4. Auth0 JWT Token Utils
    public static class JwtUtils {
        private static final String SECRET = Config.get("jwt.secret", "super-secure-jwt-secret-key-change-in-production-12345");
        private static final Algorithm algorithm = Algorithm.HMAC256(SECRET);
        private static final String ISSUER = "url-shortener-api";

        public static String generateToken(int userId, String username) {
            return JWT.create()
                    .withIssuer(ISSUER)
                    .withSubject(String.valueOf(userId))
                    .withClaim("username", username)
                    .withIssuedAt(Instant.now())
                    .withExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                    .sign(algorithm);
        }

        public static DecodedJWT validateToken(String token) {
            try {
                JWTVerifier verifier = JWT.require(algorithm)
                        .withIssuer(ISSUER)
                        .build();
                return verifier.verify(token);
            } catch (Exception e) {
                return null;
            }
        }
    }

    // 5. JDBI UserRepository DAO Mapping
    @RegisterBeanMapper(User.class)
    public interface UserRepository {
        @SqlQuery("SELECT * FROM users WHERE id = :id")
        Optional<User> findById(@Bind("id") int id);

        @SqlQuery("SELECT * FROM users WHERE username = :username")
        Optional<User> findByUsername(@Bind("username") String username);

        @SqlUpdate("INSERT INTO users(username, password_hash) VALUES(:username, :passwordHash)")
        @GetGeneratedKeys("id")
        int create(@BindBean User user);
    }

    // 6. Request Handlers
    public void register(Context ctx) {
        AuthPayload payload = ctx.bodyAsClass(AuthPayload.class);
        if (payload.getUsername() == null || payload.getUsername().trim().isEmpty() ||
            payload.getPassword() == null || payload.getPassword().trim().isEmpty()) {
            throw new BadRequestResponse("Username and password are required");
        }

        jdbi.useTransaction(handle -> {
            UserRepository repo = handle.attach(UserRepository.class);
            Optional<User> existing = repo.findByUsername(payload.getUsername());
            if (existing.isPresent()) {
                throw new BadRequestResponse("This username is already taken. Please try another one.");
            }

            User user = new User();
            user.setUsername(payload.getUsername().trim());
            user.setPasswordHash(PasswordUtils.hash(payload.getPassword()));
            repo.create(user);
        });

        ctx.status(HttpStatus.CREATED).json(Map.of("message", "User registered successfully"));
    }

    public void login(Context ctx) {
        AuthPayload payload = ctx.bodyAsClass(AuthPayload.class);
        if (payload.getUsername() == null || payload.getUsername().trim().isEmpty() ||
            payload.getPassword() == null || payload.getPassword().trim().isEmpty()) {
            throw new BadRequestResponse("Username and password are required");
        }

        User user = jdbi.withHandle(handle -> {
            UserRepository repo = handle.attach(UserRepository.class);
            return repo.findByUsername(payload.getUsername().trim())
                    .orElseThrow(() -> new UnauthorizedResponse("This username does not exist. Please sign up first."));
        });

        if (!PasswordUtils.verify(payload.getPassword(), user.getPasswordHash())) {
            throw new UnauthorizedResponse("Incorrect password. Please try again.");
        }

        String token = JwtUtils.generateToken(user.getId(), user.getUsername());
        ctx.json(Map.of("token", token, "username", user.getUsername()));
    }

    // 7. JWT Authentication Interceptor
    public static void authenticate(Context ctx) {
        if (ctx.method().name().equalsIgnoreCase("OPTIONS")) {
            return;
        }

        String authHeader = ctx.header("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new UnauthorizedResponse("Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7).trim();
        DecodedJWT decoded = JwtUtils.validateToken(token);
        if (decoded == null) {
            throw new UnauthorizedResponse("Invalid or expired token");
        }

        ctx.attribute("userId", Integer.parseInt(decoded.getSubject()));
        ctx.attribute("username", decoded.getClaim("username").asString());
    }
}
