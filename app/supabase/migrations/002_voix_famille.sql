-- Voix de la famille (maman, papa) : un dossier privé par compte parent dans le stockage « voix »
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('voix', 'voix', false, 5242880, array['audio/webm', 'audio/mp4', 'audio/ogg', 'audio/mpeg', 'audio/aac', 'video/webm', 'video/mp4'])
on conflict (id) do nothing;
-- chaque parent ne voit que son dossier (le nom du dossier = son identifiant)
create policy "voix : lire son dossier" on storage.objects for select to authenticated
  using (bucket_id = 'voix' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "voix : ajouter dans son dossier" on storage.objects for insert to authenticated
  with check (bucket_id = 'voix' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "voix : remplacer dans son dossier" on storage.objects for update to authenticated
  using (bucket_id = 'voix' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "voix : supprimer dans son dossier" on storage.objects for delete to authenticated
  using (bucket_id = 'voix' and (storage.foldername(name))[1] = auth.uid()::text);
