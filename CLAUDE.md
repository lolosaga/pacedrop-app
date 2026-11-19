# PaceDrop app — repères

Coque Capacitor 7 (Android et iOS) : elle ouvre le serveur PaceDrop (`capacitor.config.json` → `server.url`,
aujourd'hui `https://pacedrop-backend-production.up.railway.app/activities/upload`).

- **Les écrans ne sont pas ici** : ils sont rendus par le backend (`lolosaga/pacedrop-backend`, voir son `CLAUDE.md`).
  Changer un écran = modifier le backend et pousser sur `main` (Railway déploie), sans reconstruire l'APK.
- Ici seulement : config native, icônes et splash (`assets/`, `npm run assets`), page hors ligne (`www/hors-ligne.html`),
  site vitrine (`site/`), CI de l'APK (`.github/workflows/android.yml`, publié dans la release « APK de test PaceDrop »).
- Nouvel APK nécessaire seulement si la config native, les plugins ou les icônes changent : `npx cap sync` puis CI.
