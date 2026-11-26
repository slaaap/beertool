create table brewhouse_keys (
    user_id      bigint primary key references users (id) on delete cascade,
    key_hash     text not null unique,
    created_at   timestamptz not null,
    last_used_at timestamptz
);
