# Book Sender

Book Sender is a small Android app for finding a requested book in a folder you choose and preparing a message with the book file for personal WhatsApp.

## How to use it

1. Open **Settings → Choose book folder** and grant access to the folder containing your books.
2. Paste/type a request in **Search**. If WhatsApp or Android offers a system share option for the request text, you can explicitly choose **Book Sender** instead; otherwise copy the text first. Sharing text to the app is always a manual action.
3. Review the matching PDF or EPUB filenames and tap **Prepare reply** for the right book.
4. Edit the suggested message if needed, then tap **Open personal WhatsApp to send**.
5. In WhatsApp, choose the recipient and tap **Send** yourself.

## Privacy and safety

- Book Sender does **not** read WhatsApp chats, notifications, or message history. It only receives text when you explicitly share it to the app or type it yourself.
- The app searches filenames in the folder you select. It does not inspect the contents of PDFs/EPUBs or upload your library.
- Sending is never automatic: the app opens the personal WhatsApp share screen, and you choose the recipient and confirm the final Send action there.
- The app cannot tell whether WhatsApp sent or delivered the file, so its history records only that a share was opened—not that a message was sent.
- Only PDF and EPUB files are searched. Matching is based on title words in filenames, so a book may not appear if its filename does not include recognizable title words.

## Build

The GitHub Actions workflow builds a debug APK with `gradle assembleDebug --no-daemon`. With Java 17 and Gradle 8.7 installed, the same command can be run from the repository root.
