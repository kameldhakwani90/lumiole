// Copie la page Lumi (essai 2026-09-29-lumi-enfants) dans www/ et y branche les modules natifs.
import { build } from 'esbuild';
import { cpSync, mkdirSync, readFileSync, writeFileSync, rmSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ici = dirname(fileURLToPath(import.meta.url));
const racine = resolve(ici, '..');
// la page Lumi : l'essai voisin, ou un autre dossier (LUMI_SOURCE) — ex. la racine du dépôt public lumiole
const source = resolve(racine, process.env.LUMI_SOURCE || '../2026-09-29-lumi-enfants');
const www = resolve(racine, 'www');

rmSync(www, { recursive: true, force: true });
mkdirSync(www, { recursive: true });
for (const f of ['icone.svg', 'icone-32.png', 'icone-180.png', 'icone-192.png', 'icone-512.png', 'manifest.webmanifest', 'sons']) {
  if (existsSync(resolve(source, f))) cpSync(resolve(source, f), resolve(www, f), { recursive: true });
}
await build({ entryPoints: [resolve(racine, 'src/natif.js')], bundle: true, format: 'iife', minify: true, outfile: resolve(www, 'natif.js') });
// le pont natif doit être chargé avant le code de la page
const page = readFileSync(resolve(source, 'index.html'), 'utf8').replace('</head>', '<script src="natif.js"></script>\n</head>');
writeFileSync(resolve(www, 'index.html'), page);
console.log('www/ prêt :', Math.round(page.length / 1024), 'Ko');
