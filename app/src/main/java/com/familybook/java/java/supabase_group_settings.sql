-- ১) Post Settings ও Post Privacy
alter table public.groups
  add column if not exists post_mode    text not null default 'auto',   -- auto | admin_approval | admins_only
  add column if not exists post_privacy text not null default 'open';   -- open | view_only

-- ২) Blocked users
create table if not exists public.group_blocks (
  id         uuid primary key default gen_random_uuid(),
  group_id   uuid not null references public.groups(id) on delete cascade,
  user_id    uuid not null,
  blocked_by uuid,
  created_at timestamptz not null default now(),
  unique (group_id, user_id)
);

alter table public.group_blocks enable row level security;

-- যে কেউ নিজের ব্লক আছে কিনা দেখতে পারবে; Admin/Moderator সবটা দেখতে ও বদলাতে পারবে
create policy "blocks_read" on public.group_blocks for select using (
  user_id = auth.uid()
  or exists (select 1 from public.group_members m
             where m.group_id = group_blocks.group_id and m.user_id = auth.uid()
               and m.status = 'active' and m.role in ('admin','moderator'))
);
create policy "blocks_write" on public.group_blocks for all using (
  exists (select 1 from public.group_members m
          where m.group_id = group_blocks.group_id and m.user_id = auth.uid()
            and m.status = 'active' and m.role in ('admin','moderator'))
) with check (
  exists (select 1 from public.group_members m
          where m.group_id = group_blocks.group_id and m.user_id = auth.uid()
            and m.status = 'active' and m.role in ('admin','moderator'))
);

-- ৩) Post Settings সার্ভারে কার্যকর করা
-- (posts.status-এ CHECK constraint থাকলে সেখানে 'pending' মান যোগ করে নিন)
create or replace function public.is_group_manager(g uuid, u uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from group_members
                 where group_id = g and user_id = u and status = 'active'
                   and role in ('admin','moderator'));
$$;

create or replace function public.enforce_group_post_mode() returns trigger
language plpgsql security definer set search_path = public as $$
declare v_mode text;
begin
  if new.group_id is null then return new; end if;
  if public.is_group_manager(new.group_id, new.user_id) then return new; end if;
  select post_mode into v_mode from groups where id = new.group_id;
  if v_mode = 'admins_only' then
    raise exception 'Only admins and moderators can post in this group';
  elsif v_mode = 'admin_approval' then
    new.status := 'pending';          -- ফিড ও গ্রুপ পেজ status=active ফিল্টার করে, তাই লুকানো থাকে
  end if;
  return new;
end $$;

drop trigger if exists trg_group_post_mode on public.posts;
create trigger trg_group_post_mode before insert on public.posts
  for each row execute function public.enforce_group_post_mode();

-- সাধারণ সদস্য নিজের pending পোস্ট নিজে active করতে পারবে না
create or replace function public.guard_pending_post() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if old.status = 'pending' and new.status is distinct from 'pending'
     and not public.is_group_manager(old.group_id, auth.uid()) then
    new.status := 'pending';
  end if;
  return new;
end $$;

drop trigger if exists trg_guard_pending_post on public.posts;
create trigger trg_guard_pending_post before update on public.posts
  for each row execute function public.guard_pending_post();
