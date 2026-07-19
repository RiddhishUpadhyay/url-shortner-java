package backend;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import javax.sql.DataSource;
import java.io.InputStream;
import java.util.Properties;

public class Config {
    private static final Properties properties = new Properties();
    private static HikariDataSource dataSource;
    private static Jdbi jdbi;
    private static JedisPool jedisPool;

    static {
        try (InputStream input = Config.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (Exception ex) {
            System.err.println("Warning: failed to load application.properties: " + ex.getMessage());
        }
    }

    public static String get(String key, String defaultValue) {
        String envKey = key.replace('.', '_').toUpperCase();
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue;
        }
        return properties.getProperty(key, defaultValue);
    }

    public static int getInt(String key, int defaultValue) {
        String val = get(key, null);
        return val != null ? Integer.parseInt(val) : defaultValue;
    }

    public static synchronized DataSource getDataSource() {
        if (dataSource == null) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(get("db.url", "jdbc:postgresql://localhost:5432/url_shortener"));
            config.setUsername(get("db.user", "postgres"));
            config.setPassword(get("db.password", "postgres"));
            config.setMaximumPoolSize(10);
            config.setDriverClassName("org.postgresql.Driver");
            dataSource = new HikariDataSource(config);
        }
        return dataSource;
    }

    public static synchronized Jdbi getJdbi() {
        if (jdbi == null) {
            jdbi = Jdbi.create(getDataSource());
            jdbi.installPlugin(new SqlObjectPlugin());
        }
        return jdbi;
    }

    public static synchronized JedisPool getJedisPool() {
        if (jedisPool == null) {
            String host = get("redis.host", "localhost");
            int port = getInt("redis.port", 6379);
            String password = get("redis.password", null);
            if (password != null && password.trim().isEmpty()) {
                password = null;
            }

            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(10);
            poolConfig.setMaxIdle(5);
            poolConfig.setMinIdle(1);

            if (password != null) {
                // Connect with password (timeout = 2000ms)
                jedisPool = new JedisPool(poolConfig, host, port, 2000, password);
            } else {
                jedisPool = new JedisPool(poolConfig, host, port);
            }
        }
        return jedisPool;
    }

    public static void close() {
        if (dataSource != null) {
            dataSource.close();
        }
        if (jedisPool != null) {
            jedisPool.close();
        }
    }
}
