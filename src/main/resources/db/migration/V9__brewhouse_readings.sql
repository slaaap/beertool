create table brewhouse_readings (
    id        bigserial primary key,
    batch_id  bigint not null references batches (id) on delete cascade,
    at        timestamptz not null,
    phase     text not null,
    temp_c    double precision,
    target_c  double precision,
    power_w   int
);

create index idx_brewhouse_readings_batch_at on brewhouse_readings (batch_id, at);
