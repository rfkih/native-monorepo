# UAT Android installer downloads

This directory is bind-mounted read-only into the UAT **edge** nginx at `/srv/downloads`
(see `docker/compose.uat.yml`). The edge serves any top-level `/*.apk` URL from here —
`https://<uat-origin>/native-app-v6.apk` → `native-app-v6.apk` in this folder.

Stable links (edge redirects, `docker/uat/edge.conf`):
- **Business/Till app:** `https://<uat-origin>/native-app-latest.apk` → the current versioned APK.
  (`/native-till.apk` is a legacy alias that now points at `native-app-latest.apk`.)
- **Employee app:** `https://<uat-origin>/native-employee-app-v<versionCode>.apk` (currently v1).

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
`native-app-v<versionCode>.apk`, then bump the `/native-app-latest.apk` redirect target in
`docker/uat/edge.conf` and reload the edge (`docker exec native-uat-edge nginx -s reload`,
zero-downtime — edge.conf is a read-only mount). Delete superseded `native-app-v<old>.apk`
files once no shared links point at them (the stable `native-app-latest.apk` alias means
callers need not use a versioned URL). APK binaries are gitignored here;
`C:\Users\rifki\native-till-signing\builds\` is the source of truth.
