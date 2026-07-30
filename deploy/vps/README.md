# Deploiement VPS Nexora + Drama API

Cette configuration deploie les services suivants sur le meme VPS:

- `nexora-api`: application Spring Boot, port local `8080`.
- `nexora-drama`: API Python ReelShort/Drama, port local `5000`.
- `content-nexora`: API Python et lecteur web Content-Nexora, port local `8787`, expose sous `content.nexoragabon.com`.
- `nexora-anime`: API Python Anime-Sama, port local `5001`, relayee par Spring sous `/api/external/anime`.
- `frenchnexora-api` (optionnel): API Next.js/Puppeteer French Nexora API Node, port local `3100`, source de flux complementaire de Content-Nexora (voir section 2b).

Nginx expose le site et l'API en HTTPS, puis Spring appelle l'API drama en interne avec:

```env
DRAMA_API_BASE_URL=http://127.0.0.1:5000/api/v1/reelshort
```

Ajoute aussi un enregistrement DNS `content.nexoragabon.com` vers le VPS : le lecteur Content-Nexora utilise ses chemins `/api/...` à la racine de ce sous-domaine.

## 0. Supprimer les anciennes APIs Node FR

À exécuter une seule fois sur le VPS avant d'activer Content-Nexora, uniquement
si d'anciens services Node FR abandonnés tournent encore (déploiement jamais
mis à jour). Si vous voulez au contraire réactiver `nexora-node-api` comme
source complémentaire de Content-Nexora, allez directement à la section 2b —
ce nettoyage n'est pas un prérequis pour ça.

**Attention** : `frenchnexora-api` et `/opt/nexora/node-api` sont les mêmes noms
que ceux (ré)installés en section 2b. Ne relancez pas ce script après avoir
installé la section 2b, sinon il supprime le service et le dépôt que vous venez
d'installer. Les chemins ci-dessous sont ceux de l'ancien déploiement :

```bash
for service in frenchnexora-api frenchnexora-fallback node-fr node-api french-providers orion; do
  sudo systemctl disable --now "$service" 2>/dev/null || true
  sudo rm -f "/etc/systemd/system/$service.service"
done
sudo systemctl daemon-reload

# Suppression des anciens dépôts Node/FrenchNexora, après vérification du chemin.
sudo test ! -L /opt/nexora/node-api || sudo readlink -f /opt/nexora/node-api
sudo test ! -L /opt/nexora/frenchnexoraAPI || sudo readlink -f /opt/nexora/frenchnexoraAPI
sudo rm -rf -- /opt/nexora/node-api /opt/nexora/frenchnexoraAPI
```

Vérifier ensuite qu'aucun ancien service ne tourne :

```bash
systemctl list-units --all --type=service | grep -Ei 'node|french|orion' || true
```

## 1. Prerequis serveur

Sur Ubuntu/Debian:

```bash
sudo apt update
sudo apt install -y git nginx python3-venv python3-pip openjdk-17-jdk
```

Pour HTTPS:

```bash
sudo apt install -y certbot python3-certbot-nginx
```

## 2. Installer le projet

```bash
sudo useradd --system --create-home --shell /usr/sbin/nologin nexora || true
sudo mkdir -p /opt/nexora
sudo chown "$USER:$USER" /opt/nexora
git clone https://github.com/ibrahimdinzambou/nexoragabon.git /opt/nexora/app
cd /opt/nexora/app
./mvnw -DskipTests package

# API et lecteur des films et séries en français
git clone https://github.com/ibrahimdinzambou/Content-Nexora.git /opt/nexora/content-nexora
cd /opt/nexora/content-nexora
python3 -m venv .venv
. .venv/bin/activate
patch=/opt/nexora/app/deploy/vps/content-nexora-resilience.patch
if git apply --unidiff-zero --reverse --check "$patch" >/dev/null 2>&1; then
  echo "Correctifs Content-Nexora déjà appliqués"
else
  git apply --unidiff-zero --check "$patch"
  git apply --unidiff-zero "$patch"
fi
pip install -e . gunicorn

# Source du catalogue anime
git clone https://github.com/ibrahimdinzambou/anime-nexoraAPI.git /opt/nexora/anime-nexoraAPI
cd /opt/nexora/anime-nexoraAPI
python3 -m venv .venv
. .venv/bin/activate
pip install -e ".[api]"
```

Installer l'API Python:

```bash
cd /opt/nexora/app/reelshort-api
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
```

## 2b. (Optionnel) French Nexora API Node

Source complémentaire de Content-Nexora (scraping via navigateur headless
Puppeteer, avec blocage des popups/redirections publicitaires et capture
réseau des flux `.m3u8`). Désactivé par défaut: Content-Nexora fonctionne
sans cette étape. À installer seulement si vous voulez ajouter cette source.

