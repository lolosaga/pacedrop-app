package com.laurent.pacedrop;

import android.os.Bundle;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Taille du texte alignée sur le rendu dans Chrome (115 %).
        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().getSettings().setTextZoom(115);
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
