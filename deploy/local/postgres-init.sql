-- Database-per-service: each service owns its schema and credentials. No shared tables.
create user orders with password 'orders';
create user inventory with password 'inventory';
create user payment with password 'payment';
create user recon with password 'recon';

create database orderdb owner orders;
create database inventorydb owner inventory;
create database paymentdb owner payment;
create database recondb owner recon;

-- The reconciliation batch gets READ-ONLY access to orders (a read replica in AWS).
\connect orderdb
grant connect on database orderdb to recon;
grant usage on schema public to recon;
alter default privileges for role orders in schema public grant select on tables to recon;
