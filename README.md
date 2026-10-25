# PaceDrop — application Android (et iOS)

Coque Capacitor : l'application ouvre le serveur PaceDrop (`capacitor.config.json` → `server.url`) dans une vraie appli.
Chaque déploiement du backend est donc visible tout de suite, sans reconstruire l'APK.

## Récupérer l'APK de test
1. GitHub → **Releases** → « APK de test PaceDrop » → télécharger **pacedrop.apk** (il est remplacé à chaque compilation).
   Sinon : onglet **Actions** → dernier run → « Artifacts » → `pacedrop-apk` (un zip).
3. Sur le téléphone : ouvrir l'APK, autoriser l'installation depuis cette source.

## Changer l'adresse du serveur
`capacitor.config.json` → `server.url` (et `allowNavigation`). Puis `npx cap sync`.

## Pour Google Play / App Store
Voir les notes envoyées avec la mise en place : clé d'envoi Android (AAB signé), compte développeur Apple, fonctions natives
(notifications) et règles d'abonnement des deux boutiques.
