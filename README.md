# Miru

Appli Android pour regarder des animes, sur le modèle de Yomu. Source : **Anime-Sama**.

## Ce qu'elle fait

- **Accueil** : la page d'accueil d'Anime-Sama (à l'affiche, sorties de la semaine, derniers épisodes…) et une rangée « Reprendre ».
- **Catalogue** : recherche, filtre VF et filtres par genre, défilement infini.
- **Fiche d'un anime** : synopsis, genres, saisons/films, choix VOSTFR / VF, épisodes vus, progression.
- **Lecteur vidéo** (ExoPlayer) :
  - essaie les lecteurs un par un (Sibnet, Sendvid, Vidmoly, VK, OneUpload…) jusqu'à en trouver un qui marche ;
  - reprend là où tu t'étais arrêté, marque l'épisode vu à 90 % ;
  - bouton « +85 s » pour passer l'intro, épisode précédent/suivant, liste des épisodes ;
  - enchaîne l'épisode suivant, puis la saison suivante ;
  - image dans l'image (quand tu quittes l'appli pendant la lecture) ;
  - **lecteur web de secours** si aucun lecteur ne passe (pubs et redirections bloquées).
- **Bibliothèque** et **Historique**.
- **Adresse du site** : Anime-Sama change souvent de domaine. Miru trouve la nouvelle adresse tout seul
  (les anciennes redirigent vers la nouvelle), ou tu peux la taper dans *Plus*.
- **Mise à jour** : *Plus → Mettre à jour l'appli* télécharge la dernière version publiée sur GitHub.

Android 8.0 minimum.

## Obtenir l'APK

Chaque `git push` sur `main` compile l'appli avec GitHub Actions (4 à 6 min) et la publie ici :
`https://github.com/Kruikruik/Miru/releases/latest` → **Miru.apk**.

La clé de signature est fixe (`app/miru.keystore`) : une nouvelle version s'installe par-dessus l'ancienne
sans perdre ta bibliothèque.

## Structure

```
app/src/main/java/com/miru/anime/
├── source/AnimeSama.kt   pages du site, saisons, episodes.js, adresse auto
├── source/Extractors.kt  lien du lecteur → lien vidéo direct (Sibnet, Sendvid, Vidmoly, VK, générique)
├── player/               lecteur ExoPlayer + lecteur web de secours
├── data/                 bibliothèque, progression, historique, réglages, mise à jour
└── ui/                   écrans : Accueil, Catalogue, Biblio, Historique, Plus, Fiche
```
