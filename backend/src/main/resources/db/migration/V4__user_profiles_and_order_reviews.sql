CREATE TABLE user_profiles (
    user_id VARCHAR(36) PRIMARY KEY,
    avatar_id VARCHAR(36),
    school VARCHAR(80) NOT NULL DEFAULT '',
    campus VARCHAR(80) NOT NULL DEFAULT '',
    bio VARCHAR(300) NOT NULL DEFAULT '',
    gender VARCHAR(8) NOT NULL DEFAULT '',
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (avatar_id) REFERENCES files(id),
    CHECK (gender IN ('', 'MALE', 'FEMALE', 'OTHER'))
);

CREATE TABLE order_reviews (
    id VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    reviewer_id VARCHAR(36) NOT NULL,
    subject_id VARCHAR(36) NOT NULL,
    subject_role VARCHAR(6) NOT NULL,
    stars TINYINT NOT NULL,
    comment VARCHAR(500) NOT NULL,
    submitted_at BIGINT NOT NULL,
    published_at BIGINT,
    FOREIGN KEY (order_id) REFERENCES orders(id),
    FOREIGN KEY (reviewer_id) REFERENCES users(id),
    FOREIGN KEY (subject_id) REFERENCES users(id),
    UNIQUE (order_id, reviewer_id),
    CHECK (reviewer_id <> subject_id),
    CHECK (subject_role IN ('BUYER', 'SELLER')),
    CHECK (stars BETWEEN 1 AND 5),
    INDEX idx_reviews_subject_public (subject_id, published_at, id)
);
