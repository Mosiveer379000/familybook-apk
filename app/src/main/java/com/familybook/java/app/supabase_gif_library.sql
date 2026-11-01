-- "GIF Animated" লাইব্রেরি (Say good / Silence! পোস্ট স্ক্রিন)
-- Supabase → SQL Editor-এ একবার চালান (আবার চালালেও সমস্যা নেই)।
-- সবাই আপলোড হওয়া GIF দেখতে পারে; শুধু masudd16@gmail.com আপলোড করতে পারে; সর্বোচ্চ 1MB।

create table if not exists public.gif_library (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null default auth.uid(),
  key         text not null,                 -- R2 key
  name        text not null default 'example.gif',
  size_bytes  integer not null check (size_bytes > 0 and size_bytes <= 1048576),
  created_at  timestamptz not null default now()
);

alter table public.gif_library enable row level security;

drop policy if exists gif_library_read on public.gif_library;
create policy gif_library_read on public.gif_library
  for select to authenticated using (true);

drop policy if exists gif_library_admin_insert on public.gif_library;
create policy gif_library_admin_insert on public.gif_library
  for insert to authenticated
  with check (lower(auth.jwt() ->> 'email') = 'masudd16@gmail.com');

drop policy if exists gif_library_admin_delete on public.gif_library;
create policy gif_library_admin_delete on public.gif_library
  for delete to authenticated
  using (lower(auth.jwt() ->> 'email') = 'masudd16@gmail.com');
