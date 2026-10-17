alter table recipes
    add column water jsonb;

alter table batches
    add column water jsonb;
