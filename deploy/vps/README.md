# Deploiement VPS Nexora + Drama API

Cette configuration deploie deux services sur le meme VPS:

- `nexora-api`: application Spring Boot, port local `8080`.
- `nexora-drama`: API Python ReelShort/Drama, port local `5000`.
- `node-fr` / node-api: API Next.js des films et séries FR, port local `3100`.
- `french-providers` / frenchnexoraAPI: fallback providers, port local `3200`.
- `nexora-anime`: API Python Anime-Sama, port local `5001`, exposee sous `/anime-api`.
- `orion`: API legacy Orion/Aether, port local `3000`, utilisée en fallback.

Nginx expose le site et l'API en HTTPS, puis Spring appelle l'API drama en interne avec:

```env
DRAMA_API_BASE_URL=http://127.0.0.1:5000/api/v1/reelshort
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
git clone https://github.com/Dinzambou241/DramaAPI-nexora.git /opt/nexora/app
cd /opt/nexora/app
./mvnw -DskipTests package

# API des films et séries en français
git clone https://github.com/Dinzambou241/nexora-node-api.git /opt/nexora/node-api
cd /opt/nexora/node-api
npm ci
npm run build

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
ORION_BASE_URL=http://127.0.0.1:3000
ORION_TIMEOUT_SECONDS=30
FRENCH_NEXORA_API_BASE_URL=https://api.nexoragabon.com/node-fr
ANIME_NEXORA_ENABLED=true
ANIME_NEXORA_BASE_URL=https://api.nexoragabon.com/anime-api
ANIME_SOURCE_MODE=anime-nexora
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
sudo cp /opt/nexora/app/deploy/vps/systemd/frenchnexora-api.service /etc/systemd/system/
sudo cp /opt/nexora/app/deploy/vps/systemd/frenchnexora-fallback.service /etc/systemd/system/
sudo cp /opt/nexora/app/deploy/vps/systemd/nexora-anime.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now nexora-drama
sudo systemctl enable --now nexora-api
sudo systemctl enable --now frenchnexora-api
sudo systemctl enable --now frenchnexora-fallback
sudo systemctl enable --now nexora-anime
```

Verifier:

```bash
systemctl status nexora-drama --no-pager
systemctl status nexora-api --no-pager
curl http://127.0.0.1:5000/api/v1/reelshort/search?keywords=love
curl http://127.0.0.1:8080/actuator/health
curl http://127.0.0.1:8080/api/dramas/bookshelves?lang=fr
curl http://127.0.0.1:3000/api/health
curl http://127.0.0.1:5001/health
```

## 5. Installer Nginx

Modifier `deploy/vps/nginx/nexora.conf` si tes domaines changent, puis:

```bash
sudo cp /opt/nexora/app/deploy/vps/nginx/nexora.conf /etc/nginx/sites-available/nexora.conf
sudo ln -sfn /etc/nginx/sites-available/nexora.conf /etc/nginx/sites-enabled/nexora.conf
sudo nginx -t
sudo systemctl reload nginx
```

Activer HTTPS:

```bash
sudo certbot --nginx -d nexoragabon.com -d www.nexoragabon.com -d api.nexoragabon.com
```

## 6. Connecter le front

Le front inclus dans Spring est deja configure pour:

- utiliser une URL relative si le site est servi sur `nexoragabon.com`, `www.nexoragabon.com` ou `api.nexoragabon.com`;
- appeler `https://api.nexoragabon.com/api/...` depuis les autres domaines.

Si ton front est sur Netlify/Vercel, ajoute:

```html
<script>
  window.NEXORA_API_BASE_URL = "https://api.nexoragabon.com";
  window.NEXORA_DRAMA_API_BASE_URL = "https://api.nexoragabon.com/drama-api";
</script>
```

avant `/assets/runtime-config.js`, ou modifie `src/main/resources/static/assets/runtime-config.js`.

## 7. Redemarrer apres mise a jour

```bash
cd /opt/nexora/app
sudo chown -R "$USER:$USER" /opt/nexora/app
git pull
./mvnw -DskipTests package

# Mettre Ã  jour FrenchNexoraAPI Ã©galement : elle agrÃ¨ge les providers
# et expose /api/streams. Sans cette mise Ã  jour, le VPS peut rester sur
# l'ancien contrat /api/sources qui ne renvoie qu'un seul hoster.
cd /opt/nexora/node-api
git pull --ff-only
npm ci --omit=dev

cd /opt/nexora/app/reelshort-api
. .venv/bin/activate
pip install -r requirements.txt
sudo chown -R nexora:nexora /opt/nexora
sudo systemctl restart nexora-drama nexora-api frenchnexora-api
sudo systemctl restart nexora-anime
```

VÃ©rifier ensuite le contrat et le nombre de providers exposÃ©s:

```bash
curl http://127.0.0.1:3100/api/health
curl "http://127.0.0.1:3100/api/streams?tmdbId=936075&mediaType=movie&provider=all"
```