Prérequis Node.js et Chromium (une fois par serveur):

```bash
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs chromium
```

Installer le service:

```bash
git clone https://github.com/Dinzambou241/nexora-node-api.git /opt/nexora/node-api
cd /opt/nexora/node-api
npm ci
npm run build
```

Puis regler dans `/opt/nexora/app/.env`:

```env
FRENCH_NEXORA_API_BASE_URL=http://127.0.0.1:3100
```

Sans cette variable (ou si elle est vide), Content-Nexora ignore silencieusement
cette source complementaire — aucun impact sur le reste du site.

Donner ensuite le dossier a l'utilisateur de service:

```bash
sudo chown -R nexora:nexora /opt/nexora
```

## 3. Variables d'environnement

Creer `/opt/nexora/app/.env` a partir de `.env.example`, puis au minimum regler:

```env
PORT=8080
PUBLIC_SITE_URL=https://nexoragabon.com
PUBLIC_API_BASE_URL=https://api.nexoragabon.com
CORS_ALLOWED_ORIGIN_PATTERNS=https://nexoragabon.com,https://www.nexoragabon.com,https://api.nexoragabon.com
DRAMA_API_BASE_URL=http://127.0.0.1:5000/api/v1/reelshort
DRAMA_API_TIMEOUT_SECONDS=20
CONTENT_NEXORA_API_BASE_URL=https://content.nexoragabon.com
ANIME_NEXORA_ENABLED=true
ANIME_NEXORA_BASE_URL=http://127.0.0.1:5001
ANIME_SOURCE_MODE=anime-nexora
ANIME_NEXORA_INTERNAL_BASE_URL=http://127.0.0.1:5001
CONTENT_NEXORA_INTERNAL_BASE_URL=http://127.0.0.1:8787
# Optionnel, seulement si la section 2b a ete installee:
# FRENCH_NEXORA_API_BASE_URL=http://127.0.0.1:3100
```

Si tu utilises PostgreSQL:

```env
SPRING_PROFILES_ACTIVE=postgres
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/iptv_saas
SPRING_DATASOURCE_USERNAME=iptv_saas
SPRING_DATASOURCE_PASSWORD=replace-with-password
```

## 4. Installer systemd

Copier les services:

```bash
sudo cp /opt/nexora/app/deploy/vps/systemd/nexora-api.service /etc/systemd/system/
sudo cp /opt/nexora/app/deploy/vps/systemd/nexora-drama.service /etc/systemd/system/
sudo cp /opt/nexora/app/deploy/vps/systemd/content-nexora.service /etc/systemd/system/
sudo cp /opt/nexora/app/deploy/vps/systemd/nexora-anime.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now nexora-drama
sudo systemctl enable --now nexora-api
sudo systemctl enable --now content-nexora
sudo systemctl enable --now nexora-anime
```

Si la section 2b a ete installee:

```bash
sudo cp /opt/nexora/app/deploy/vps/systemd/frenchnexora-api.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now frenchnexora-api
```

Verifier:

```bash
systemctl status nexora-drama --no-pager
systemctl status nexora-api --no-pager
systemctl status content-nexora --no-pager
curl http://127.0.0.1:5000/api/v1/reelshort/search?keywords=love
curl http://127.0.0.1:8080/actuator/health
curl http://127.0.0.1:8080/api/dramas/bookshelves?lang=fr
curl http://127.0.0.1:8787/api/health
curl http://127.0.0.1:5001/health
# Si la section 2b a ete installee:
curl http://127.0.0.1:3100/api/health
curl "http://127.0.0.1:3100/api/streams?tmdbId=936075&mediaType=movie&provider=all"
```

## 5. Installer Nginx

Le fichier Nginx versionné contient directement les vhosts HTTPS afin qu'une
mise à jour ne supprime plus le certificat de `content.nexoragabon.com`.
Le site principal et `www` peuvent rester hébergés ailleurs; seuls `api` et
`content` doivent pointer vers ce VPS. Obtenir d'abord leurs certificats
séparés (première installation uniquement):

```bash
sudo systemctl stop nginx
sudo certbot certonly --standalone \
  --cert-name api.nexoragabon.com \
  -d api.nexoragabon.com
sudo certbot certonly --standalone \
  --cert-name content.nexoragabon.com \
  -d content.nexoragabon.com
sudo cp /opt/nexora/app/deploy/vps/nginx/nexora.conf /etc/nginx/sites-available/nexora.conf
sudo ln -sfn /etc/nginx/sites-available/nexora.conf /etc/nginx/sites-enabled/nexora.conf
sudo nginx -t
sudo systemctl restart nginx
```

