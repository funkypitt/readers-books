# Politique de confidentialité — Reader's Books

*Dernière mise à jour : 8 novembre 2026*

Reader's Books (`com.freedomfighter.readersbooks`) est publiée par Pierre Gallaz. C'est une liseuse faite pour les écrans e-ink : elle affiche les livres que vous y ajoutez, page après page, et garde votre place, vos surlignages et vos commentaires. Elle est gratuite, sans publicité, sans compte et sans pistage.

## Données collectées par l'application

Aucune. L'application ne collecte, ne conserve ni ne transmet aucune donnée personnelle à son éditeur ni à quiconque, et ne contient aucune bibliothèque d'analyse d'audience, de publicité ou de rapport de plantage.

## Usage du réseau

Sans bibliothèque configurée, l'application n'utilise jamais le réseau.

Si vous configurez une bibliothèque, l'application ne parle qu'au serveur que vous lui indiquez : une adresse WebDAV (par exemple votre propre kDrive ou Nextcloud) avec le nom d'utilisateur et le mot de passe d'application que vous saisissez, ou un lien de partage public kDrive que vous collez. Elle y liste les dossiers et les livres, télécharge les livres que vous ouvrez, et écrit à côté de chaque livre un petit fichier avec vos surlignages, vos commentaires et votre position de lecture, pour que vos autres appareils les retrouvent. Si vous lui donnez aussi l'adresse de votre dossier Reader's Notes, elle y écrit une note par livre avec les passages surlignés. Chaque requête s'adresse à ce seul serveur, ne porte que ce qu'il faut pour lire ou écrire ces fichiers, et part de vous (ouverture de la bibliothèque, « synchroniser », ouverture d'un livre, surlignage). Aucun autre serveur n'est contacté.

## Données conservées sur le téléphone

Les livres que vous ajoutez, la liste de vos livres, vos positions de lecture, surlignages et commentaires, et vos réglages restent dans l'espace privé de l'application sur le téléphone. Ils sont effacés avec la désinstallation. Le mot de passe de la bibliothèque est enregistré chiffré, avec une clé gardée dans le coffre sécurisé du téléphone. La sauvegarde Android peut copier les réglages, la liste des livres et les surlignages (pas les fichiers des livres) vers le compte Google configuré sur le téléphone ; cela dépend des réglages de sauvegarde du téléphone, pas de l'application.

L'application peut exporter vos identifiants de bibliothèque dans un petit fichier, seulement à votre demande, pour qu'une autre application Reader's du même téléphone utilise le même serveur ; ce fichier va où vous choisissez de l'envoyer.

## Partage avec d'autres applications

Reader's Launcher, s'il est installé et signé par le même éditeur, peut lire la liste de vos livres et votre avancement dans chacun, pour afficher une tuile « reprendre la lecture ». Aucune autre application n'y a accès.

## Autorisations

L'application ne demande que ce qu'il faut à sa tâche : le réseau, pour la bibliothèque facultative ; un service au premier plan avec une notification discrète et un maintien éveillé, pour qu'un long inventaire de la bibliothèque se poursuive téléphone verrouillé ; les notifications, pour montrer l'avancement de cet inventaire. Les livres ouverts depuis une autre application ou partagés vers elle sont copiés dans l'application ; elle ne parcourt jamais votre stockage d'elle-même.

## Enfants

L'application ne s'adresse pas aux enfants et ne collecte de données de personne.

## Contact

Pierre Gallaz — pierregallaz@gmail.com

Code source : https://github.com/funkypitt/readers-books
