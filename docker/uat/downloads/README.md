# UAT Android installer downloads

This directory is bind-mounted read-only into the UAT **edge** nginx at `/srv/downloads`
(see `docker/compose.uat.yml`). The edge serves any top-level `/*.apk` URL from here —
`https://<uat-origin>/native-app-v5.apk` → `native-app-v5.apk` in this folder.

Why the edge and not the console image: the APK used to be `docker cp`'d into the console
container, and every console recreate silently dropped it; the console's SPA history
fallback then answered the `.apk` URL with `index.html`, so the "download" bounced users
to the home page. A host mount survives every rebuild/recreate of every container, and a
missing file is a real 404.

## Publishing a release

```powershell
Copy-Item C:\Users\rifki\native-till-signing\builds\native-till-v<N>-release.apk `
          "docker\uat\downloads\native-app-v<N>.apk"
```

No container restart needed — nginx reads the mount per-request. Name the file
`native-app-v<versionCode>.apk` and update the legacy-redirect target in
`docker/uat/edge.conf` (`/native-till.apk` → current version). APK binaries are
gitignored here; `C:\Users\rifki\native-till-signing\builds\` is the source of truth.
