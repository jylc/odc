-- add column database_id to script_meta, records the connected database
-- (and thus its datasource) of the SQL window when the script was saved
alter table `script_meta`
add column `database_id` bigint default null
comment 'id of the connect_database record the script was saved from';
