// Connecter la télé au compte des parents depuis le téléphone (comme Netflix / YouTube).
// Le téléphone connecté demande un jeton à usage unique ; il le passe à la télé par le canal secret du QR ;
// la télé l'échange contre SA PROPRE session (pas de partage du jeton de rafraîchissement du téléphone).
// Déployer : supabase functions deploy lier-tele
import { createClient } from 'npm:@supabase/supabase-js@2';

const entetes = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'authorization, apikey, content-type', 'Content-Type': 'application/json' };

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: entetes });
  const jeton = (req.headers.get('Authorization') || '').replace('Bearer ', '');
  const admin = createClient(Deno.env.get('SUPABASE_URL')!, Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!);
  const { data, error } = await admin.auth.getUser(jeton);
  if (error || !data.user?.email) return new Response('{"erreur":"non autorisé"}', { status: 401, headers: entetes });
  // lien magique généré sans envoi d'e-mail : on ne garde que son empreinte (usage unique, courte durée)
  const { data: lien, error: e2 } = await admin.auth.admin.generateLink({ type: 'magiclink', email: data.user.email });
  if (e2 || !lien?.properties?.hashed_token) return new Response(JSON.stringify({ erreur: e2?.message || 'lien' }), { status: 500, headers: entetes });
  return new Response(JSON.stringify({ jeton: lien.properties.hashed_token }), { headers: entetes });
});
