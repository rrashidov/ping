CREATE TABLE ping_result (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    pinged_at       DATETIME NOT NULL,
    success         BOOLEAN  NOT NULL,
    response_time_ms BIGINT  NULL
);

CREATE INDEX idx_ping_result_pinged_at ON ping_result (pinged_at);
