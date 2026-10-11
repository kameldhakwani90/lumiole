-- Compte parent Lumiole : une ligne par famille (profils des enfants + progression), privée.
create table if not exists public.familles (
  user_id uuid primary key references auth.users (id) on delete cascade,
  donnees jsonb not null default '{}'::jsonb,
  maj timestamptz not null default now()
);
alter table public.familles enable row level security;
-- chaque parent ne voit et ne modifie que sa propre famille
create policy "lire sa famille" on public.familles for select using (auth.uid() = user_id);
create policy "créer sa famille" on public.familles for insert with check (auth.uid() = user_id);
create policy "modifier sa famille" on public.familles for update using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "supprimer sa famille" on public.familles for delete using (auth.uid() = user_id);
