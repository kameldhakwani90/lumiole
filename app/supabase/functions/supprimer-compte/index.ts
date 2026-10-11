// Suppression du compte depuis l'app (exigé par Apple et Google).
// Déployer : supabase functions deploy supprimer-compte
import { createClient } from 'npm:@supabase/supabase-js@2';

const entetes = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'authorization, apikey, content-type' };

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: entetes });
  const jeton = (req.headers.get('Authorization') || '').replace('Bearer ', '');
  const admin = createClient(Deno.env.get('SUPABASE_URL')!, Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!);
  const { data, error } = await admin.auth.getUser(jeton);
  if (error || !data.user) return new Response('non autorisé', { status: 401, headers: entetes });
  await admin.from('familles').delete().eq('user_id', data.user.id);
  const { error: e2 } = await admin.auth.admin.deleteUser(data.user.id);
  if (e2) return new Response(e2.message, { status: 500, headers: entetes });
  return new Response(null, { status: 204, headers: entetes });
});
