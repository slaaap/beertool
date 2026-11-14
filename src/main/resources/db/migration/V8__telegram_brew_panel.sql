create table telegram_brew_sessions (
    batch_id           bigint primary key references batches (id) on delete cascade,
    user_id            bigint not null references users (id) on delete cascade,
    chat_id            bigint not null,
    panel_message_id   bigint,
    stage              text not null,
    mash_step          int not null default 0,
    timer              text,
    timer_ends_at      timestamptz,
    announced_down_to  int,
    panel_refreshed_at timestamptz
);

insert into telegram_brew_sessions (batch_id, user_id, chat_id, stage, timer, timer_ends_at, announced_down_to)
select batch_id, user_id, chat_id, 'BOIL', 'BOIL', ends_at, announced_down_to from telegram_boil_timers;

drop table telegram_boil_timers;

create table telegram_reading_prompts (
    telegram_user_id bigint not null references telegram_links (telegram_user_id) on delete cascade,
    message_id       bigint not null,
    batch_id         bigint not null references batches (id) on delete cascade,
    reading          text not null,
    asked_at         timestamptz not null,
    primary key (telegram_user_id, message_id)
);
