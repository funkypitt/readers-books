# Privacy policy — Reader's Books

*Last updated: 8 November 2026*

Reader's Books (`com.freedomfighter.readersbooks`) is published by Pierre Gallaz. It is an e-book reader made for e-ink screens: it shows the books you add to it, page by page, and keeps your place, your highlights and your comments. It is free, has no ads, no account and no tracking.

## Data the app collects

None. The app does not collect, store or transmit any personal data to its publisher or to anyone else, and it contains no analytics, advertising or crash-reporting library.

## Network use

Without a library set up, the app never uses the network.

If you set up a library, the app talks only to the server you give it: a WebDAV address (for example your own kDrive or Nextcloud) with the user name and application password you type, or a public kDrive share link you paste. It lists the folders and books there, downloads the books you open, and writes next to each book a small file with your highlights, comments and reading position, so that your other devices find them. If you also give it the address of your Reader's Notes folder, it writes there one note per book with your highlighted passages. Every request is addressed to that server alone, carries only what is needed to read or write those files, and is started by you (opening the library, « sync now », opening a book, highlighting). No other server is contacted.

## Data kept on the phone

The books you add, the list of your books, your reading positions, highlights and comments, and your settings stay in the app's private storage on the phone. They are deleted when the app is uninstalled. The library password is stored encrypted with a key held in the phone's secure keystore. Android's own backup may copy the settings, the list of books and the highlights (not the book files themselves) to the Google account configured on the phone; that is controlled by the phone's backup settings, not by the app.

The app can export your library credentials to a small file, only when you ask it to, so that another Reader's app on the same phone can use the same server; that file goes where you choose to send it.

## Sharing with other apps

Reader's Launcher, when installed and signed by the same publisher, may read the list of your books and how far you are in each, to show a « continue reading » tile. No other app has access.

## Permissions

The app asks only for what its job needs: the network, for the optional library; a foreground service with a quiet notification and a wake lock, so that a long listing of the library goes on while the phone is locked; notifications, to show the progress of that listing. Books opened from another app or shared to it are copied into the app; the app never browses your storage on its own.

## Children

The app is not directed at children and collects no data from anyone.

## Contact

Pierre Gallaz — pierregallaz@gmail.com

Source code: https://github.com/funkypitt/readers-books
