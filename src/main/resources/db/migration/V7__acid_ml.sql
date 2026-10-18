update recipes
set water = (water - 'lacticMl') || jsonb_build_object('acidMl', water -> 'lacticMl')
where jsonb_exists(water, 'lacticMl');

update batches
set water = jsonb_set(water, '{water}', ((water -> 'water') - 'lacticMl') || jsonb_build_object('acidMl', water -> 'water' -> 'lacticMl'))
where jsonb_exists(water -> 'water', 'lacticMl');
