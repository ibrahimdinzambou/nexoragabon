# IPTV SaaS API - Spring Boot

Implementation Spring Boot de l'API decrite dans `CAHIER_DES_CHARGES_API_IPTV.md`.

## Stack

- Java 17
- Spring Boot 3.3
- Spring Web, Security, Data JPA, Validation, Mail, Actuator
- Springdoc OpenAPI 2.6
- H2 local par defaut
- Authentification Bearer token stockee en base

## Lancer

```powershell
.\mvnw.cmd spring-boot:run
```

API locale:

- `http://localhost:8080/` - interface client Nexora
- `http://localhost:8080/api/docs`
- `http://localhost:8080/swagger-ui.html`
- `http://localhost:8080/v3/api-docs`
- `http://localhost:8080/actuator/health`
- `http://localhost:8080/h2-console`

Production:

- Site Netlify: `https://nexoragabon.com` et `https://www.nexoragabon.com`
- API Railway: `https://nexora-api-production.up.railway.app`
- Health: `https://nexora-api-production.up.railway.app/actuator/health`

H2:

- JDBC URL: `jdbc:h2:file:./data/iptv-saas;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`
- User: `sa`
- Password: vide

## Railway et PostgreSQL

Le depot contient `railway.toml` pour builder le jar avec Railpack, demarrer
`target/iptv-saas-api-0.1.0.jar` et verifier `/actuator/health`.

Pour Railway:

1. Ajoutez un service PostgreSQL au projet Railway.
2. Dans le service Spring Boot, configurez:

```properties
SPRING_PROFILES_ACTIVE=postgres
SPRING_DATASOURCE_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
SPRING_DATASOURCE_USERNAME=${{Postgres.PGUSER}}
SPRING_DATASOURCE_PASSWORD=${{Postgres.PGPASSWORD}}
```

Gardez aussi les variables fonctionnelles du `.env` local: SMTP, Telegram,
TMDB, Consumet, TorBox et add-ons selon les integrations activees.
Configurez aussi les URLs publiques, CORS et le seed super admin pour le front
Netlify:

```properties
PUBLIC_SITE_URL=https://nexoragabon.com
PUBLIC_API_BASE_URL=https://nexora-api-production.up.railway.app
CORS_ALLOWED_ORIGIN_PATTERNS=https://nexoragabon.com,https://www.nexoragabon.com,https://*.netlify.app,https://*.vercel.app
SUPER_ADMIN_EMAIL=alexandredinzambou@gmail.com
SUPER_ADMIN_NAME=Alexandre Dinzambou
```

Le profil `postgres` cree ou met a jour le schema via Hibernate (`ddl-auto:
update`). La migration des donnees H2 existantes vers PostgreSQL reste une
etape separee: export H2, import PostgreSQL, puis verification des comptes,
plans, abonnements et sessions.

## Comptes seed

- Admin: `alexandredinzambou@gmail.com` / `password`
- User: `test@example.com` / `password`

## Exemples

Login:

```http
POST /api/auth/login
Content-Type: application/json

{
  "email": "alexandredinzambou@gmail.com",
  "password": "password"
}
```

Puis utiliser:

```http
Authorization: Bearer {token}
Accept: application/json
Content-Type: application/json
```

Swagger UI:

- Ouvrir `http://localhost:8080/swagger-ui.html`
- Cliquer sur `Authorize`
- Renseigner `Bearer {token}` ou directement le token selon l'affichage Swagger UI

Pages web:

- `http://localhost:8080/` : présentation publique de Nexora et abonnements
- `http://localhost:8080/signup.html` : inscription client avec choix de formule
- `http://localhost:8080/watch.html` : catalogue et lecteur client
- `http://localhost:8080/admin.html` : console d'administration

## Tests

```powershell
.\mvnw.cmd test
```

## SMTP et Telegram

La configuration se fait par variables d'environnement. Le fichier
`.env.example` liste toutes les variables attendues et Spring Boot charge aussi
un fichier `.env` local via `spring.config.import`.

Exemple PowerShell pour Mailtrap Email Testing:

