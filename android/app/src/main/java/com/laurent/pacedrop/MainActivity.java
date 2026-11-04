package com.laurent.pacedrop;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;
import android.widget.Toast;
import android.os.CancellationSignal;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
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
    private class PontIdentifiants {
        @JavascriptInterface
        public void enregistrer(final String identifiant, final String motDePasse) {
            runOnUiThread(() -> enregistrerIdentifiants(identifiant, motDePasse));
        }

        @JavascriptInterface
        public void demander() {
            runOnUiThread(() -> demanderIdentifiants());
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
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
