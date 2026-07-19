package backend;

import io.javalin.http.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.config.RegisterBeanMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class UrlManager {
    private static final Logger logger = LoggerFactory.getLogger(UrlManager.class);
    private final Jdbi jdbi;
    private final Cache cache;
    private final RateLimiter rateLimiter;
    private final String baseUrl;

    public UrlManager(Cache cache, RateLimiter rateLimiter) {
        this.jdbi = Config.getJdbi();
        this.cache = cache;
        this.rateLimiter = rateLimiter;
        this.baseUrl = Config.get("base.url", "http://localhost:8080/");
    }

    // 1. URL Domain Model
    public static class Url {
        private int id;
        private String shortCode;
        private String originalUrl;
        private Integer ownerId;
        private Instant createdAt;
        private Instant expiresAt;

        public Url() {}

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getShortCode() { return shortCode; }
        public void setShortCode(String shortCode) { this.shortCode = shortCode; }
        public String getOriginalUrl() { return originalUrl; }
        public void setOriginalUrl(String originalUrl) { this.originalUrl = originalUrl; }
        public Integer getOwnerId() { return ownerId; }
        public void setOwnerId(Integer ownerId) { this.ownerId = ownerId; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

        public boolean isExpired() {
            return expiresAt != null && expiresAt.isBefore(Instant.now());
        }
    }

    // 2. Request DTO
    public static class ShortenRequest {
        private String originalUrl;
        private String customAlias;
        private Integer expiresInDays;

        public ShortenRequest() {}
        public String getOriginalUrl() { return originalUrl; }
        public void setOriginalUrl(String originalUrl) { this.originalUrl = originalUrl; }
        public String getCustomAlias() { return customAlias; }
        public void setCustomAlias(String customAlias) { this.customAlias = customAlias; }
        public Integer getExpiresInDays() { return expiresInDays; }
        public void setExpiresInDays(Integer expiresInDays) { this.expiresInDays = expiresInDays; }
    }

    // 3. Response DTO
    public static class UrlResponse {
        private int id;
        private String shortCode;
        private String originalUrl;
        private String shortUrl;
        private long createdAt;
        private Long expiresAt;
        private int clicksCount;

        public UrlResponse() {}
        public UrlResponse(int id, String shortCode, String originalUrl, String shortUrl, long createdAt, Long expiresAt, int clicksCount) {
            this.id = id;
            this.shortCode = shortCode;
            this.originalUrl = originalUrl;
            this.shortUrl = shortUrl;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.clicksCount = clicksCount;
        }

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getShortCode() { return shortCode; }
        public void setShortCode(String shortCode) { this.shortCode = shortCode; }
        public String getOriginalUrl() { return originalUrl; }
        public void setOriginalUrl(String originalUrl) { this.originalUrl = originalUrl; }
        public String getShortUrl() { return shortUrl; }
        public void setShortUrl(String shortUrl) { this.shortUrl = shortUrl; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
        public Long getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }
        public int getClicksCount() { return clicksCount; }
        public void setClicksCount(int clicksCount) { this.clicksCount = clicksCount; }
    }

    // 4. Base62 Shortening Logic
    public static class Base62 {
        private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        private static final int BASE = ALPHABET.length();

        public static String encode(long num) {
            if (num == 0) return String.valueOf(ALPHABET.charAt(0));
            StringBuilder sb = new StringBuilder();
            while (num > 0) {
                sb.append(ALPHABET.charAt((int) (num % BASE)));
                num /= BASE;
            }
            return sb.reverse().toString();
        }
    }

    // 5. Caching System (Jedis wrapper)
    public static class Cache {
        private final JedisPool jedisPool;
        private static final int DEFAULT_TTL = 86400; // 24 hours

        public Cache() {
            this.jedisPool = Config.getJedisPool();
        }

        public String getOriginalUrl(String shortCode) {
            try (Jedis jedis = jedisPool.getResource()) {
                String cached = jedis.get("url:" + shortCode);
                if (cached == null) return null;
                String[] parts = cached.split("\\|");
                if (parts.length < 2) return parts[0];
                String originalUrl = parts[0];
                String expiry = parts[1];
                if (expiry.equals("never")) return originalUrl;
                long expiryTime = Long.parseLong(expiry);
                if (expiryTime < System.currentTimeMillis()) return "EXPIRED";
                return originalUrl;
            } catch (Exception e) {
                logger.error("Failed to fetch from cache for shortCode: " + shortCode, e);
                return null;
            }
        }

        public void setOriginalUrl(String shortCode, String originalUrl, Instant expiresAt) {
            try (Jedis jedis = jedisPool.getResource()) {
                String value = originalUrl + "|" + (expiresAt != null ? expiresAt.toEpochMilli() : "never");
                jedis.setex("url:" + shortCode, DEFAULT_TTL, value);
            } catch (Exception e) {
                logger.error("Failed to save to cache for shortCode: " + shortCode, e);
            }
        }

        public void invalidate(String shortCode) {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.del("url:" + shortCode);
            } catch (Exception e) {
                logger.error("Failed to invalidate cache for shortCode: " + shortCode, e);
            }
        }
    }

    // 6. Sliding Window Rate Limiter
    public static class RateLimiter {
        private final JedisPool jedisPool;

        public RateLimiter() {
            this.jedisPool = Config.getJedisPool();
        }

        public boolean isAllowed(String action, String identifier, int limit, int windowSeconds) {
            String key = "rate_limit:" + action + ":" + identifier;
            long now = System.currentTimeMillis();
            long windowStart = now - (windowSeconds * 1000L);
            String member = UUID.randomUUID().toString();

            try (Jedis jedis = jedisPool.getResource()) {
                Transaction t = jedis.multi();
                t.zadd(key, now, member);
                t.zremrangeByScore(key, 0, (double) windowStart);
                t.zcard(key);
                t.expire(key, windowSeconds);
                List<Object> results = t.exec();

                if (results == null || results.size() < 3) return true;

                long currentRequests = (Long) results.get(2);
                if (currentRequests > limit) {
                    try { jedis.zrem(key, member); } catch (Exception ex) {}
                    return false;
                }
                return true;
            } catch (Exception e) {
                logger.error("Rate limiter exception for action: " + action + ", identifier: " + identifier, e);
                return true; // Fail open
            }
        }
    }

    // 7. JDBI UrlRepository DAO Mapping
    @RegisterBeanMapper(Url.class)
    public interface UrlRepository {
        @SqlQuery("SELECT * FROM urls WHERE id = :id")
        Optional<Url> findById(@Bind("id") int id);

        @SqlQuery("SELECT * FROM urls WHERE short_code = :shortCode")
        Optional<Url> findByShortCode(@Bind("shortCode") String shortCode);

        @SqlQuery("SELECT * FROM urls WHERE owner_id = :ownerId ORDER BY created_at DESC")
        List<Url> findByOwnerId(@Bind("ownerId") int ownerId);

        @SqlQuery("SELECT nextval('urls_id_seq')")
        int getNextVal();

        @SqlUpdate("INSERT INTO urls(id, short_code, original_url, owner_id, expires_at) VALUES(:id, :shortCode, :originalUrl, :ownerId, :expiresAt)")
        void insertWithId(@BindBean Url url);

        @SqlUpdate("INSERT INTO urls(short_code, original_url, owner_id, expires_at) VALUES(:shortCode, :originalUrl, :ownerId, :expiresAt)")
        @GetGeneratedKeys("id")
        int insertWithoutId(@BindBean Url url);

        @SqlUpdate("DELETE FROM urls WHERE id = :id AND owner_id = :ownerId")
        int delete(@Bind("id") int id, @Bind("ownerId") int ownerId);
    }

    // 8. Request Handlers
    public void shorten(Context ctx) {
        ShortenRequest req = ctx.bodyAsClass(ShortenRequest.class);
        String originalUrl = req.getOriginalUrl();

        if (originalUrl == null || originalUrl.trim().isEmpty()) {
            throw new BadRequestResponse("Original URL is required");
        }
        if (!originalUrl.startsWith("http://") && !originalUrl.startsWith("https://")) {
            throw new BadRequestResponse("URL must start with http:// or https://");
        }

        int ownerId = ctx.attribute("userId");

        Url url = new Url();
        url.setOriginalUrl(originalUrl.trim());
        url.setOwnerId(ownerId);
        if (req.getExpiresInDays() != null && req.getExpiresInDays() > 0) {
            url.setExpiresAt(Instant.now().plus(req.getExpiresInDays(), ChronoUnit.DAYS));
        }

        String finalShortCode = jdbi.inTransaction(handle -> {
            UrlRepository repo = handle.attach(UrlRepository.class);
            String code;

            if (req.getCustomAlias() != null && !req.getCustomAlias().trim().isEmpty()) {
                code = req.getCustomAlias().trim();
                if (!code.matches("^[a-zA-Z0-9_-]{3,30}$")) {
                    throw new BadRequestResponse("Custom alias must be 3-30 characters long and contain only alphanumeric characters, dashes, or underscores.");
                }

                Optional<Url> existing = repo.findByShortCode(code);
                if (existing.isPresent()) {
                    throw new BadRequestResponse("Custom alias is already in use.");
                }

                url.setShortCode(code);
                int generatedId = repo.insertWithoutId(url);
                url.setId(generatedId);
            } else {
                int nextId = repo.getNextVal();
                code = Base62.encode(nextId);
                url.setId(nextId);
                url.setShortCode(code);
                repo.insertWithId(url);
            }
            return code;
        });

        cache.setOriginalUrl(finalShortCode, originalUrl, url.getExpiresAt());

        UrlResponse res = new UrlResponse(
                url.getId(),
                finalShortCode,
                url.getOriginalUrl(),
                baseUrl + finalShortCode,
                Instant.now().toEpochMilli(),
                url.getExpiresAt() != null ? url.getExpiresAt().toEpochMilli() : null,
                0
        );
        ctx.json(res);
    }

    public void getMyUrls(Context ctx) {
        int ownerId = ctx.attribute("userId");

        List<UrlResponse> responses = jdbi.withHandle(handle -> {
            UrlRepository urlRepo = handle.attach(UrlRepository.class);
            List<Url> urls = urlRepo.findByOwnerId(ownerId);
            List<UrlResponse> list = new ArrayList<>();
            for (Url u : urls) {
                int clickCount = handle.createQuery("SELECT COUNT(*) FROM clicks WHERE url_id = :urlId")
                        .bind("urlId", u.getId())
                        .mapTo(Integer.class)
                        .one();

                list.add(new UrlResponse(
                        u.getId(),
                        u.getShortCode(),
                        u.getOriginalUrl(),
                        baseUrl + u.getShortCode(),
                        u.getCreatedAt().toEpochMilli(),
                        u.getExpiresAt() != null ? u.getExpiresAt().toEpochMilli() : null,
                        clickCount
                ));
            }
            return list;
        });
        ctx.json(responses);
    }

    public void delete(Context ctx) {
        int id = Integer.parseInt(ctx.pathParam("id"));
        int ownerId = ctx.attribute("userId");

        jdbi.useTransaction(handle -> {
            UrlRepository repo = handle.attach(UrlRepository.class);
            Optional<Url> urlOpt = repo.findById(id);
            if (urlOpt.isEmpty()) {
                throw new NotFoundResponse("URL not found");
            }
            Url url = urlOpt.get();
            if (url.getOwnerId() == null || url.getOwnerId() != ownerId) {
                throw new ForbiddenResponse("You do not own this URL.");
            }

            repo.delete(id, ownerId);
            cache.invalidate(url.getShortCode());
        });

        ctx.json(Map.of("message", "URL deleted successfully"));
    }

    // 9. Middlewares
    public void rateLimitShorten(Context ctx) {
        if (ctx.method().name().equalsIgnoreCase("OPTIONS")) return;
        String identifier = ctx.ip();
        Integer userId = ctx.attribute("userId");
        if (userId != null) identifier = "user:" + userId;

        if (!rateLimiter.isAllowed("shorten", identifier, 10, 60)) {
            throw new HttpResponseException(429, "Rate limit exceeded for link creation. Maximum 10 requests per minute.");
        }
    }

    public void rateLimitRedirect(Context ctx) {
        if (ctx.method().name().equalsIgnoreCase("OPTIONS")) return;
        String identifier = ctx.ip();

        if (!rateLimiter.isAllowed("redirect", identifier, 100, 60)) {
            throw new HttpResponseException(429, "Rate limit exceeded for redirects. Maximum 100 requests per minute.");
        }
    }
}
