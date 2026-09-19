# Interrupteur (appli Android minimale, but pedagogique)

Un seul ecran, un seul bouton : quand on appuie, l'appli envoie une requete http GET
vers une URL fixe (celle de ton module wifi / interrupteur connecte), et affiche le
code de reponse recu.

## Ouvrir le projet

1. Ouvrir Android Studio.
2. `File > Open`, choisir ce dossier (`interrupteur-android`).
3. Android Studio va proposer de generer le "Gradle Wrapper" manquant : accepter.
4. Laisser Gradle synchroniser (premiere fois : peut prendre quelques minutes).

## Avant de lancer

Modifier la constante `URL_INTERRUPTEUR` en haut de
[`MainActivity.kt`](app/src/main/java/com/example/interrupteur/MainActivity.kt)
avec l'URL reelle de ton module (IP locale + commande).

## Lancer sur le Galaxy A13

1. Activer le mode developpeur + le debogage USB sur le telephone.
2. Brancher le telephone en USB (ou configurer le wifi debugging).
3. Dans Android Studio, selectionner l'appareil puis cliquer sur "Run" (▶).

Le telephone et le module wifi cible doivent etre sur le meme reseau local.

## Pourquoi ce code est ecrit comme ca (points a retenir)

- `android:usesCleartextTraffic="true"` dans le `AndroidManifest.xml` : obligatoire
  depuis Android 9 pour pouvoir faire des appels en `http://` (non chiffre), ce qui
  est le cas pour la plupart des modules domotique locaux.
- La requete http est faite dans un `Thread` separe : un appel reseau ne peut pas
  se faire directement sur le thread principal (celui qui gere l'affichage), sinon
  Android plante l'appli volontairement (`NetworkOnMainThreadException`).
- `runOnUiThread { ... }` : seul le thread principal a le droit de modifier
  l'affichage (ici le `TextView`), donc on y revient explicitement pour afficher
  le resultat.