```powershell
$env:MAIL_HOST="sandbox.smtp.mailtrap.io"
$env:MAIL_PORT="2525"
$env:MAIL_USERNAME="identifiant-mailtrap"
$env:MAIL_PASSWORD="mot-de-passe-mailtrap"
$env:MAIL_FROM_ADDRESS="notifications@nexoragabon.com"
$env:MAIL_FROM_NAME="Nexora"
$env:MAIL_SMTP_AUTH="true"
$env:MAIL_STARTTLS="true"
$env:MAIL_STARTTLS_REQUIRED="false"
$env:MAIL_SSL="false"
```

Telegram logs / alertes:

```powershell
$env:TELEGRAM_ALERTS_ENABLED="true"
$env:TELEGRAM_ALERTS_BOT_TOKEN="token-du-bot-logs"
$env:TELEGRAM_ALERTS_CHAT_ID="identifiant-du-chat-logs"
$env:TELEGRAM_ACTIVITY_ALERTS_ENABLED="true"
$env:TELEGRAM_LOGIN_ALERTS_ENABLED="true"
$env:TELEGRAM_FAILED_LOGIN_ALERTS_ENABLED="true"
$env:TELEGRAM_PASSWORD_ALERTS_ENABLED="true"
$env:TELEGRAM_DAILY_DIGEST_ENABLED="true"
$env:TELEGRAM_DAILY_DIGEST_CRON="0 0 22 * * *"
$env:TELEGRAM_DAILY_DIGEST_ZONE="Africa/Lagos"
```

Telegram administration:

```powershell
$env:TELEGRAM_ADMIN_ENABLED="true"
$env:TELEGRAM_ADMIN_BOT_TOKEN="token-du-bot-admin"
$env:TELEGRAM_ADMIN_CHAT_ID="identifiant-du-chat-admin"
$env:TELEGRAM_ADMIN_ALLOWED_CHAT_IDS="identifiant-du-chat-admin,autre-chat"
$env:TELEGRAM_ADMIN_READONLY_CHAT_IDS="chat-lecture-seule"
$env:TELEGRAM_ADMIN_CHAT_ROLES="identifiant-du-chat-admin:SUPER_ADMIN,autre-chat:OPS"
```

Redemarrer ensuite l'application. Les statuts et les envois de test sont
disponibles dans `http://localhost:8080/admin.html`, vue **Connecteurs**.
Avec Mailtrap Email Sending en production, remplacer l'hote par celui fourni par
Mailtrap, par exemple `live.smtp.mailtrap.io`, et garder les identifiants SMTP
du compte Mailtrap.

Panel Telegram:

- `/admin` : aide et menu avec boutons inline
- `/whoami`, `/admin_status` : chat_id, role et etat du bot admin
- `/control` : centre de controle Telegram avec boutons
- `/today` ou `/digest_now` : resume complet de la journee
- `/activity` ou `/activity email@example.com` : journal recent ou activite d'un client
- `/security` : connexions, resets et evenements sensibles
- `/errors` : erreurs et incidents recents issus de l'audit
- `/status`, `/health`, `/sessions`, `/capacity` : supervision
- `/iptv` ou `/accounts` : liste des comptes IPTV
- `/account_33` : detail d'un compte
- `/add_m3u Nom | URL playlist`
- `/add_xtream Nom | URL base | username | password`
- `/test_account 33`, `/sync_limits 33`, `/clear_cache 33`
- `/audit_iptv`, `/audit_iptv_now` ou `/audit_streams` : audit manuel relancable des comptes IPTV
- `/active_sessions`, `/close_session 12`, `/stale_sessions`, `/cleanup_sessions`
- `/client email@example.com`, `/suspend_user 5`, `/reactivate_user 5`
- `/pending_payments`, `/verify_payment 42`, `/reject_payment 42 | raison`
- `/tickets`, `/urgent_tickets`, `/reply_ticket 7 | message`
- `/smtp_status`, `/smtp_test email@example.com`, `/telegram_status`, `/torbox_status`
- `/addons` : liste des add-ons installes
- `/users recherche` : recherche d'utilisateurs actifs
- `/assign_addon addonId userId,userId` : partage d'un add-on prive avec des utilisateurs

