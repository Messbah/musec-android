# Building MuSec Browser for Android Phone & Android TV (Zero Java on This PC)

Because Java (JDK 17) cannot be installed on this computer, this project is configured to compile the signed **`MuSec-Android-Phone-TV.apk`** completely in the cloud using **GitHub Actions (free tier)**.

## Option 1: Build APK in the Cloud via GitHub Actions (Recommended — Free & Zero Local Install)

1. Create a new (private or public) GitHub repository (e.g., `musec-android`).
2. Upload or push the contents of `mmuBrowser-android/` to that repository's `main` branch.
3. Open the repository on GitHub → click the **Actions** tab → click **Build MuSec Android (Phone + TV) APK** (it starts automatically on push, or click **Run workflow**).
4. Wait ~3 minutes for the cloud Ubuntu runner (which has JDK 17 + Android SDK 35 + Gradle 8.9 pre-installed) to finish.
5. Under **Artifacts**, download **`MuSec-Android-Phone-TV-APK`**, unzip it, and you have **`MuSec-Android-Phone-TV.apk`**!

## Installing the APK

### On Your Android Phone
1. Transfer `MuSec-Android-Phone-TV.apk` to your phone (or download it directly from GitHub Actions on your phone).
2. Tap the APK file and enable **Install unknown apps** when prompted.
3. Launch **MuSec Browser**!

### On Your Android TV / Google TV
1. Install **Send Files to TV** (or **Downloader by AFTVnews**) from the TV Play Store.
2. Send `MuSec-Android-Phone-TV.apk` from your phone to your Android TV and install it.
3. **MuSec Browser** appears natively on your Android TV home screen (`LEANBACK_LAUNCHER` banner included!).
4. Use your TV remote's **D-Pad** to navigate, **Back** to go back or close panels, and **Menu** (or highlight the top-right **🛡️ Shield** pill) to configure the **YouTube Kids & Content Shield** and **Parental PIN**.

## Option 2: Build in WSL / Another Linux or Cloud Machine

If you ever use WSL Ubuntu or a cloud devbox (GitHub Codespaces / Gitpod):
```bash
docker build -f Dockerfile.build -t musec-android-builder .
docker create --name musec-out musec-android-builder
docker cp musec-out:/out/MuSec-Android-Phone-TV.apk ./MuSec-Android-Phone-TV.apk
docker rm musec-out
```
