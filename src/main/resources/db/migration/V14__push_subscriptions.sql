create table push_subscriptions (
    endpoint   text primary key,
    user_id    bigint not null references users (id) on delete cascade,
    p256dh     text not null,
    auth       text not null,
    created_at timestamptz not null
);

create index idx_push_subscriptions_user on push_subscriptions (user_id);
