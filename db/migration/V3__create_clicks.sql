CREATE TABLE IF NOT EXISTS clicks (
    id SERIAL PRIMARY KEY,
    url_id INT NOT NULL REFERENCES urls(id) ON DELETE CASCADE,
    click_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    referrer TEXT,
    ip_hash VARCHAR(64) NOT NULL,
    user_agent TEXT,
    device VARCHAR(50),
    browser VARCHAR(50),
    location VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS idx_clicks_url_id ON clicks(url_id);
