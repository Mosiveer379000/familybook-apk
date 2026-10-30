-- "এই গান দিয়ে বানানো Shorts" দ্রুত খুঁজতে (Supabase → SQL Editor-এ একবার চালান; আবার চালালেও সমস্যা নেই)
create index if not exists shorts_music_url_idx on public.shorts (music_url) where music_url is not null;
