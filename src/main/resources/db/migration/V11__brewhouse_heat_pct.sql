-- The API reports how hard the controller heats as a percentage, which fits any heater rather than a hob's watts.
alter table brewhouse_readings drop column power_w;
alter table brewhouse_readings add column heat_pct int;
