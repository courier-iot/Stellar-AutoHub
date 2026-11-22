# Stellar AutoHub

Upload a zip, files or a folder from your phone straight into a GitHub repo, watch the Actions build, read the errors, and install the APK.

## First-time setup

1. **Upload this project** to a new repo (e.g. `Stellar-AutoHub`) on `main`. Include the `.github` folder.
2. **Signing secrets (optional for the very first build):**
   - If you already have a keystore (e.g. the one Stellar Simulcast uses), add the same 4 secrets here:
     `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
   - If you don't, the first build is signed with a temporary debug key. Install it, create a key in
     **AutoHub → 🔒 Keys**, apply it to this repo in **Secrets**, bump the version and build again.
     Uninstall the debug build once, then install the signed one.
3. **GitHub sign-in:** github.com → Settings → Developer settings → OAuth Apps → New OAuth App
   (name: Stellar AutoHub, homepage + callback: `https://github.com`), tick **Enable Device Flow**,
   copy the **Client ID** into AutoHub. You can paste a personal access token instead
   (classic: `repo` + `workflow`).

## How uploads work

- Commits use the Git Data API: one commit per upload, with no 100-file limit. Hidden folders like `.github` are included.
- The preview shows **New / Changed / Delete / Identical / Skipped** before anything is sent. Identical files are never re-sent.
- A full-project zip or folder can **mirror** the repo, removing old files. You pick which ones, and `README*` / `LICENSE*` are protected by default. Loose files only **patch**.
- A wrapping top folder (`MyApp/…`) is stripped automatically.
- Keystores, `build/`, `.gradle/`, `local.properties`, `__MACOSX` and `.DS_Store` are never uploaded.
- You can share a zip from any app to AutoHub. It guesses the repo from the app's `applicationId`.

## Builds

- Builds are watched in the background, with a notification on pass or fail.
- On failure you get the failed step, annotations, highlighted error lines, **Copy for Claude**, **Share log**, and Re-run failed / all.
- On success it offers **Install** when the app isn't on the phone or is older. If no release was published, it can install the APK from the build artifact.

## Secrets

The secrets screen reads the workflows, lists every `secrets.NAME` they use, and marks each one set or missing. **Apply signing key** fills the signing secrets automatically by matching their names. Values are encrypted on the phone (libsodium sealed box) before upload.
