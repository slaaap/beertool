create table brewhouse_widgets (
    user_id    bigint primary key references users (id) on delete cascade,
    html       text not null,
    updated_at timestamptz not null
);
