package backend;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        logger.info("Starting URL Shortener service...");

        // 1. Run database migrations
        runDatabaseMigrations();

        // Load HTML resources for explicit routing
        String loadedIndexHtml;
        String loadedDashboardHtml;
        try {
            loadedIndexHtml = readResource("frontend/index.html");
            loadedDashboardHtml = readResource("frontend/dashboard.html");
        } catch (Exception e) {
            logger.error("Failed to load static HTML resources from classpath", e);
            loadedIndexHtml = "<h1>Error: HTML files missing in package</h1>";
            loadedDashboardHtml = "<h1>Error: HTML files missing in package</h1>";
        }
        final String indexHtml = loadedIndexHtml;
        final String dashboardHtml = loadedDashboardHtml;

        // 2. Initialize Managers
        UrlManager.Cache cache = new UrlManager.Cache();
        UrlManager.RateLimiter rateLimiter = new UrlManager.RateLimiter();

        AuthManager authManager = new AuthManager();
        UrlManager urlManager = new UrlManager(cache, rateLimiter);
        AnalyticsManager analyticsManager = new AnalyticsManager();

        // 3. Bootstrap Javalin Server
        int port = Config.getInt("server.port", 8080);
        Javalin app = Javalin.create(config -> {
            config.staticFiles.add(staticFilesConfig -> {
                staticFilesConfig.hostedPath = "/";
                staticFilesConfig.directory = "/frontend";
                staticFilesConfig.location = Location.CLASSPATH;
            });
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
        });

        // 4. Register Routes and Middleware Interceptors
        
        // Root page routes (precede wildcard redirect to allow static serving)
        app.get("/", ctx -> ctx.html(indexHtml));
        app.get("/index.html", ctx -> ctx.html(indexHtml));
        app.get("/dashboard.html", ctx -> ctx.html(dashboardHtml));
        app.get("/favicon.ico", ctx -> ctx.status(404));

        // CORS Option support
        app.options("/*", ctx -> ctx.status(200));

        // Public Auth APIs
        app.post("/api/auth/register", authManager::register);
        app.post("/api/auth/login", authManager::login);

        // Protected Url Operations
        app.before("/api/urls/*", AuthManager::authenticate);
        app.before("/api/urls/*", urlManager::rateLimitShorten);
        app.post("/api/urls/shorten", urlManager::shorten);
        app.get("/api/urls/my-urls", urlManager::getMyUrls);
        app.delete("/api/urls/{id}", urlManager::delete);

        // Protected Analytics API
        app.before("/api/analytics/{shortCode}", AuthManager::authenticate);
        app.get("/api/analytics/{shortCode}", analyticsManager::getAnalytics);

        // Redirect Handler with Rate Limiting
        app.before("/{shortCode}", urlManager::rateLimitRedirect);
        app.get("/{shortCode}", ctx -> analyticsManager.redirectAndLog(ctx, cache));

        // 5. Start Server
        app.start(port);
        logger.info("URL Shortener application is running on port {}", port);

        // 6. Shutdown Hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Stopping application...");
            app.stop();
            analyticsManager.shutdown();
            Config.close();
            logger.info("Application stopped.");
        }));
    }

    private static void runDatabaseMigrations() {
        logger.info("Executing database migrations...");
        Jdbi jdbi = Config.getJdbi();
        jdbi.useHandle(handle -> {
            try {
                // Rerun-safe execution of migration files
                handle.execute(readResource("db/migration/V1__create_users.sql"));
                handle.execute(readResource("db/migration/V2__create_urls.sql"));
                handle.execute(readResource("db/migration/V3__create_clicks.sql"));
            } catch (Exception e) {
                logger.error("Failed to run SQL migrations", e);
                throw new RuntimeException(e);
            }
        });
        logger.info("Database migrations completed successfully.");
    }

    private static String readResource(String path) throws Exception {
        try (InputStream is = Main.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new java.io.FileNotFoundException("Resource file not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