Le bot d'administration est separe du bot de logs. Le bot logs utilise
`TELEGRAM_ALERTS_BOT_TOKEN` et ne fait qu'envoyer les alertes. Le bot admin
utilise `TELEGRAM_ADMIN_BOT_TOKEN` et traite les commandes.

Le panel n'accepte que les chats declares dans `TELEGRAM_ADMIN_ALLOWED_CHAT_IDS`. Les
chats declares dans `TELEGRAM_ADMIN_READONLY_CHAT_IDS` peuvent consulter mais pas
executer d'action d'ecriture. `TELEGRAM_ADMIN_CHAT_ROLES` accepte le format
`chatId:ROLE` avec `SUPER_ADMIN`, `ADMIN`, `OPS`, `BILLING` ou `SUPPORT`;
sans role explicite, un chat autorise garde le role `SUPER_ADMIN`. Les actions
sensibles demandent confirmation par bouton inline avec expiration courte. Toutes les commandes sont ajoutees au journal d'audit. Les alertes
automatiques couvrent les inscriptions, connexions, echecs de connexion,
demandes de reset mot de passe, tickets, paiements et evenements IPTV. Un
resume quotidien est envoye selon `TELEGRAM_DAILY_DIGEST_CRON`. Les alertes IPTV
previennent quand un compte tombe, expire bientot ou depasse le seuil de
saturation configure.

Compatibilite: les anciennes variables `TELEGRAM_BOT_TOKEN` et
`TELEGRAM_CHAT_ID` restent acceptees comme alias du bot logs, pas du bot admin.

## Interface client

Le dossier `netflix-main` sert de référence visuelle pour le site client. La version
intégrée à Spring Boot se trouve dans:

- `src/main/resources/static/index.html`
- `src/main/resources/static/assets/app.css`
- `src/main/resources/static/assets/app.js`
- `src/main/resources/static/assets/images/`

L'interface est servie directement par Spring Boot sur `http://localhost:8080/`
en local, et par Netlify sur `https://nexoragabon.com` en production. Le fichier
`assets/runtime-config.js` connecte le front a l'API Railway
`https://nexora-api-production.up.railway.app`. Sur Netlify, `netlify.toml`
publie `src/main/resources/static` et reecrit `/api/*` vers Railway.
Elle propose le catalogue Direct/Films/Séries, la recherche, l’inscription, la
connexion, le profil client et l’ouverture des sessions de streaming via l’API.
Les playlists M3U configurées dans les comptes IPTV sont chargées côté serveur:
leurs URLs ne sont jamais exposées au navigateur. Les flux MPEG-TS sont lus via
`mpegts.js` 1.8.0, distribué sous licence Apache-2.0 dans `assets/vendor/`.
Les comptes qui ne repondent plus pendant la lecture sortent automatiquement
de la rotation apres `IPTV_HEALTH_DISABLE_AFTER_FAILURES` echecs consecutifs.
Un `404/410` sur un flux est journalise comme contenu absent chez ce fournisseur,
sans desactiver tout le compte.

Sans authentification, un catalogue de découverte est affiché. Pour tester le
catalogue connecté:

- `test@example.com` / `password`
- `alexandredinzambou@gmail.com` / `password`

## Couverture fonctionnelle

Routes implementees:

- Auth: inscription, login, profil, logout, OTP email, 2FA, reset password
- SaaS: organisations, membres, plans, abonnements
- Billing: paiements manuels, validation/rejet admin, factures PDF simples
- IPTV: comptes fournisseurs, catalogue demo, health, sync limites
- Streaming: open, url, proxy redirect, heartbeat, close, cleanup service
- Support: tickets, reponses, messages internes admin
- Admin: dashboard, clients, subscriptions, invoices, users, IPTV, sessions
- Ops: health, metrics, audit logs, uptime checks
- Docs: `/api/docs`, Swagger UI `/swagger-ui.html`, OpenAPI JSON `/v3/api-docs`, redirection `/api/documentation`

## Add-ons communautaires

Le panneau `Administration > Connecteurs` permet d'installer un manifeste communautaire.
L'add-on reste en statut `PENDING` jusqu'a son approbation. Le serveur n'execute
aucun code tiers : il interroge uniquement des routes JSON compatibles avec le
format Stremio de base.

