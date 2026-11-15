# Tarification — application Android

Application Android autonome qui embarque l'outil de cotation (`../index.html`).
Elle fonctionne **entièrement hors ligne** (aucune permission Internet) : connexion,
calcul, historique (stockage local) et génération des PDF se font sur le téléphone.

## Obtenir l'APK

1. Onglet **Actions** du dépôt → workflow **Application Android** → dernière exécution
   → artefact **Tarification-apk** (fichier `.zip` contenant l'APK).
2. Pour une version publiée : pousser un tag `v1.0`, `v1.1`… ; l'APK est joint à la *Release*.
3. Sur le téléphone : ouvrir l'APK et autoriser l'installation d'applications inconnues.

L'APK est reconstruit automatiquement à chaque modification de `index.html` :
mettre à jour l'outil web met donc aussi à jour l'application.

## Ce qui est adapté à Android

- **PDF et journaux** : enregistrés dans *Téléchargements*, puis boutons *Ouvrir* / *Partager*.
  « Ouvrir le PDF » lance la visionneuse PDF du téléphone.
- **E-mail à la Direction technique** : la messagerie s'ouvre avec destinataire, objet,
  texte **et le fichier déjà joint**.
- Données conservées entre deux lancements (comme dans le navigateur).

## Signature

Sans configuration, l'APK est signé avec une clé de débogage : il s'installe, mais une
mise à jour devra être signée par la même clé. Pour une clé stable (recommandé, et
obligatoire pour le Play Store), créer une fois une clé :

    keytool -genkeypair -v -keystore tarification.jks -alias tarification -keyalg RSA -keysize 2048 -validity 10000

puis ajouter dans *Settings → Secrets and variables → Actions* :
`ANDROID_KEYSTORE_BASE64` (`base64 -w0 tarification.jks`), `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Conserver précieusement le fichier `.jks`.

## Compiler localement

Ouvrir le dossier `android/` dans Android Studio, ou : `cd android && ./gradlew assembleDebug`.
