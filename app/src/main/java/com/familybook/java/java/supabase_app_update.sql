-- অটো-আপডেট: Supabase SQL Editor-এ একবার রান করুন (একাধিকবার রান করলেও সমস্যা নেই)

create table if not exists public.app_update (
  id               int primary key default 1,
  version_code     bigint not null default 0,
  min_version_code bigint not null default 0,   -- এর চেয়ে পুরনো ভার্সনে আপডেট বাধ্যতামূলক
  version_name     text,
  apk_url          text,
  message          text,
  force            boolean not null default false,
  updated_at       timestamptz not null default now(),
  constraint app_update_single_row check (id = 1)
);

insert into public.app_update (id) values (1) on conflict (id) do nothing;

-- অ্যাপ (anon) শুধু পড়তে পারবে; লিখতে পারবে শুধু GitHub Actions (service_role key, RLS বাইপাস করে)
alter table public.app_update enable row level security;
drop policy if exists "app_update_read" on public.app_update;
create policy "app_update_read" on public.app_update for select using (true);

-- APK রাখার পাবলিক বাকেট
insert into storage.buckets (id, name, public)
values ('apk', 'apk', true)
on conflict (id) do update set public = true;