Un add-on peut aussi etre marque `Usage prive`. Dans ce mode:

- il est visible par son proprietaire et, si le super-admin le decide, par une
  liste restreinte de comptes actifs;
- seul le super-admin peut modifier cette liste de partage;
- tous les autres utilisateurs, y compris les administrateurs non selectionnes,
  ne peuvent ni parcourir ses catalogues ni resoudre directement ses identifiants;
- la preuve de licence n'est pas exigee, car le catalogue n'est pas publie aux
  utilisateurs de la plateforme;
- le proprietaire conserve la responsabilite de respecter les conditions des
  sources et du fournisseur Debrid utilise.

Exemple de manifeste:

```json
{
  "id": "org.example.free-cinema",
  "name": "Free Cinema",
  "version": "1.0.0",
  "description": "Catalogue de films sous licence libre",
  "catalogs": [
    { "type": "movie", "id": "free", "name": "Films libres" }
  ]
}
```

Pour un manifeste situe a `https://addon.example/manifest.json`, les routes
attendues sont:

```text
GET https://addon.example/catalog/movie/free.json
GET https://addon.example/meta/movie/{id}.json
GET https://addon.example/stream/movie/{id}.json
```

Le catalogue retourne `metas` ou `items`, la fiche retourne `meta`, et la route
de lecture retourne `streams`. Les URL media directes sont acceptees lorsqu'elles
appartiennent a la liste blanche de l'add-on. Les flux Stremio avec `infoHash`
peuvent etre resolus par TorBox lorsque `TORBOX_API_TOKEN` est configure.

Pour ASA, utilisez de preference l'URL privee generee par
`https://asa.00696900.xyz/configure#tokens` apres avoir renseigne TorBox.
Le manifeste public expose surtout des torrents bruts, tandis que le manifeste
configure peut fournir directement les liens video TorBox. Cette URL privee
contient une configuration sensible: ne la partagez pas et conservez l'add-on
en mode prive. Autorisez au minimum `.tb-cdn.io` et `.torbox.app` dans les
domaines de diffusion.

Les options Stremio declarees dans `catalogs[].extra` sont exposees comme
filtres de catalogue. ASA fournit notamment les filtres annee, studio,
interprete, tag et qualite. La pagination `skip` est chargee progressivement
avec le bouton `Charger la suite ASA`, et le catalogue `search` est utilise
automatiquement par la recherche globale.

Les types de catalogue Stremio personnalises sont acceptes lorsqu'un unique type
standard (`movie`, `series` ou `live`) est declare dans `types`. Par exemple, un
catalogue distant `porn` avec `"types": ["movie"]` est expose comme catalogue
`movie` dans Nexora, tout en conservant `porn` dans l'URL distante.

Configuration TorBox:

```properties
TORBOX_API_TOKEN=...
TORBOX_API_BASE_URL=https://api.torbox.app
TORBOX_ALLOWED_DOWNLOAD_HOSTS=.torbox.app,.tb-cdn.io
TORBOX_MAX_WAIT_SECONDS=90
```

Le jeton reste cote serveur. Nexora envoie le magnet a TorBox, selectionne le
plus grand fichier video, attend sa disponibilite puis relaie l'URL HTTPS
temporaire. Le navigateur ne recoit ni le magnet ni le jeton TorBox.

### Add-on stremio-porn sur Railway

Le depot inclut `addons/stremio-porn`, une copie deployable de
`https://github.com/naughty-doge/stremio-porn`. Cet add-on utilise l'ancien SDK
Stremio JSON-RPC (`/stremioget/stremio/v1`); Nexora le convertit en catalogues
internes au demarrage.

Railway:

1. Creez un second service Railway pour l'add-on avec le Root Directory
   `/addons/stremio-porn`.
2. Si Railway demande un chemin de config, utilisez
   `/addons/stremio-porn/railway.json`.
3. Exposez un domaine public pour ce service.
4. Dans le service Spring Boot Nexora, ajoutez:

```properties
STREMIO_PORN_ADDON_URL=https://${{stremio-porn.RAILWAY_PUBLIC_DOMAIN}}/stremioget/stremio/v1
STREMIO_PORN_ALLOWED_STREAM_HOSTS=*
```

