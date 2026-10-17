create table oauth_grants (
    id                 bigserial primary key,
    user_id            bigint not null references users (id) on delete cascade,
    client_id          text not null,
    client_name        text not null,
    scope              text not null,
    resource           text not null,
    refresh_hash       text unique,
    refresh_expires_at timestamptz,
    created_at         timestamptz not null,
    last_used_at       timestamptz
);

create index oauth_grants_user_id on oauth_grants (user_id);

create table oauth_codes (
    code_hash      text primary key,
    user_id        bigint not null references users (id) on delete cascade,
    client_id      text not null,
    client_name    text not null,
    redirect_uri   text not null,
    code_challenge text not null,
    scope          text not null,
    resource       text not null,
    expires_at     timestamptz not null
);

create table oauth_access_tokens (
    token_hash text primary key,
    grant_id   bigint not null references oauth_grants (id) on delete cascade,
    expires_at timestamptz not null
);
