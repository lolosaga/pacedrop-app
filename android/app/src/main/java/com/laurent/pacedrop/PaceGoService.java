package com.laurent.pacedrop;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * PaceGo : enregistre la sortie avec le GPS du téléphone, écran verrouillé compris.
 *
 * Un service au premier plan (notification permanente, type « location ») garde le GPS actif tant que la sortie dure.
 * Le service calcule aussi le temps, la distance et les étapes, et annonce kilomètres et changements d'étape à la voix :
 * la page web, suspendue quand l'écran est verrouillé, n'a qu'à relire l'état et les points à son retour.
 */
public class PaceGoService extends Service implements LocationListener {
    static final String ACTION_DEMARRER = "pacego.demarrer";
    static final String ACTION_ARRETER = "pacego.arreter";
    private static final String CANAL = "pacego";
    private static final int NOTIF_ID = 4101;
    private static final double PRECISION_MAX_M = 40.0;
    private static final double VITESSE_MAX_MS = 12.0;
    private static final double VITESSE_MIN_MS = 0.5;
    private static final double ECART_MAX_S = 30.0;

    // État partagé avec le pont de MainActivity (le service et l'activité vivent dans le même processus).
    private static final Object VERROU = new Object();
    private static boolean actif = false;
    private static boolean pause = false;
    private static boolean voix = true;
    private static long t0 = 0;           // elapsedRealtime du dernier départ ou de la dernière reprise
    private static double tAcc = 0;       // secondes déjà courues avant t0
    private static double distance = 0;   // mètres
    private static double mouvement = 0;  // secondes en mouvement
    private static int kmDit = 0;
    private static int etapeCourante = 0;
    private static double debutEtape = 0;
    private static List<double[]> points = new ArrayList<>();   // t, lat, lon, précision
    private static List<double[]> etapes = new ArrayList<>();   // durée en secondes
    private static List<String> nomsEtapes = new ArrayList<>();
    private static List<String> ciblesEtapes = new ArrayList<>();
    private static List<double[]> faites = new ArrayList<>();   // début, fin
    private static List<String> nomsFaites = new ArrayList<>();
    private static double[] dernier = null;                      // t, lat, lon du dernier point retenu

    private LocationManager gps;
    private PowerManager.WakeLock reveil;
    private TextToSpeech parole;
    private boolean paroleOk = false;
    private String enAttente = null;
    private android.os.Handler horloge;
    private final Runnable battement = new Runnable() {
        @Override public void run() { verifierEtape(); if (actif) horloge.postDelayed(this, 1000); }
    };

    static double ecoule() {
        synchronized (VERROU) {
            return tAcc + (actif && !pause ? (SystemClock.elapsedRealtime() - t0) / 1000.0 : 0);
        }
    }

    // ---- Commandes (appelées par le pont web) -------------------------------------------------------------------

    static void reinitialiser(String etapesJson, boolean avecVoix) {
        synchronized (VERROU) {
            actif = true; pause = false; voix = avecVoix; t0 = SystemClock.elapsedRealtime(); tAcc = 0;
            distance = 0; mouvement = 0; kmDit = 0; etapeCourante = 0; debutEtape = 0; dernier = null;
            points = new ArrayList<>(); etapes = new ArrayList<>(); nomsEtapes = new ArrayList<>(); ciblesEtapes = new ArrayList<>();
            faites = new ArrayList<>(); nomsFaites = new ArrayList<>();
            try {
                JSONArray liste = new JSONArray(etapesJson == null ? "[]" : etapesJson);
                for (int i = 0; i < liste.length() && i < 20; i++) {
                    JSONObject e = liste.getJSONObject(i);
                    etapes.add(new double[] { e.optDouble("minutes", 0) * 60.0 });
                    nomsEtapes.add(e.optString("nom", ""));
                    ciblesEtapes.add(e.optString("cible", ""));
                }
            } catch (Throwable ignore) { }
        }
    }

    static void basculerPause(boolean enPause) {
        synchronized (VERROU) {
            if (!actif || enPause == pause) return;
            if (enPause) { tAcc = ecoule(); pause = true; dernier = null; }
            else { t0 = SystemClock.elapsedRealtime(); pause = false; }
        }
    }

    static void regler(boolean avecVoix) { synchronized (VERROU) { voix = avecVoix; } }

    static void etapeSuivante() {
        String annonce;
        synchronized (VERROU) {
            if (!actif || nomsEtapes.isEmpty() || etapeCourante >= nomsEtapes.size() - 1) return;
            annonce = passerEtape(ecoule());
        }
        PaceGoService service = instance;
        if (service != null && annonce != null) service.dire(annonce);
    }

    private static volatile PaceGoService instance = null;

    /** Passe à l'étape suivante (appelé sous VERROU). Renvoie le texte à dire, ou null. */
    private static String passerEtape(double maintenant) {
        faites.add(new double[] { debutEtape, maintenant });
        nomsFaites.add(nomsEtapes.get(etapeCourante));
        etapeCourante++;
        debutEtape = maintenant;
        String cible = ciblesEtapes.get(etapeCourante);
        derniereAnnonce = nomsEtapes.get(etapeCourante) + (cible.isEmpty() ? "" : ", " + cible) + ".";
        return derniereAnnonce;
    }

    private static String derniereAnnonce = null;

    static boolean estActif() { synchronized (VERROU) { return actif; } }

