CREATE SEQUENCE IF NOT EXISTS urls_id_seq START WITH 100000000;

CREATE TABLE IF NOT EXISTS urls (
    id INT PRIMARY KEY DEFAULT nextval('urls_id_seq'),
    short_code VARCHAR(50) UNIQUE NOT NULL,
    original_url TEXT NOT NULL,
    owner_id INT REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_urls_short_code ON urls(short_code);
