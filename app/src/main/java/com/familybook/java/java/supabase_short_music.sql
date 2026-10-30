-- Short-এর গানের তথ্য (ভিউয়ারের নিচের "সাউন্ড" লাইনে নাম দেখাতে ও চাপলে গান বাজাতে)
-- Supabase → SQL Editor-এ একবার চালান (আবার চালালেও সমস্যা নেই)।
-- এটা চালানোর পরেই নতুন অ্যাপ ব্যবহার করুন, নইলে Short আপলোড/লোড ব্যর্থ হবে।

alter table public.shorts add column if not exists music_title  text;
alter table public.shorts add column if not exists music_artist text;
alter table public.shorts add column if not exists music_cover  text;
alter table public.shorts add column if not exists music_url    text;
alter table public.shorts add column if not exists music_start  integer;
alter table public.shorts add column if not exists music_len    integer;

-- Short এডিট (থাম্বনেইল / টাইটেল / ডেসক্রিপশন / হ্যাশট্যাগ / AI লেবেল) নিজের Short-এ UPDATE করতে পারা চাই।
-- আপনার shorts টেবিলে আগে থেকে এমন policy থাকলে নিচেরটা লাগবে না।
-- do $$ begin
--   if not exists (select 1 from pg_policies where tablename = 'shorts' and policyname = 'shorts_update_own') then
--     create policy shorts_update_own on public.shorts for update
--       using (auth.uid() = user_id) with check (auth.uid() = user_id);
--   end if;
-- end $$;
