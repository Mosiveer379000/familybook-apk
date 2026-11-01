-- কমেন্ট ও ভিডিও-কমেন্টে GIF: একবার Supabase → SQL Editor-এ চালান
alter table public.comments add column if not exists image_key text;
