# 🔗 SnipLink - High-Performance URL Shortener & Analytics

[![Live Demo](https://img.shields.io/badge/Live_Demo-Render-brightgreen?style=for-the-badge&logo=render)](https://url-shortner-u052.onrender.com/)
[![Java](https://img.shields.io/badge/Java-17-orange?style=for-the-badge&logo=java)](https://www.oracle.com/java/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue?style=for-the-badge&logo=postgresql)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-Cache-red?style=for-the-badge&logo=redis)](https://redis.io/)

> 🌐 **Live Web Application**: [https://url-shortner-u052.onrender.com/](https://url-shortner-u052.onrender.com/)

---

## 📌 Overview

**SnipLink** is a full-stack URL shortening service built in **Java** with fast startup time. It features real-time click tracking, rate limiting, and an interactive frontend.

---

## ✨ Features

- ⚡ **Base62 URL Encoding**: Generates unique, non-sequential, short aliases.
- 🎨 **Custom Aliases & Expirations**: Set custom link names and optional link expiration dates.
- 🚀 **DB-Free Fast Redirection**: Caches link targets in Redis for fast redirects without hitting PostgreSQL.
- 📊 **Real-Time Click Analytics**: Track total clicks, clicks over time, referrers, browsers, devices, and visitor locations.
- ⚡ **Async Metrics Pipeline**: Redirects users instantly (`302 Found`) while logging analytics asynchronously in background worker threads.
- 🛡️ **Sliding Window Rate Limiting**: Protects shortening and redirection endpoints against spam using Redis atomic transactions.
- 🔐 **JWT & Password Security**: Secure account creation with BCrypt password hashing and JWT authentication tokens.

---

## 🏗️ How System Works

### 1. Shortening a URL
```text
[ User Input URL ]
        │
        ▼
[ Generate ID ] ──► [ Convert to Base62 ]
        │
        ├───────────────────────┐
        ▼                       ▼
 [ Save in PostgreSQL ]   [ Cache in Redis ]
```

### 2. Visitor Redirect & Analytics
```text
[ Visitor Clicks Link ]
        │
        ├─► 1. Check Redis Cache ──► [ Instantly Redirect User (302) ]
        │
        └─► 2. Queue Async Job   ──► [ Log Browser, Device, Location to PostgreSQL ]
```

---

## 🛠️ Tech Stack

| Component | Technology |
| :--- | :--- |
| **Language** | Java 17 |
| **Web Framework** | Javalin (Lightweight REST framework on Jetty) |
| **Database** | PostgreSQL 16 (via JDBI 3) |
| **Cache & Limiter** | Redis (Jedis client) |
| **Security** | Auth0 JWT + BCrypt |
| **Frontend** | Vanilla HTML5 / CSS3 (Glassmorphism) + Chart.js |
| **Cloud Hosting** | Render (Docker Container) + Neon (PostgreSQL) + Upstash (Redis) |

---

## 📂 Project Structure

```text
url-shortner-java/
├── backend/                  # Core Java backend code
│   ├── Main.java             # Server entrypoint & route setup
│   ├── Config.java           # Connections (Postgres & Redis)
│   ├── AuthManager.java      # User login, sign-up & JWT auth
│   ├── UrlManager.java       # Shortening logic, Base62, Redis cache
│   └── AnalyticsManager.java # Click tracking & analytics data
│
├── frontend/                 # Web interface files
│   ├── index.html            # URL shortening page
│   ├── dashboard.html        # Analytics visual dashboard
│   ├── css/style.css         # Glassmorphic dark styling
│   └── js/                   # Frontend logic & chart renderers
│
├── db/migration/             # SQL database migration scripts
│   ├── V1__create_users.sql
│   ├── V2__create_urls.sql
│   └── V3__create_clicks.sql
│
├── application.properties.example # Template settings configuration
├── pom.xml                   # Maven build configuration
├── Dockerfile                # Multi-stage Docker packaging file
└── README.md
```

---

## 🚀 How to Run Locally

### 1. Prerequisites
- **Java 17** or higher installed
- **PostgreSQL** database running on port `5432`
- **Redis** server running on port `6379`

### 2. Environment Configuration
Copy the configuration template:
```bash
cp application.properties.example application.properties
```
Fill in your local database credentials inside `application.properties`.

### 3. Build & Run
Compile the code and launch the server:
```bash
mvn clean package -DskipTests
java -jar target/url-shortener-java-1.0-SNAPSHOT.jar
```
Open **[http://localhost:8080](http://localhost:8080)** in your web browser.

---

## 🌐 Live Deployment Link

Access the live hosted version anytime at:
👉 **[https://url-shortner-u052.onrender.com/](https://url-shortner-u052.onrender.com/)**
