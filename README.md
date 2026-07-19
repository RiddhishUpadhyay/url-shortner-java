# SnipLink - High-Performance URL Shortener & Analytics

SnipLink is a lightweight, high-performance URL shortening service written in Java (Javalin) with PostgreSQL, Redis, and a modern glassmorphic web dashboard with real-time Chart.js visual analytics.

---

## 🎯 Problem Statement
Building a production-ready URL shortener is more than just a CRUD application. Redirection endpoints get hit far more frequently than link-creation endpoints, introducing major engineering challenges:
1. **Redirection Latency**: Querying a relational database (PostgreSQL) for every single redirect is slow and doesn't scale under heavy traffic.
2. **Analytics Bottleneck**: Logging click parameters (timestamp, referrer, device, browser, approximate location) synchronously blocks the client's HTTP response, slowing down the redirection experience.
3. **Short-Code Collisions**: Generating random hashes (like MD5) can lead to collisions, while running search-and-insert loops wastes database cycles.
4. **Abuse Prevention**: Unauthenticated users or scrapers can spam link creation or hammer redirects, consuming resources.

---

## ⚙️ Core Architecture & System Flow

### 1. Link Shortening Flow
```text
[User Client] ──( POST Long URL )──► [Javalin Server]
                                           │
                                  (Query Sequence ID)
                                           ▼
                                    [PostgreSQL]
                                           │
                                  (Base62 Encoding)
                                           ▼
                                 [Unique Short Code]
                                           │
                        ┌──────────────────┴──────────────────┐
                        ▼                                     ▼
             [Cache in Redis]                      [Save in PostgreSQL]
        (originalUrl|expiryTimestamp)
```

### 2. Redirect & Analytics Flow
```text
[Visitor] ──( GET /6LAzx )──► [Javalin Server] ──( 302 Redirect )──► [Target URL]
                                    │
                         (Check Cache in Redis)
                                    ├───────────► [Cache Hit] (Instantly redirects DB-free)
                                    └───────────► [Cache Miss] (Reads DB, caches, redirects)
                                    │
                      (Async Handoff to Executor Pool)
                                    ▼
                         [Log Click parameters]
                     (Hashed IP, Browser, Device,
                       Location, Referrer, Time)
                                    ▼
                               [PostgreSQL]
```

* **Cache-Aside Redirection**: Redirection targets are cached in Redis. The cache key includes expiration metadata, enabling **100% database-free redirects** even for links with custom expiry limits.
* **Asynchronous Metrics Processing**: When a redirect occurs, the client receives an immediate `302 Found` HTTP redirect response. The database log task is handed over to a background thread pool (`ExecutorService`) to avoid adding milliseconds to the redirect request.
* **Sliding Window Rate Limiting**: Implemented atomically in Redis using sorted sets (`ZSet`) inside transaction pipelines (`MULTI`/`EXEC`), protecting both shortening and redirect paths from traffic spikes.

---

## 🛠️ Tech Stack
* **Language/Framework**: Java 17 + Javalin (an ultra-lightweight REST framework running on Jetty, bootstrapping in <300ms)
* **Database**: PostgreSQL 16
* **Database Access**: JDBI 3 (lightweight SQL object binder)
* **In-Memory Cache & Limiter**: Redis (Jedis)
* **Security & Auth**: JWT (java-jwt) + BCrypt (password hashing)
* **Frontend**: Glassmorphic HTML5 & CSS3 + Chart.js (Interactive visual graphs)

---

## 📂 Folder Structure
The codebase uses a **package-by-feature** directory layout, collapsing boilerplate micro-segmented structures into clean, logical managers at the root level:

```text
url-shortener-java/
├── backend/                  # Direct Java source files (package backend)
│   ├── Main.java             # Entrypoint, route mappings, and database migrations
│   ├── Config.java           # Property management, database & Redis connection pools
│   ├── AuthManager.java      # User models, password hashing, JWTs, and auth handlers
│   ├── UrlManager.java       # URL models, Base62 encoding, caches, rate limiters, and handlers
│   └── AnalyticsManager.java # Click models, User-Agent parser, async metrics, and charts API
│
├── frontend/                 # Direct web user interface files
│   ├── index.html            # Shortener landing UI
│   ├── dashboard.html        # Interactive Chart.js analytics visualizer
│   ├── css/style.css         # Custom dark theme variables & glassmorphic styling
│   └── js/
│       ├── app.js            # User registration/login & shortening API handlers
│       └── dashboard.js      # Analytics charts retriever and renderer
│
├── db/migration/             # Re-run safe raw SQL migrations
│   ├── V1__create_users.sql
│   ├── V2__create_urls.sql
│   └── V3__create_clicks.sql
│
├── application.properties.example # Template settings configuration
├── pom.xml                   # Maven dependencies and build instructions
├── Dockerfile                # Multi-stage Docker package file
├── docker-compose.yml        # Docker orchestrator (Postgres + Redis + App container)
└── README.md
```

---

## 🚀 Getting Started

### 1. Prerequisites
- **Java Development Kit (JDK) 17** or higher
- **PostgreSQL** database server running locally
- **Redis** server running locally

### 2. Configuration Setup
Create your configuration file by copying the template:
```bash
cp application.properties.example application.properties
```
*Note: `application.properties` is listed in `.gitignore` and will never be pushed to GitHub to prevent exposing local passwords.*

Open `application.properties` and fill in your local Postgres and Redis connection credentials:
```properties
server.port=8080
base.url=http://localhost:8080/

db.url=jdbc:postgresql://localhost:5432/url_shortener
db.user=your_postgres_username
db.password=your_postgres_password

redis.host=localhost
redis.port=6379

jwt.secret=make-a-strong-jwt-secret-key
```

### 3. Compile and Package
Compile the codebase and bundle the libraries into a shaded executable JAR using Maven:
```bash
mvn clean package -DskipTests
```

### 4. Run the Application
Launch the compiled package:
```bash
java -jar target/url-shortener-java-1.0-SNAPSHOT.jar
```
The server will boot up, execute database migrations automatically, and start listening on port `8080`. Open **[http://localhost:8080](http://localhost:8080)** in your browser!

---

## 🐳 Docker Composition Deployment
To run the entire ecosystem (Postgres + Redis + Java App container) in one click without configuring local databases:
```bash
docker compose up --build -d
```
The app container implements health checks and waits for PostgreSQL and Redis to initialize before launching.
