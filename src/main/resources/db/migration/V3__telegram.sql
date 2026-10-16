create table telegram_links (
    telegram_user_id bigint primary key,
    user_id          bigint not null unique references users (id) on delete cascade,
    chat_id          bigint not null,
    current_batch_id bigint references batches (id) on delete set null,
    linked_at        timestamptz not null
);

create table telegram_link_tokens (
    token_hash text primary key,
    user_id    bigint not null references users (id) on delete cascade,
    expires_at timestamptz not null
);

create table telegram_boil_timers (
    batch_id         bigint primary key references batches (id) on delete cascade,
    user_id          bigint not null references users (id) on delete cascade,
    chat_id          bigint not null,
    ends_at          timestamptz not null,
    announced_down_to int not null
);
