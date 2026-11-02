-- Post settings (AI label / Monetization) — চালানোর পর PostQueue.SEND_SETTING_COLUMNS = true করুন
alter table public.posts add column if not exists ai_label boolean not null default false;
alter table public.posts add column if not exists monetize boolean not null default false;