    static String etatJson() {
        synchronized (VERROU) {
            try {
                JSONObject o = new JSONObject();
                o.put("actif", actif); o.put("pause", pause); o.put("t", ecoule()); o.put("dist", distance);
                o.put("mouv", mouvement); o.put("n", points.size()); o.put("i", etapeCourante); o.put("debutEtape", debutEtape);
                JSONArray f = new JSONArray();
                for (int k = 0; k < faites.size(); k++) {
                    JSONObject e = new JSONObject();
                    e.put("nom", nomsFaites.get(k)); e.put("debut_s", faites.get(k)[0]); e.put("fin_s", faites.get(k)[1]);
                    f.put(e);
                }
                o.put("faites", f);
                return o.toString();
            } catch (Throwable e) { return "{}"; }
        }
    }

    static String pointsJson(int depuis) {
        synchronized (VERROU) {
            JSONArray a = new JSONArray();
            try {
                for (int i = Math.max(0, depuis); i < points.size(); i++) {
                    double[] p = points.get(i);
                    JSONObject o = new JSONObject();
                    o.put("t", Math.round(p[0] * 10) / 10.0); o.put("lat", p[1]); o.put("lon", p[2]); o.put("acc", Math.round(p[3]));
                    a.put(o);
                }
            } catch (Throwable ignore) { }
            return a.toString();
        }
    }

    // ---- Cycle de vie du service --------------------------------------------------------------------------------

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_ARRETER.equals(intent.getAction())) { arreter(); return START_NOT_STICKY; }
        instance = this;
        demarrerPremierPlan();  // Android exige l'appel dès le démarrage du service
        if (!estActif()) { arreter(); return START_NOT_STICKY; }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            arreter();
            return START_NOT_STICKY;
        }
        try {
            gps = (LocationManager) getSystemService(LOCATION_SERVICE);
            gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
        } catch (Throwable e) { arreter(); return START_NOT_STICKY; }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (reveil == null && pm != null) { reveil = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pacedrop:pacego"); reveil.acquire(6 * 60 * 60 * 1000L); }
        horloge = new android.os.Handler(getMainLooper());
        horloge.removeCallbacks(battement);
        horloge.postDelayed(battement, 1000);
        dire("C'est parti." + (nomsEtapes.isEmpty() ? "" : " " + nomsEtapes.get(0) + "."));
        return START_NOT_STICKY;
    }

    private void demarrerPremierPlan() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CANAL, "Sortie PaceGo", NotificationManager.IMPORTANCE_LOW));
        }
        Intent ouvrir = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, ouvrir, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(getApplicationInfo().icon)
            .setContentTitle("PaceGo : sortie en cours")
            .setContentText("Le GPS enregistre ta sortie. Touche pour revenir dans PaceDrop.")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi).setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(NOTIF_ID, n);
    }

    private void arreter() {
        synchronized (VERROU) { actif = false; }
        try { if (gps != null) gps.removeUpdates(this); } catch (Throwable ignore) { }
        try { if (reveil != null && reveil.isHeld()) reveil.release(); } catch (Throwable ignore) { }
        reveil = null;
        if (horloge != null) horloge.removeCallbacks(battement);
        if (parole != null) { try { parole.stop(); parole.shutdown(); } catch (Throwable ignore) { } parole = null; paroleOk = false; }
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() { arreter(); if (instance == this) instance = null; super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }

    // ---- GPS ----------------------------------------------------------------------------------------------------

    @Override
    public void onLocationChanged(Location l) {
        String annonce = null;
        synchronized (VERROU) {
            if (!actif || pause) return;
            double precision = l.hasAccuracy() ? l.getAccuracy() : 0;
            if (precision > PRECISION_MAX_M) return;
            double t = ecoule();
            if (dernier != null) {
                double dt = t - dernier[0];
                if (dt < 2) return;
                float[] d = new float[1];
                Location.distanceBetween(dernier[1], dernier[2], l.getLatitude(), l.getLongitude(), d);
                double v = d[0] / dt;
                if (v >= VITESSE_MAX_MS) return;  // saut GPS
                if (dt <= ECART_MAX_S && v >= VITESSE_MIN_MS) { distance += d[0]; mouvement += dt; }
            }
            dernier = new double[] { t, l.getLatitude(), l.getLongitude() };
            points.add(new double[] { t, l.getLatitude(), l.getLongitude(), precision });
            int km = (int) Math.floor(distance / 1000.0);
            if (km > kmDit) {
                kmDit = km;
                double s = distance > 0 ? mouvement / (distance / 1000.0) : 0;
                int m = (int) (s / 60), sec = (int) Math.round(s % 60);
                annonce = km + (km > 1 ? " kilomètres" : " kilomètre") + ". Allure moyenne " + m + " " + (sec < 10 ? "0" : "") + sec + " par kilomètre.";
            }
        }
        if (annonce != null) dire(annonce);
    }

    private void verifierEtape() {
        String annonce = null;
        synchronized (VERROU) {
            if (!actif || pause || nomsEtapes.isEmpty() || etapeCourante >= nomsEtapes.size() - 1) return;
            double fin = debutEtape + etapes.get(etapeCourante)[0];
            if (ecoule() >= fin) annonce = passerEtape(fin);
        }
        if (annonce != null) dire(annonce);
    }

    @Override public void onStatusChanged(String p, int s, Bundle e) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }

    // ---- Voix ---------------------------------------------------------------------------------------------------

    private void dire(final String texte) {
        boolean ok; synchronized (VERROU) { ok = voix; }
        if (!ok) return;
        if (parole == null) {
            parole = new TextToSpeech(getApplicationContext(), statut -> {
                if (statut == TextToSpeech.SUCCESS) {
                    parole.setLanguage(Locale.FRANCE);
                    paroleOk = true;
                    if (enAttente != null) { parole.speak(enAttente, TextToSpeech.QUEUE_ADD, null, "pg"); enAttente = null; }
                }
            });
        }
        if (!paroleOk) { enAttente = texte; return; }
        parole.speak(texte, TextToSpeech.QUEUE_ADD, null, "pg");
    }
}