Tester le renouvellement HTTPS:

```bash
sudo certbot renew --dry-run
```

## 6. Connecter le front

Le front inclus dans Spring est deja configure pour:

- utiliser une URL relative si le site est servi sur `nexoragabon.com`, `www.nexoragabon.com` ou `api.nexoragabon.com`;
- appeler `https://api.nexoragabon.com/api/...` depuis les autres domaines.

Si ton front est sur Netlify/Vercel, ajoute:

```html
<script>
  window.NEXORA_API_BASE_URL = "https://api.nexoragabon.com";
  window.NEXORA_CONTENT_NEXORA_API_BASE_URL = "https://api.nexoragabon.com/api/external/content";
  window.NEXORA_CONTENT_NEXORA_PLAYER_BASE_URL = "https://content.nexoragabon.com";
  window.NEXORA_DRAMA_API_BASE_URL = "https://api.nexoragabon.com/drama-api";
</script>
```

avant `/assets/runtime-config.js`, ou modifie `src/main/resources/static/assets/runtime-config.js`.

## 7. Redemarrer apres mise a jour

```bash
sudo chown -R nexora:nexora /opt/nexora/app
sudo -u nexora git -C /opt/nexora/app remote set-url origin \
  https://github.com/ibrahimdinzambou/nexoragabon.git
sudo -u nexora git -C /opt/nexora/app fetch origin main
sudo -u nexora git -C /opt/nexora/app switch main
sudo -u nexora git -C /opt/nexora/app pull --ff-only origin main
sudo -u nexora bash -c 'cd /opt/nexora/app && ./mvnw -DskipTests package'

sudo -u nexora git -C /opt/nexora/anime-nexoraAPI pull --ff-only origin main
sudo -u nexora /opt/nexora/anime-nexoraAPI/.venv/bin/python -m pip install \
  -e '/opt/nexora/anime-nexoraAPI[api]'

cd /opt/nexora/app/reelshort-api
. .venv/bin/activate
pip install -r requirements.txt
cd /opt/nexora/content-nexora
git stash push -m "nexora-patches-before-update-$(date +%Y%m%d-%H%M%S)" -- \
  src/autoflix_api/app.py \
  src/autoflix_api/static/app.js \
  src/autoflix_api/static/app.css \
  src/autoflix_api/templates/index.html \
  src/autoflix_cli/config_loader.py \
  src/autoflix_cli/scraping/french_stream.py \
  src/autoflix_cli/scraping/player.py \
  tests/test_api_contract.py || true
git pull --ff-only
. .venv/bin/activate
patch=/opt/nexora/app/deploy/vps/content-nexora-resilience.patch
if git apply --unidiff-zero --reverse --check "$patch" >/dev/null 2>&1; then
  echo "Correctifs Content-Nexora déjà appliqués"
else
  git apply --unidiff-zero --check "$patch"
  git apply --unidiff-zero "$patch"
fi
pip install -e . gunicorn
sudo chown -R nexora:nexora /opt/nexora
sudo cp /opt/nexora/app/deploy/vps/systemd/content-nexora.service /etc/systemd/system/
sudo cp /opt/nexora/app/deploy/vps/systemd/nexora-anime.service /etc/systemd/system/
sudo cp /opt/nexora/app/deploy/vps/nginx/nexora.conf /etc/nginx/sites-available/nexora.conf
sudo systemctl daemon-reload
sudo nginx -t
sudo systemctl reload nginx
sudo systemctl restart nexora-drama nexora-api content-nexora
sudo systemctl restart nexora-anime

# Si la section 2b a ete installee:
sudo -u nexora git -C /opt/nexora/node-api pull --ff-only origin main
sudo -u nexora bash -c 'cd /opt/nexora/node-api && npm ci && npm run build'
sudo cp /opt/nexora/app/deploy/vps/systemd/frenchnexora-api.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl restart frenchnexora-api
```

Les routes `/api/**` laissent Spring Security gérer CORS, y compris les requêtes
préliminaires `OPTIONS`. N'ajoutez pas d'en-têtes `Access-Control-Allow-*` dans
le bloc Nginx `location /api/`, sinon les réponses applicatives contiendront
deux valeurs `Access-Control-Allow-Origin` et seront refusées par le navigateur.

VÃ©rifier ensuite le contrat API et le lecteur:

```bash
curl http://127.0.0.1:8787/api/health
curl "http://127.0.0.1:8787/api/search?provider=french-stream&q=breaking+bad"
curl "http://127.0.0.1:5001/api/v1/catalogues?limit=24"
```
