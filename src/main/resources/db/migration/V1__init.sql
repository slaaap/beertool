create table users (
    id            bigserial primary key,
    email         text not null unique,
    password_hash text not null,
    display_name  text not null,
    created_at    timestamptz not null,
    preferences   jsonb not null default '{}'::jsonb
);

create table recipes (
    id            bigserial primary key,
    user_id       bigint not null references users (id) on delete cascade,
    no            int not null,
    name          text not null,
    style         text,
    description   text,

    pre_boil_volume_l   numeric(6, 2),
    post_boil_volume_l  numeric(6, 2) not null,
    fermenter_volume_l  numeric(6, 2) not null,
    efficiency    numeric(4, 3) not null,
    boil_time_min int not null,
    created_at    timestamptz not null,
    updated_at    timestamptz not null,
    search_doc    tsvector,
    unique (user_id, no)
);

create table recipe_fermentables (
    id              bigserial primary key,
    recipe_id       bigint not null references recipes (id) on delete cascade,
    name            text not null,
    type            text not null,
    amount_kg       numeric(7, 3) not null,
    colour_ebc      numeric(6, 2),

    extract_percent numeric(4, 1),

    usage           text not null,
    boil_time_min   int
);

create table recipe_hops (
    id            bigserial primary key,
    recipe_id     bigint not null references recipes (id) on delete cascade,
    name          text not null,
    amount_g      numeric(7, 2) not null,
    alpha_acid    numeric(4, 2) not null,
    boil_time_min int,
    usage         text not null
);

create table recipe_yeasts (
    id          bigserial primary key,
    recipe_id   bigint not null references recipes (id) on delete cascade,
    name        text not null,
    attenuation numeric(4, 3) not null,

    usage       text not null
);

create table recipe_mash_steps (
    id        bigserial primary key,
    recipe_id bigint not null references recipes (id) on delete cascade,
    temp_c    numeric(4, 1) not null,
    time_min  int           not null
);

create index idx_mash_steps_recipe on recipe_mash_steps (recipe_id);

create table recipe_extras (
    id            bigserial primary key,
    recipe_id     bigint not null references recipes (id) on delete cascade,
    name          text not null,
    amount        numeric(9, 3) not null,
    unit          text not null,
    usage         text not null,
    boil_time_min int
);

create table batches (
    id            bigserial primary key,
    recipe_id     bigint not null references recipes (id) on delete cascade,
    user_id       bigint not null references users (id) on delete cascade,
    no            int not null,
    brew_date     date,
    packaged_date date,
    measured_og   numeric(5, 3),
    measured_fg   numeric(5, 3),

    measured_pre_boil_volume_l  numeric(6, 2),
    measured_post_boil_volume_l numeric(6, 2),
    measured_fermenter_volume_l numeric(6, 2),

    abv           numeric(4, 2),

    mash_efficiency numeric(4, 3),
    notes         text,
    created_at    timestamptz not null,
    unique (recipe_id, no)
);

create function recipe_search_doc(rid bigint) returns tsvector
    language sql
    stable
as
$$
select setweight(to_tsvector('english', coalesce(r.name, '')), 'A')
           || setweight(to_tsvector('english', coalesce(r.style, '')), 'B')
           || setweight(to_tsvector('english', coalesce(
        (select string_agg(f.name, ' ') from recipe_fermentables f where f.recipe_id = r.id), '')), 'B')
           || setweight(to_tsvector('english', coalesce(
        (select string_agg(h.name, ' ') from recipe_hops h where h.recipe_id = r.id), '')), 'B')
           || setweight(to_tsvector('english', coalesce(
        (select string_agg(y.name, ' ') from recipe_yeasts y where y.recipe_id = r.id), '')), 'C')
           || setweight(to_tsvector('english', coalesce(
        (select string_agg(e.name, ' ') from recipe_extras e where e.recipe_id = r.id), '')), 'C')
           || setweight(to_tsvector('english', coalesce(r.description, '')), 'D')
from recipes r
where r.id = rid;
$$;

create index idx_recipes_user on recipes (user_id);
create index idx_recipes_search on recipes using gin (search_doc);
create index idx_batches_recipe on batches (recipe_id);
create index idx_batches_user on batches (user_id);
create index idx_fermentables_recipe on recipe_fermentables (recipe_id);
create index idx_hops_recipe on recipe_hops (recipe_id);
create index idx_yeasts_recipe on recipe_yeasts (recipe_id);
create index idx_extras_recipe on recipe_extras (recipe_id);
