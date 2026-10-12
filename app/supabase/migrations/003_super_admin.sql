-- Super admin de Lumiole : liste des admins, configuration pilotée à distance, statistiques (appliquée le 12/10/2026)
create table if not exists public.admins (email text primary key, ajoute timestamptz not null default now());
alter table public.admins enable row level security;
create policy "admins non lisibles" on public.admins for select to authenticated using (false);
create or replace function public.est_admin() returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.admins a where lower(a.email) = lower(coalesce(auth.jwt() ->> 'email', '')));
$$;
revoke all on function public.est_admin() from public, anon;
grant execute on function public.est_admin() to authenticated;
create table if not exists public.app_config (cle text primary key, valeur jsonb not null default '{}'::jsonb, maj timestamptz not null default now());
alter table public.app_config enable row level security;
create policy "config lisible par tous" on public.app_config for select to anon, authenticated using (true);
create policy "config modifiable par les admins" on public.app_config for all to authenticated using (public.est_admin()) with check (public.est_admin());
-- admin_stats() et admin_familles(n) : voir la migration 003b (security definer, refusent tout compte non admin)
insert into public.admins (email) values ('<email de l’administrateur>') on conflict do nothing;
