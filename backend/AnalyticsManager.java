package backend;

import io.javalin.http.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.config.RegisterBeanMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AnalyticsManager {
    private static final Logger logger = LoggerFactory.getLogger(AnalyticsManager.class);
    private final Jdbi jdbi;
    private final ExecutorService executorService;
    
    private static final String[] MOCK_LOCATIONS = {
            "Mumbai, India", "New York, USA", "London, UK", "Tokyo, Japan",
            "Berlin, Germany", "Sydney, Australia", "Paris, France", "Toronto, Canada"
    };

    public AnalyticsManager() {
        this.jdbi = Config.getJdbi();
        this.executorService = Executors.newCachedThreadPool();
    }

    // 1. Click Domain Model
    public static class Click {
        private int id;
        private int urlId;
        private Instant timestamp;
        private String referrer;
        private String ipHash;
        private String userAgent;
        private String device;
        private String browser;
        private String location;

        public Click() {}

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public int getUrlId() { return urlId; }
        public void setUrlId(int urlId) { this.urlId = urlId; }
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
        public String getReferrer() { return referrer; }
        public void setReferrer(String referrer) { this.referrer = referrer; }
        public String getIpHash() { return ipHash; }
        public void setIpHash(String ipHash) { this.ipHash = ipHash; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
        public String getDevice() { return device; }
        public void setDevice(String device) { this.device = device; }
        public String getBrowser() { return browser; }
        public void setBrowser(String browser) { this.browser = browser; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
    }

    // 2. Response DTO
    public static class AnalyticsResponse {
        private int totalClicks;
        private Map<String, Integer> clicksOverTime;
        private Map<String, Integer> referrers;
        private Map<String, Integer> devices;
        private Map<String, Integer> browsers;
        private Map<String, Integer> locations;

        public AnalyticsResponse() {}

        public int getTotalClicks() { return totalClicks; }
        public void setTotalClicks(int totalClicks) { this.totalClicks = totalClicks; }
        public Map<String, Integer> getClicksOverTime() { return clicksOverTime; }
        public void setClicksOverTime(Map<String, Integer> clicksOverTime) { this.clicksOverTime = clicksOverTime; }
        public Map<String, Integer> getReferrers() { return referrers; }
        public void setReferrers(Map<String, Integer> referrers) { this.referrers = referrers; }
        public Map<String, Integer> getDevices() { return devices; }
        public void setDevices(Map<String, Integer> devices) { this.devices = devices; }
        public Map<String, Integer> getBrowsers() { return browsers; }
        public void setBrowsers(Map<String, Integer> browsers) { this.browsers = browsers; }
        public Map<String, Integer> getLocations() { return locations; }
        public void setLocations(Map<String, Integer> locations) { this.locations = locations; }
    }

    // 3. User-Agent Parsing Utility
    public static class UserAgentParser {
        public static String getDevice(String ua) {
            if (ua == null) return "Unknown";
            String lower = ua.toLowerCase();
            if (lower.contains("mobile") || lower.contains("android") || lower.contains("iphone") || lower.contains("ipad")) {
                if (lower.contains("ipad") || lower.contains("tablet")) return "Tablet";
                return "Mobile";
            }
            return "Desktop";
        }

        public static String getBrowser(String ua) {
            if (ua == null) return "Unknown";
            String lower = ua.toLowerCase();
            if (lower.contains("edg/")) return "Edge";
            if (lower.contains("chrome/") && !lower.contains("chromium")) return "Chrome";
            if (lower.contains("safari/") && lower.contains("version/")) return "Safari";
            if (lower.contains("firefox/")) return "Firefox";
            if (lower.contains("opr/") || lower.contains("opera/")) return "Opera";
            return "Other";
        }
    }

    // 4. JDBI ClickRepository DAO Mapping
    public interface ClickRepository {
        @SqlUpdate("INSERT INTO clicks(url_id, referrer, ip_hash, user_agent, device, browser, location) " +
                   "VALUES(:urlId, :referrer, :ipHash, :userAgent, :device, :browser, :location)")
        void insert(@BindBean Click click);
    }

    // 5. Click Tracking Log Logic (Redirect endpoint + Queue logging)
    public void redirectAndLog(Context ctx, UrlManager.Cache cache) {
        String shortCode = ctx.pathParam("shortCode");

        if (shortCode.equals("favicon.ico") || shortCode.equals("index.html") || shortCode.equals("dashboard.html")) {
            ctx.status(HttpStatus.NOT_FOUND);
            return;
        }

        // 1. Cache Check
        String cachedUrl = cache.getOriginalUrl(shortCode);
        if (cachedUrl != null) {
            if (cachedUrl.equals("EXPIRED")) {
                throw new NotFoundResponse("This shortened link has expired.");
            }
            ctx.redirect(cachedUrl);
            queueClick(shortCode, ctx);
            return;
        }

        // 2. Database Fallback
        Optional<UrlManager.Url> urlOpt = jdbi.withHandle(handle ->
            handle.createQuery("SELECT * FROM urls WHERE short_code = :shortCode")
                  .bind("shortCode", shortCode)
                  .mapToBean(UrlManager.Url.class)
                  .findOne()
        );

        if (urlOpt.isEmpty()) {
            throw new NotFoundResponse("Shortened link not found");
        }

        UrlManager.Url url = urlOpt.get();
        if (url.isExpired()) {
            cache.setOriginalUrl(shortCode, "EXPIRED", url.getExpiresAt());
            throw new NotFoundResponse("This shortened link has expired.");
        }

        cache.setOriginalUrl(shortCode, url.getOriginalUrl(), url.getExpiresAt());
        ctx.redirect(url.getOriginalUrl());
        queueClick(url.getId(), ctx);
    }

    private void queueClick(String shortCode, Context ctx) {
        String ip = ctx.ip();
        String referrer = ctx.header("Referer");
        String userAgent = ctx.header("User-Agent");

        executorService.submit(() -> {
            try {
                Optional<Integer> urlIdOpt = jdbi.withHandle(handle ->
                    handle.createQuery("SELECT id FROM urls WHERE short_code = :shortCode")
                          .bind("shortCode", shortCode)
                          .mapTo(Integer.class)
                          .findOne()
                );
                urlIdOpt.ifPresent(urlId -> logClick(urlId, ip, referrer, userAgent));
            } catch (Exception e) {
                logger.error("Error logging click asynchronously for shortCode: " + shortCode, e);
            }
        });
    }

    private void queueClick(int urlId, Context ctx) {
        String ip = ctx.ip();
        String referrer = ctx.header("Referer");
        String userAgent = ctx.header("User-Agent");

        executorService.submit(() -> {
            try {
                logClick(urlId, ip, referrer, userAgent);
            } catch (Exception e) {
                logger.error("Error logging click asynchronously for URL id: " + urlId, e);
            }
        });
    }

    private void logClick(int urlId, String ip, String referrer, String userAgent) {
        String ipHash = hashIp(ip);
        String device = UserAgentParser.getDevice(userAgent);
        String browser = UserAgentParser.getBrowser(userAgent);
        String location = resolveLocation(ip);

        Click click = new Click();
        click.setUrlId(urlId);
        click.setReferrer(referrer);
        click.setIpHash(ipHash);
        click.setUserAgent(userAgent);
        click.setDevice(device);
        click.setBrowser(browser);
        click.setLocation(location);

        jdbi.useTransaction(handle -> {
            ClickRepository repo = handle.attach(ClickRepository.class);
            repo.insert(click);
        });
    }

    private String hashIp(String ip) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(ip.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception ex) {
            return "hash-failed-" + (ip != null ? ip.hashCode() : "null");
        }
    }

    private String resolveLocation(String ip) {
        if (ip == null) {
            return "Unknown";
        }
        String cleanIp = ip.trim().replace("[", "").replace("]", "");
        if (cleanIp.equals("127.0.0.1") || 
            cleanIp.equals("0:0:0:0:0:0:0:1") || 
            cleanIp.equals("::1") || 
            cleanIp.startsWith("192.168.") || 
            cleanIp.startsWith("10.") || 
            cleanIp.startsWith("172.16.") || 
            cleanIp.startsWith("172.17.") || 
            cleanIp.startsWith("172.18.") || 
            cleanIp.startsWith("172.19.") || 
            cleanIp.startsWith("172.2") || 
            cleanIp.startsWith("172.3") || 
            cleanIp.startsWith("fe80:")) {
            return "Localhost";
        }
        int hash = Math.abs(cleanIp.hashCode());
        return MOCK_LOCATIONS[hash % MOCK_LOCATIONS.length];
    }

    // 6. Aggregate analytics retrieval API Handler
    public void getAnalytics(Context ctx) {
        String shortCode = ctx.pathParam("shortCode");
        int userId = ctx.attribute("userId");

        jdbi.useTransaction(handle -> {
            // Verify link owner
            Optional<UrlManager.Url> urlOpt = handle.createQuery("SELECT * FROM urls WHERE short_code = :shortCode")
                    .bind("shortCode", shortCode)
                    .mapToBean(UrlManager.Url.class)
                    .findOne();

            if (urlOpt.isEmpty()) {
                throw new NotFoundResponse("Shortened link not found");
            }
            UrlManager.Url url = urlOpt.get();
            if (url.getOwnerId() == null || url.getOwnerId() != userId) {
                throw new ForbiddenResponse("You do not own this URL.");
            }

            int urlId = url.getId();

            AnalyticsResponse res = new AnalyticsResponse();
            
            // Query 1: Total Clicks
            int totalClicks = handle.createQuery("SELECT COUNT(*) FROM clicks WHERE url_id = :urlId")
                    .bind("urlId", urlId)
                    .mapTo(Integer.class)
                    .one();
            res.setTotalClicks(totalClicks);

            // Query 2: Clicks Over Time (Last 7 Days)
            Instant since = Instant.now().minus(7, ChronoUnit.DAYS);
            List<Map<String, Object>> clicksOverTimeRows = handle.createQuery(
                    "SELECT DATE(click_timestamp) as d, COUNT(*) as c " +
                    "FROM clicks " +
                    "WHERE url_id = :urlId AND click_timestamp >= :since " +
                    "GROUP BY DATE(click_timestamp) " +
                    "ORDER BY DATE(click_timestamp) ASC")
                    .bind("urlId", urlId)
                    .bind("since", since)
                    .mapToMap()
                    .list();

            Map<String, Integer> clicksOverTime = new LinkedHashMap<>();
            // Fill past 7 days with zeros first to make charts render continuously
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            for (int i = 6; i >= 0; i--) {
                clicksOverTime.put(LocalDate.now().minusDays(i).format(formatter), 0);
            }

            for (Map<String, Object> row : clicksOverTimeRows) {
                Object d = row.get("d");
                String dateKey = d.toString();
                Number countVal = (Number) row.get("c");
                clicksOverTime.put(dateKey, countVal.intValue());
            }
            res.setClicksOverTime(clicksOverTime);

            // Query 3: Top Referrers
            List<Map<String, Object>> referrerRows = handle.createQuery(
                    "SELECT referrer as r, COUNT(*) as c " +
                    "FROM clicks " +
                    "WHERE url_id = :urlId " +
                    "GROUP BY referrer " +
                    "ORDER BY c DESC LIMIT 5")
                    .bind("urlId", urlId)
                    .mapToMap()
                    .list();
            Map<String, Integer> referrers = new LinkedHashMap<>();
            for (Map<String, Object> row : referrerRows) {
                Object rVal = row.get("r");
                String label = rVal != null ? rVal.toString() : "Direct";
                Number countVal = (Number) row.get("c");
                referrers.put(label, countVal.intValue());
            }
            res.setReferrers(referrers);

            // Query 4: Devices
            List<Map<String, Object>> deviceRows = handle.createQuery(
                    "SELECT device as d, COUNT(*) as c " +
                    "FROM clicks " +
                    "WHERE url_id = :urlId " +
                    "GROUP BY device " +
                    "ORDER BY c DESC")
                    .bind("urlId", urlId)
                    .mapToMap()
                    .list();
            Map<String, Integer> devices = new LinkedHashMap<>();
            for (Map<String, Object> row : deviceRows) {
                Object label = row.get("d");
                Number countVal = (Number) row.get("c");
                devices.put(label != null ? label.toString() : "Unknown", countVal.intValue());
            }
            res.setDevices(devices);

            // Query 5: Browsers
            List<Map<String, Object>> browserRows = handle.createQuery(
                    "SELECT browser as b, COUNT(*) as c " +
                    "FROM clicks " +
                    "WHERE url_id = :urlId " +
                    "GROUP BY browser " +
                    "ORDER BY c DESC")
                    .bind("urlId", urlId)
                    .mapToMap()
                    .list();
            Map<String, Integer> browsers = new LinkedHashMap<>();
            for (Map<String, Object> row : browserRows) {
                Object label = row.get("b");
                Number countVal = (Number) row.get("c");
                browsers.put(label != null ? label.toString() : "Unknown", countVal.intValue());
            }
            res.setBrowsers(browsers);

            // Query 6: Locations
            List<Map<String, Object>> locationRows = handle.createQuery(
                    "SELECT location as l, COUNT(*) as c " +
                    "FROM clicks " +
                    "WHERE url_id = :urlId " +
                    "GROUP BY location " +
                    "ORDER BY c DESC LIMIT 5")
                    .bind("urlId", urlId)
                    .mapToMap()
                    .list();
            Map<String, Integer> locations = new LinkedHashMap<>();
            for (Map<String, Object> row : locationRows) {
                Object label = row.get("l");
                Number countVal = (Number) row.get("c");
                locations.put(label != null ? label.toString() : "Unknown", countVal.intValue());
            }
            res.setLocations(locations);

            ctx.json(res);
        });
    }

    public void shutdown() {
        executorService.shutdown();
    }
}
