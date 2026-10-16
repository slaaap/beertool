create table api_tokens (
    id           bigserial primary key,
    user_id      bigint not null references users (id) on delete cascade,
    name         text not null,
    token_hash   text not null unique,
    created_at   timestamptz not null,
    last_used_at timestamptz
);

create index api_tokens_user_id on api_tokens (user_id);
