package com.laurent.pacedrop;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;
import android.widget.Toast;
import android.os.CancellationSignal;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.View;
import java.util.Locale;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.credentials.CreateCredentialResponse;
import androidx.credentials.CreatePasswordRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.GetPasswordOption;
import androidx.credentials.PasswordCredential;
import androidx.credentials.exceptions.CreateCredentialException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.core.content.FileProvider;
import com.getcapacitor.BridgeActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import org.json.JSONObject;

public class MainActivity extends BridgeActivity {
    /** Pont appelé par la page de connexion : ouvre le sélecteur de mots de passe de Google. */
    private TextToSpeech voix;
    private boolean voixPrete = false;
    private String enAttente = null;

    private void parlerNatif(final String texte) {
        if (voix == null) {
            voix = new TextToSpeech(getApplicationContext(), statut -> {
                if (statut == TextToSpeech.SUCCESS) {
                    voix.setLanguage(Locale.FRANCE);
                    voixPrete = true;
                    if (enAttente != null) { voix.speak(enAttente, TextToSpeech.QUEUE_FLUSH, null, "pd"); enAttente = null; }
                }
            });
        }
        if (!voixPrete) { enAttente = texte; return; }
        voix.speak(texte, TextToSpeech.QUEUE_FLUSH, null, "pd");
    }

    /** Jeton Firebase de cet appareil (vide tant que Firebase n'est pas configuré ou que le jeton n'est pas arrivé). */
    private volatile String jetonPush = "";
    /** Pourquoi le jeton manque (pour le diagnostic du serveur) : texte de l'erreur Firebase, vide si tout va bien. */
    private volatile String erreurPush = "";