Remplacez `stremio-porn` par le nom exact du service Railway si vous l'avez
nomme autrement. Une URL publique complete fonctionne aussi; si vous ne mettez
que le domaine, Nexora ajoute automatiquement `/stremioget/stremio/v1`.

Au demarrage, Nexora installe et approuve automatiquement cet add-on en contenu
18+. Les comptes clients ne voient pas ces categories adultes par defaut: elles
doivent etre attribuees explicitement dans l'administration ou par une regle
`ADDON`/`CATEGORY` adaptee.

## Consumet

Consumet peut etre utilise comme source de films, series et anime via une
instance REST auto-hebergee. Une fois active, Nexora ajoute les categories
Consumet au catalogue existant, charge les fiches detaillees, expose les
episodes et relaie les liens HLS retournes par `watch`.

Le mode `anilist` est compatible avec le depot `nekoCollection`: il utilise les
routes `/meta/anilist/*` pour le catalogue anime et ouvre les episodes via un
lecteur iframe compatible avec la structure `/{anilistId}/{episode}/sub`.

```properties
CONSUMET_ENABLED=true
CONSUMET_BASE_URL=https://consumet.kuro-neko.dev
CONSUMET_MOVIE_PROVIDER=flixhq
CONSUMET_ANIME_PROVIDER=hianime
CONSUMET_MOVIE_SERVER=vidcloud
CONSUMET_ANIME_SERVER=vidstreaming
CONSUMET_ANIME_CATEGORY=dub
CONSUMET_SOURCE_MODE=anilist
CONSUMET_META_PROVIDER=zoro
CONSUMET_EMBED_SOURCE=hd-2
CONSUMET_EMBED_PLAYER_URL=https://vidnest.fun
CONSUMET_OTHER_EMBED_PLAYER_URL=https://animeplay.cfd/stream/ani
CONSUMET_ALLOWED_STREAM_HOSTS=
```

`CONSUMET_ALLOWED_STREAM_HOSTS` sert uniquement si les playlists HLS renvoient
vers des CDN qui ne partagent pas le meme domaine parent que la source initiale.
Exemple: `.cdn.example` autorise les sous-domaines de ce CDN.

## TMDB

TMDB est installe comme connecteur serveur pour les metadonnees de films,
series, personnes et images. Le client prend en charge le token Bearer TMDB
recommande, ou la cle API v3 en secours.
Quand le connecteur est configure, Nexora ajoute des categories TMDB aux
onglets Films et Series, marque les contenus avec `TMDB`, et etend la recherche
globale avec les resultats `search/movie` et `search/tv`.

```properties
TMDB_READ_ACCESS_TOKEN=...
# ou
TMDB_API_KEY=...
TMDB_API_BASE_URL=https://api.themoviedb.org/3
TMDB_IMAGE_BASE_URL=https://image.tmdb.org/t/p
TMDB_LANGUAGE=fr-FR
TMDB_REGION=FR
TMDB_INCLUDE_ADULT=false
```

Le secret reste cote serveur. Le statut est visible dans la vue
`Connecteurs` de l'admin, sans exposer la cle ni le token.
Les fiches TMDB sont lisibles via le lecteur externe Videasy: les films ouvrent
`https://player.videasy.net/movie/{tmdbId}` et les episodes de series ouvrent
`https://player.videasy.net/tv/{tmdbId}/{season}/{episode}` automatiquement.
Si le player Videasy charge puis que sa console affiche un `500` sur
`api.videasy.to`, le `tmdbId` a bien ete transmis mais Videasy n'a pas de source
exploitable pour ce titre a ce moment-la.

Regles de securite:

- manifeste HTTPS public uniquement;
- reponses JSON limitees en taille et sans redirection automatique;
- licence et URL de preuve obligatoires avant approbation;
- exception de licence limitee aux add-ons prives et a leur proprietaire;
- domaines de flux explicitement autorises;
- URL TorBox limitees aux domaines de telechargement autorises;
- toute modification ou actualisation replace l'add-on en attente;
- un catalogue marque `18+` exige une attribution explicite de sa categorie a
  l'utilisateur.
