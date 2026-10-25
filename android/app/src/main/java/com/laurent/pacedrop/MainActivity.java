package com.laurent.pacedrop;

import android.os.Build;
import android.os.CancellationSignal;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.GetPasswordOption;
import androidx.credentials.PasswordCredential;
import androidx.credentials.exceptions.GetCredentialException;
import com.getcapacitor.BridgeActivity;
import org.json.JSONObject;

public class MainActivity extends BridgeActivity {
    /** Pont appelé par la page de connexion : ouvre le sélecteur de mots de passe de Google. */
    private class PontIdentifiants {
        @JavascriptInterface
        public void demander() {
            runOnUiThread(() -> demanderIdentifiants());
        }
    }

    private void demanderIdentifiants() {
        Toast.makeText(this, "Recherche des mots de passe…", Toast.LENGTH_SHORT).show();
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
                        // Annulé ou aucun mot de passe : on affiche la raison (mise au point).
                        Toast.makeText(MainActivity.this, "Mots de passe : " + e.getType() + " " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            );
        } catch (Throwable e) {
            Toast.makeText(this, "Mots de passe : erreur " + e, Toast.LENGTH_LONG).show();
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