    private void preparerNotifications() {
        // Canal « Mes séances » : « Bravo pour ta séance », avec le bruit et la vibration par défaut.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager gestionnaire = getSystemService(NotificationManager.class);
            if (gestionnaire != null) {
                NotificationChannel canal = new NotificationChannel("seances", "Mes séances", NotificationManager.IMPORTANCE_HIGH);
                canal.setDescription("Ta séance est prête à être analysée.");
                gestionnaire.createNotificationChannel(canal);
            }
        }
        // Android 13+ : la permission d'afficher des notifications se demande.
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.POST_NOTIFICATIONS }, 4102);
        }
        // Le jeton Firebase : sans fichier google-services.json, Firebase n'est pas démarré et on n'en tient pas compte.
        demanderJetonPush(0);
    }

    /** Demande le jeton à Firebase ; SERVICE_NOT_AVAILABLE est passager (réseau, services Google qui s'éveillent) : on redemande. */
    private void demanderJetonPush(final int essai) {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(tache -> {
                    if (tache.isSuccessful() && tache.getResult() != null) {
                        jetonPush = tache.getResult();
                        erreurPush = "";
                    } else {
                        Exception e = tache.getException();
                        erreurPush = (e == null ? "jeton vide" : e.toString()) + " (essai " + (essai + 1) + ")";
                        if (essai < 8) {
                            getWindow().getDecorView().postDelayed(() -> demanderJetonPush(essai + 1), 15000L * (essai + 1));
                        }
                    }
                });
        } catch (Exception e) {
            jetonPush = "";
            erreurPush = e.toString();
        }
    }

    /** Une notification touchée ouvre la page visée (clé « url » : un chemin du site, jamais une adresse extérieure). */
    private void ouvrirDepuisNotification(Intent intent) {
        if (intent == null || intent.getExtras() == null) return;
        String url = intent.getStringExtra("url");
        if (url == null || !url.startsWith("/") || url.startsWith("//")) return;
        final WebView web = getBridge() != null ? getBridge().getWebView() : null;
        if (web == null) return;
        String courante = web.getUrl();
        if (courante == null) return;
        try {
            Uri base = Uri.parse(courante);
            final String cible = base.getScheme() + "://" + base.getAuthority() + url;
            web.postDelayed(() -> web.loadUrl(cible), 600);
        } catch (Exception ignore) { }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        ouvrirDepuisNotification(intent);
    }

    private class PontIdentifiants {
        /** Le jeton Firebase de l'appareil : la page l'envoie au serveur pour recevoir « Bravo pour ta séance ». */
        @JavascriptInterface
        public String jetonPush() { return jetonPush; }

        @JavascriptInterface
        public String jetonPushErreur() { return erreurPush; }

        /** Lit un texte à voix haute (synthèse vocale d'Android, en français) : la WebView n'a pas speechSynthesis. */
        @JavascriptInterface
        public void parler(final String texte) { runOnUiThread(() -> parlerNatif(texte)); }

        @JavascriptInterface
        public void taire() { runOnUiThread(() -> { enAttente = null; if (voix != null && voixPrete) voix.stop(); }); }

        @JavascriptInterface
        public void enregistrer(final String identifiant, final String motDePasse) {
            runOnUiThread(() -> enregistrerIdentifiants(identifiant, motDePasse));
        }

        @JavascriptInterface
        public void demander() {
            runOnUiThread(() -> demanderIdentifiants());
        }

        // ---- PaceGo : sortie enregistrée avec le GPS du téléphone, écran verrouillé compris ----

        /** Vrai si la position précise est déjà autorisée. */
        @JavascriptInterface
        public boolean goAutorise() {
            return ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }

        /** Demande la position (et les notifications sur Android 13+) ; la page reçoit le résultat par window.__goPermission(bool). */
        @JavascriptInterface
        public void goDemanderAutorisation() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    ActivityCompat.requestPermissions(MainActivity.this, new String[] { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS }, 4101);
                } else {
                    ActivityCompat.requestPermissions(MainActivity.this, new String[] { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }, 4101);
                }
            });
        }

        /** Démarre l'enregistrement : service au premier plan, étapes [{nom, minutes, cible}] en JSON. */
        @JavascriptInterface
        public boolean goDemarrer(final String etapesJson, final boolean voix) {
            if (!goAutorise()) return false;
            PaceGoService.reinitialiser(etapesJson, voix);
            runOnUiThread(() -> ContextCompat.startForegroundService(MainActivity.this, new Intent(MainActivity.this, PaceGoService.class).setAction(PaceGoService.ACTION_DEMARRER)));
            return true;
        }

        @JavascriptInterface public void goPause(final boolean enPause) { PaceGoService.basculerPause(enPause); }
        @JavascriptInterface public void goVoix(final boolean avecVoix) { PaceGoService.regler(avecVoix); }
        @JavascriptInterface public void goEtapeSuivante() { PaceGoService.etapeSuivante(); }
        @JavascriptInterface public String goEtat() { return PaceGoService.etatJson(); }
        @JavascriptInterface public String goPoints(final int depuis) { return PaceGoService.pointsJson(depuis); }

        /** Arrête le service (le GPS et la notification s'éteignent). */
        @JavascriptInterface
        public void goArreter() {
            runOnUiThread(() -> startService(new Intent(MainActivity.this, PaceGoService.class).setAction(PaceGoService.ACTION_ARRETER)));
        }

        /** Garde l'écran allumé (true) ou le laisse se mettre en veille (false) : renfo guidé. */
        @JavascriptInterface
        public void garderEcran(final boolean allume) {
            runOnUiThread(() -> {
                if (allume) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        /** Ouvre le partage Android avec l'image PNG (base64) générée par la page. */
        @JavascriptInterface
        public boolean partagerImage(final String base64, final String nom) {
            try {
                final byte[] octets = Base64.decode(base64, Base64.DEFAULT);
                File dossier = new File(getCacheDir(), "images");
                if (!dossier.exists() && !dossier.mkdirs()) return false;
                final File fichier = new File(dossier, nomSur(nom));
                try (FileOutputStream sortie = new FileOutputStream(fichier)) { sortie.write(octets); }
                runOnUiThread(() -> {
                    try {
                        Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", fichier);
                        Intent envoi = new Intent(Intent.ACTION_SEND);
                        envoi.setType("image/png");
                        envoi.putExtra(Intent.EXTRA_STREAM, uri);
                        envoi.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(envoi, "Partager"));
                    } catch (Throwable e) {
                        Toast.makeText(MainActivity.this, "Partage impossible", Toast.LENGTH_SHORT).show();
                    }
                });
                return true;
            } catch (Throwable e) {
                return false;
            }
        }

        /** Enregistre l'image PNG (base64) dans la galerie (Pictures/PaceDrop) ; avant Android 10, ouvre le partage. */
        @JavascriptInterface
        public boolean enregistrerImage(final String base64, final String nom) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return partagerImage(base64, nom);
            try {
                final byte[] octets = Base64.decode(base64, Base64.DEFAULT);
                ContentValues valeurs = new ContentValues();
                valeurs.put(MediaStore.Images.Media.DISPLAY_NAME, nomSur(nom));
                valeurs.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                valeurs.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PaceDrop");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valeurs);
                if (uri == null) return false;
                try (OutputStream sortie = getContentResolver().openOutputStream(uri)) {
                    if (sortie == null) return false;
                    sortie.write(octets);
                }
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Image enregistrée dans Pictures/PaceDrop", Toast.LENGTH_SHORT).show());
                return true;
            } catch (Throwable e) {
                return false;
            }
        }

        private String nomSur(String nom) {
            String propre = nom == null ? "" : nom.replaceAll("[^A-Za-z0-9._-]", "_");
            return propre.isEmpty() ? "pacedrop.png" : propre;
        }
    }

    private void soumettreConnexion() {
        WebView web = getBridge() != null ? getBridge().getWebView() : null;
        if (web != null) web.evaluateJavascript("window.__pdSoumettre && window.__pdSoumettre();", null);
    }

    /** Propose à Google d'enregistrer l'identifiant, puis laisse la connexion continuer. */
    private void enregistrerIdentifiants(String identifiant, String motDePasse) {
        try {
            CredentialManager.create(this).createCredentialAsync(
                this,
                new CreatePasswordRequest(identifiant, motDePasse),
                new CancellationSignal(),
                ContextCompat.getMainExecutor(this),
                new CredentialManagerCallback<CreateCredentialResponse, CreateCredentialException>() {
                    @Override
                    public void onResult(CreateCredentialResponse reponse) {
                        soumettreConnexion();
                    }

                    @Override
                    public void onError(CreateCredentialException e) {
                        soumettreConnexion();
                    }
                }
            );
        } catch (Throwable e) {
            soumettreConnexion();
        }
    }

    private void demanderIdentifiants() {
        try {
            CredentialManager gestionnaire = CredentialManager.create(this);
            GetCredentialRequest requete = new GetCredentialRequest.Builder()
                .addCredentialOption(new GetPasswordOption())
                .build();
            gestionnaire.getCredentialAsync(
                this,
                requete,
                new CancellationSignal(),
                ContextCompat.getMainExecutor(this),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse reponse) {
                        Credential credential = reponse.getCredential();
                        if (credential instanceof PasswordCredential) {
                            PasswordCredential mdp = (PasswordCredential) credential;
                            remplirConnexion(mdp.getId(), mdp.getPassword());
                        }
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        // Annulé ou aucun mot de passe enregistré : on laisse le formulaire tel quel.
                    }
                }
            );
        } catch (Throwable e) {
            // Pas bloquant : la connexion manuelle reste possible.
        }
    }

    private void remplirConnexion(String identifiant, String motDePasse) {
        WebView web = getBridge() != null ? getBridge().getWebView() : null;
        if (web == null) return;
        String js = "window.__pdFill && window.__pdFill(" + JSONObject.quote(identifiant) + "," + JSONObject.quote(motDePasse) + ");";
        web.evaluateJavascript(js, null);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] resultats) {
        super.onRequestPermissionsResult(code, permissions, resultats);
        if (code != 4101) return;
        boolean ok = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        WebView web = getBridge() != null ? getBridge().getWebView() : null;
        if (web != null) web.evaluateJavascript("window.__goPermission && window.__goPermission(" + ok + ");", null);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preparerNotifications();
        // Ouverte depuis une notification (appli fermée) : la page visée s'ouvre une fois le site chargé.
        getWindow().getDecorView().postDelayed(() -> ouvrirDepuisNotification(getIntent()), 2500);
        // Taille du texte alignée sur le rendu dans Chrome (115 %).
        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().getSettings().setTextZoom(115);
            final WebView web = getBridge().getWebView();
            web.addJavascriptInterface(new PontIdentifiants(), "PaceDropNative");
            // Un pont ajouté après le début du chargement n'apparaît qu'à la page suivante :
            // on recharge une fois la première page pour qu'il y soit présent.
            web.post(new Runnable() {
                @Override
                public void run() {
                    web.reload();
                }
            });
            // Saisie automatique : demande explicite au gestionnaire de mots de passe.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getBridge().getWebView().setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
                getWindow().getDecorView().setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
            }
        }
        // Fond clair : icônes de la barre d'état (heure, batterie, réseau) en foncé.
        WindowInsetsControllerCompat controleur = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controleur.setAppearanceLightStatusBars(true);
        controleur.setAppearanceLightNavigationBars(true);
        // Clavier : en plein écran (bord à bord) Android ne redimensionne plus la fenêtre, le clavier se pose par-dessus la saisie.
        // On réserve sa hauteur en bas du contenu (moins la barre de navigation, déjà gérée par les marges de Capacitor), sans
        // consommer les marges : la zone de saisie remonte au-dessus du clavier, comme dans une messagerie.
        final View contenu = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(contenu, (vue, marges) -> {
            Insets clavier = marges.getInsets(WindowInsetsCompat.Type.ime());
            Insets barres = marges.getInsets(WindowInsetsCompat.Type.systemBars());
            vue.setPadding(0, 0, 0, Math.max(0, clavier.bottom - barres.bottom));
            return marges;
        });
        // Bouton retour : revient à la page précédente du site, et ne quitte
        // l'appli que depuis la première page.
        getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    WebView web = getBridge() != null ? getBridge().getWebView() : null;
                    if (web != null && web.canGoBack()) {
                        web.goBack();
                    } else {
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                    }
                }
            }
        );
    }
}
