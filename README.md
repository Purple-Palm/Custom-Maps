# Custom Maps

A personal navigation app for Android Auto, built on Mapbox. The phone app uses Jetpack Compose;
the car app uses the Android for Cars App Library with the navigation template.

**Status: stage 1 done, navigation on the phone.** The phone app shows a night map with your
location, finds addresses and places, offers routes with traffic and alternatives, and guides turn
by turn with lanes and arrival time. The car screen arrives in stage 2; the plan is in
[docs/ROADMAP.md](docs/ROADMAP.md).

## Modules

| Module | Contents |
|---|---|
| `:core-nav` | Pure Kotlin: domain models (maneuvers, lanes, route progress, camera mode, distances) and the `NavigationEngine`, `MapRenderer`, `SearchProvider` and `GuidanceAudio` interfaces. No Android dependencies. |
| `:engine-mapbox` | Android library that implements those interfaces with the Mapbox Navigation SDK. Mapbox types never leave this module. |
| `:app` | The phone app: Compose UI, settings and the wiring of all modules. The car app service will live here too. |
| `:car` (stage 2) | Car screens built on the navigation template. |

Progress by stage is tracked in [docs/ROADMAP.md](docs/ROADMAP.md); design decisions and their
alternatives are recorded in [docs/DECISIONS.md](docs/DECISIONS.md).

## Requirements

- Android Studio Quail 4 (2026.1.4) or newer. Rabbit 1 and Rabbit 2 work too.
- JDK 21 to run Gradle. The JDK bundled with Android Studio is fine.
- Android SDK Platform 37 and Build-Tools 36.0.0. Android Studio offers to install them on the first sync.
- A phone with Android 8.0 or newer. The Desktop Head Unit needs Android 9 or newer.

## Mapbox access token

- The app needs a Mapbox **public** token (`pk.…`). Create one on the
  [Mapbox access tokens page](https://console.mapbox.com/account/access-tokens/); the default public scopes are enough.
- No build contains the token: not debug builds, not CI builds, not Play builds. Enter it in the
  app, either on first launch or later in **Settings → Mapbox access token**, where it can also be
  replaced or removed.
- The token is stored only on the phone, in the app's private storage. It is excluded from cloud
  backups and from device-to-device transfers, so after a reinstall or on a new phone it has to be
  entered again.
- Secret tokens (`sk.…`) are refused. Building the project needs no Mapbox token at all: the Mapbox
  Maven repository is public.

## Install a build from GitHub Actions

Every push to `main` builds a debug APK.

1. On GitHub, open **Actions → CI** and the latest run that lists the APK under **Artifacts**: the
   latest successful run, or a newer one that failed only in the app bundle part, for example with a
   wrong release secret. Downloads need a signed-in GitHub account.
2. Under **Artifacts**, download `custommaps-debug-<run>-<commit>.apk`. It is the APK itself, not a zip.
   A name that ends in `-tempkey` means the build has a temporary key (see
   [Shared debug signing key](#shared-debug-signing-key)).
3. Open the APK on the phone (download it there, or copy it over), and allow installing apps from
   that source when Android asks.
4. Launch **Custom Maps Debug**, as the debug build is named on the phone, and paste your Mapbox
   token. The build from Google Play is named **Custom Maps**, so the two icons are told apart.

Install newer builds over the installed one; do not uninstall first. Uninstalling deletes the
token, and every new installation counts as one more monthly active user at Mapbox. Updating in
place only works when all builds are signed with the same key, see the next section.
**Settings → About** shows the version (`0.1.<run>` for a CI build) and the commit of the installed
build.

The same builds also reach the phone through Google Play, where they update by themselves; see
[Release through Google Play](#release-through-google-play).

## Using the app

1. On first launch, paste the Mapbox token, then allow location access. The map centers on you.
   Routes and guidance need **precise** location; with approximate location only, the prompt at
   the bottom offers to switch. If Android has stopped asking, the prompt opens the app's system
   settings instead, where precise location can be turned on.
2. Type an address or a place name in the search field and pick a suggestion.
3. Tap **Directions**. Up to three routes appear: the chosen one in amber, turning orange and red
   where traffic is heavy, the alternatives in grey. Tap a route, on the map or in the list, to
   choose it.
4. Tap **Start**. On Android 13 and newer the app asks once to show notifications, for the trip
   notification. The maneuver card shows the next turn, its distance and the lanes; the bottom bar
   shows the arrival time, what is left, **Overview**/**Recenter** and **End**. Right after a new
   route, as after leaving the route, the card can dim for a moment and show no distance, until
   the new route's first instruction arrives.
5. Guidance stops at the destination (**Done**) or with **End**. Both stop the trip session, and so
   does **End Navigation** in the trip notification.

The phone screen stays in portrait. If Mapbox refuses the token (mistyped or revoked), the map
stays empty and the app says so, with a button to Settings where the token can be replaced.

### Simulated drive

To test guidance without driving, turn on **Settings → Debug → Simulate drive**. **Start** then
becomes **Start simulation**: the position moves along the chosen route by itself, with the same
maneuver card, lanes and arrival. Each simulated drive is a trip session and is billed like a real
one; the debug section counts them with the rest.

During a simulated drive a small slider sits over the guidance bar, like a video's progress bar:
amber for the part driven, a white dot where the car is. Drag the dot, or tap the slider, to send
the car forward or back along the route; the map, the car and the card go there at once, and the
drive goes on by itself from there. The slider's end stops a few seconds before the destination, so
the drive still arrives by itself. A seek is no new trip: it keeps the trip session and the route
and asks for no route; from the first seek of a drive to its end the navigator does not reroute.
DRIVES.md shows how to use it to check a turn at home. A real drive has no slider.

### Offline

The app saves what a drive needs for stretches without a connection:

- when routes are shown, the street map along the first 100 km of each, starting where they
  start; the download goes on after **Start** until it is done, and stops when the drive ends;
- during guidance, the map and the road data around the car, along the route and around the
  destination.

Both use mobile data when the phone is not on Wi-Fi, by estimate tens of MB for a new area, and
what is saved is used again on later drives. Mapbox does not charge extra for it. The debug log
records how much the download along the shown routes really loaded. Mapbox gives no such count for
what guidance keeps ahead, so during guidance the log also notes, once a minute, how much the app
has received in all.

Three seconds after the app loses its internet a chip on the map says so: a sentence with the
reason under the search bar, or a short **Offline** tab under the maneuver card during guidance.
The map then shows only what is saved on the phone, and guidance continues.

### Debug log

The app records a detailed log on the phone, so a drive can be examined afterwards:

- every route request and answer, with each step: road, maneuver, lanes, distance, time and traffic;
- the route you picked, Start, End and arrival;
- every second of guidance: position, speed, the road you are on, the speed limit, the next
  maneuver and what is left, and how old each position fix was;
- off-route moments and reroutes, searches, trip sessions and errors;
- crashes, written before the app closes, and at each start how earlier runs ended as Android
  recorded it (a crash, the system freeing memory, a swipe from Recents) and why this one started;
- the phone's state: on screen or not, the screen, charging, Power saving, heat and memory, with a
  sample once a minute during guidance that also has the bytes the app has received, and whether
  Android's animations run;
- the app's network on every change, and the phone settings that can keep only this app offline;
- what is saved for stretches without a connection: each download along the shown routes with its
  size and the bytes it loaded, and when the offline chip showed;
- one camera line a second during guidance while the map is on screen: where the camera is and
  where it is heading, how smoothly the camera and the car move, and the map's frame rate;
- one summary line at the end of each drive: how it ended, how long and how far, reroutes, the
  time without internet, and the data and battery it took;
- map errors, the first of each kind in full and the repeats as one count a minute.

To hand it over, open **Settings → Debug → Share debug log** and pick an app in the share sheet,
for example Google Drive. Each day becomes one text file, `custom-maps-debug-<date>.txt`, with one
JSON event per line. The log never leaves the phone otherwise: it is kept out of backups and device
transfers, days that are over are kept compressed, the oldest days are deleted once it passes 50 MB
on the phone, and **Clear** deletes it together with the copies made for sharing. The shared text
files are several times larger than the size Settings shows. It never contains the Mapbox token;
token-shaped text is masked in every line as a last safeguard.

Debug builds also offer **Settings → Debug → Crash now**, which closes the app as a crash would, to
check on the phone that the crash and how the app ended are recorded.

### Backups

Android backs up only the app's settings and its trip session log. The token, the debug log and
the data the Mapbox SDKs keep on the phone (map and routing caches) stay out of cloud backups and
device transfers. The app keeps no search history.

### Privacy

The [privacy policy](docs/PRIVACY.md) says what stays on the phone, what Mapbox receives and how
to turn off Mapbox's telemetry; **Settings → About → Privacy policy** opens it.

## Shared debug signing key

Android installs an update over an existing app only if both are signed with the same key. Without
the setup below, every CI run signs the APK with a new temporary key, and each new build would have
to replace the app by uninstalling it first. This key is for debug builds only; Google Play builds
are signed with a different key, the upload key (see
[Release through Google Play](#release-through-google-play)).

1. Create the key. `keytool` ships with every JDK, including the one inside Android Studio
   (`<Android Studio>/jbr/bin/keytool`; on macOS
   `Android Studio.app/Contents/jbr/Contents/Home/bin/keytool`). It asks for a password twice:

   ```bash
   keytool -genkeypair -v -storetype PKCS12 -keystore custommaps-debug.p12 -alias custommaps-debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Custom Maps Debug"
   ```

   Keep `custommaps-debug.p12` outside the repository and the password in a password manager. In a
   PKCS12 keystore the key password is the same as the keystore password.

2. Encode the file as one line of base64.

   macOS, straight to the clipboard:

   ```bash
   base64 -i custommaps-debug.p12 | pbcopy
   ```

   Linux, in the folder that holds the key file and never inside this repository (copy the content
   of the file, then delete it):

   ```bash
   base64 -w 0 custommaps-debug.p12 > custommaps-debug.b64
   ```

   Windows PowerShell, straight to the clipboard:

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\custommaps-debug.p12")) | Set-Clipboard
   ```

3. On GitHub open the repository's **Settings → Secrets and variables → Actions → New repository
   secret** and create four secrets:

   | Name | Value |
   |---|---|
   | `DEBUG_KEYSTORE_BASE64` | the base64 text from step 2 |
   | `DEBUG_KEYSTORE_PASSWORD` | the password from step 1 |
   | `DEBUG_KEY_ALIAS` | `custommaps-debug` |
   | `DEBUG_KEY_PASSWORD` | the same password as `DEBUG_KEYSTORE_PASSWORD` |

4. Start a build: **Actions → CI → Run workflow**, or push a commit. From now on every APK is signed
   with this key. If a build signed with a temporary key is installed, uninstall it once and install
   the new one.

5. Optional: to let builds from Android Studio install over CI builds too, put the same four values
   into `local.properties` as shown in [local.properties.example](local.properties.example). Use an
   absolute path with forward slashes, also on Windows.

Set all four values or none: a partial setup fails the build with a message listing what is missing.

Until the key is set up, the APK's name ends in `-tempkey`, the run's page shows a warning, and
**Settings → Debug** in such a build says that the next CI build needs an uninstall, which deletes
the token, the settings and the debug log: share the debug log first.

## Release through Google Play

Android Auto in a real car runs only apps installed from Google Play, and Play keeps the app up to
date by itself. The Play build reaches the phone through Google Play's **internal testing** track.
It is built from the same code as the debug APK, with four differences: its package is
`io.github.purplepalm.custommaps` (no `.debug`), its name on the phone is **Custom Maps** (the debug
APK's is **Custom Maps Debug**), it is not debuggable (no **Crash now**), and it is signed with the
upload key below and then by Google.

The Play app and the debug APK are two apps: both can stay installed, each keeps its own token and
settings (paste the token once more in the Play app), and each installation counts as one Mapbox
monthly active user.

### The upload key and the release secrets

Google Play signs the APKs it delivers with an app signing key that Google generates and keeps
(Play App Signing). CI signs each app bundle with the **upload key**, which shows Google that the
bundle comes from the owner. It is a PKCS12 keystore with an RSA 2048 key, not the debug key. The
owner keeps the upload key; CI has it as secrets. Its certificate's SHA-256 fingerprint, which Play
Console shows on the Play app signing page, is
`AB:19:35:13:3F:7C:B9:D1:8C:09:B2:C0:F6:72:60:28:8D:7D:33:4C:E5:DD:D5:C7:4C:24:53:CA:AA:7E:3D:00`.

The four repository secrets that hold it (**Settings → Secrets and variables → Actions**):

| Name | What it is |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | the keystore file as one line of base64, encoded as in step 2 of the debug key |
| `RELEASE_KEYSTORE_PASSWORD` | the keystore's password |
| `RELEASE_KEY_ALIAS` | the alias of the key in the keystore |
| `RELEASE_KEY_PASSWORD` | the key's password: in a PKCS12 keystore the same as the keystore password |

Set all four or none. Without them CI still builds the debug APK, but no app bundle; with only
some, the build fails before anything is built and names the missing secrets. Only the Gradle run
that builds the app bundle is given the upload key: CI decodes the keystore just before that run
into a private folder of the runner's temporary directory and deletes it right after, also when the
run is cancelled, and it passes the passwords to that run alone, only as environment variables. The
tests of the CI scripts and the debug build, its unit tests and lint do not get them in their
environment. That keeps the upload key out of what they log, report or cache, not away from hostile
code in the build job, which can read every secret of the job (decision 42 in
[DECISIONS.md](docs/DECISIONS.md)). A release build on a computer takes the same four values in
`local.properties`, with `RELEASE_KEYSTORE_FILE` instead of the base64 (see
[local.properties.example](local.properties.example)).

A wrong release secret, or any other failure in the app bundle part, turns the run red after the
debug APK is published: that APK passed its tests, lint and 16 KB check and can be installed, but no
bundle is published. Correct the secret and use **Re-run failed jobs**, or delete all four secrets
to go back to debug builds only.

If the upload key is ever lost or leaked, Play Console resets it (**Request upload key reset** on the
Play app signing page). The app signing key stays with Google, so the installed app keeps updating.

### The app bundle

With the four secrets, every CI run also builds the app bundle for Google Play, with both ABIs (Play
sends each phone only its own), checks that every native library in it is 16 KB aligned, and
publishes it for 30 days as the artifact `custommaps-release-<versionCode>-<commit>.aab` of the run.
Like the APK, any signed-in GitHub user can download it; it holds no secret, only the compiled public
code, the commit and the public part of the upload certificate.

Every CI run gets a higher versionCode, as Google Play needs: the run number times 100 plus the run
attempt, so a re-run also counts higher. The version name is `0.1.<run>`; **Settings → About** shows
it with the commit. Debug builds keep versionCode 1, so a build from Android Studio still installs
over a CI build. A release build on a computer is versionCode 1 and is not meant for Google Play.

### The first release, by hand

Google Play takes uploads through its API only for an app whose first release was rolled out in
Play Console. Once:

1. Download the newest `custommaps-release-<versionCode>-<commit>.aab` from a green CI run.
2. In Play Console open the app, then **Test and release → Testing → Internal testing → Create new
   release**. Keep Play App Signing with the key Google generates, the default for a new app. Upload
   the bundle and click **Next**. On **Preview and confirm** click **Start rollout** (or
   **Publish**): the release goes out at once. If only **Save** is offered, the changes need review:
   click **Save**, then **Send for review** on the **Publishing overview** page. Changes are not sent
   for review by themselves.
3. Fill in the foreground service declaration (**App content**) when Play Console asks for it, and
   before the first automatic upload at the latest. Declare the location type: the Mapbox
   navigation service keeps turn-by-turn guidance going after the driver taps **Start**, and ends
   with the drive. Google asks for a link to a short video that shows a drive being started. If Play
   Console asks about the advertising ID, the answer is No: the app has none.
4. On the **Testers** tab create an email list with the Google account the phone's Play Store uses,
   select it, **Save changes**, and copy the shareable link. Open the link on the phone, accept the
   invitation and install Custom Maps from the Play Store page it leads to. The link works once the
   release is out; the first time that can take a few hours.

### Automatic updates

After the first rollout, CI can publish each green build of `main` on internal testing. It needs a
Google Cloud service account that Play Console allows to release to testing tracks:

1. In Google Cloud Console: create a project, enable the **Google Play Android Developer API**, and
   under **IAM & Admin → Service accounts** create a service account with no Google Cloud roles. Under
   its **Keys** tab choose **Add key → Create new key → JSON**.
2. In Play Console: **Users and permissions → Invite new users**, enter the service account's email
   address, add Custom Maps under **App permissions** with **Release apps to testing tracks** and
   **View app information (read-only)**, and send the invitation.
3. On GitHub open **Settings → Environments** and the environment `google-play`; the first publish
   job creates it, otherwise create it with **New environment**. First limit it to `main`: under
   **Deployment branches** choose **Selected branches and tags**, then **Add deployment branch or tag
   rule**, Branch, `main`, **Add rule**. Only then, under **Environment secrets**, add the secret
   `PLAY_SERVICE_ACCOUNT_JSON_BASE64`: the JSON key as one line of base64, encoded as in step 2 of
   the debug key in the folder the browser saved it to, never inside this repository. Then delete
   the JSON file and any `.b64` file for good and clear the clipboard. Never create a repository
   secret of that name: any job of any branch could read it.

From then on the **publish** job of each green run on `main` (a push, or **Run workflow** on `main`)
uploads the run's app bundle with [`scripts/play-upload.sh`](scripts/play-upload.sh) and makes it
the release of internal testing. The update usually reaches the phone's Play Store within minutes,
though Google says that changes can take several hours; the phone installs it as its Play Store
settings say. The job:

- runs only after the build, its tests, lint and the 16 KB check passed, and only for `main`; until
  the key is set it only says so and publishes nothing;
- is the only job that uses the environment `google-play`, and only jobs that use it can read the
  key: the build job, which runs Gradle, never can, and the branch rule keeps every other branch out;
- publishes only on internal testing, never on closed or open testing or production;
- runs one upload at a time; one that waits gives way to a newer one, and a bundle that is not newer
  than the one on internal testing is not uploaded, so the track never goes back to an older build;
- never cancels changes that are in review in Play Console; the upload then fails, and the first
  run after the review publishes;
- sends the final commit to Google Play only once; when no clear answer comes back, it says that the
  build may already be on internal testing, and **Re-run failed jobs** then publishes it or finds it
  there;
- stops within seconds when the run is cancelled and deletes its unfinished edit, so nothing is
  published; if the final commit was already sent, it says so, as when no answer comes back;
- says in plain words what to do when Google refuses, for example when the first rollout by hand is
  missing or the service account lacks the permission.

When the publish job fails, the build job stays green and the bundle stays in the run's artifacts,
to upload by hand or with **Re-run failed jobs**. **Actions → CI → Run workflow** on `main` builds
and publishes the newest commit again, with a new versionCode.

### Production

Internal testing is enough for the owner's phone and car. To publish on Google Play for everyone, a
personal developer account first has to run a closed test with at least 12 testers who stay opted
in for 14 days in a row, and then apply for production in Play Console. Closed testing also needs
the store listing, the content rating, the Data safety form and the link to the
[privacy policy](docs/PRIVACY.md).

## Build and run locally

- Android Studio: open the project, wait for the Gradle sync, choose the `app` run configuration
  and your phone, then Run.
- Command line, with the phone connected over USB:

  ```bash
  ./gradlew installDebug
  ```

- Unit tests and lint:

  ```bash
  ./gradlew testDebugUnitTest :core-nav:test :app:lintDebug
  ```

- 16 KB page size check of the APK (Linux, needs `readelf`):

  ```bash
  scripts/check-16kb-alignment.sh app/build/outputs/apk/debug/app-debug.apk
  ```

## Desktop Head Unit (DHU)

The car screens arrive in stage 2. Until then the DHU connects, but Android Auto does not list this
app yet. To prepare:

1. On the phone, enable the developer options and USB debugging, and update Android Auto. Then turn
   on Android Auto developer mode: open the Android Auto settings (on Android 10 and newer:
   **Settings → Apps → See all apps → Android Auto → Advanced → Additional settings in the app**),
   tap **Version and permission info** 10 times and confirm.
2. On the computer, install **Android Auto Desktop Head Unit Emulator** from Android Studio's
   **SDK Manager → SDK Tools**, or with `sdkmanager "extras;google;auto"`. It is installed into
   `<sdk>/extras/google/auto/`. On macOS and Linux make it executable with
   `chmod +x desktop-head-unit`. Linux also needs GLIBC 2.32 or newer and the `libc++1` and
   `libc++abi1` packages.
3. Connect the phone over USB and start the DHU from that directory in accessory mode (recommended):

   ```bash
   ./desktop-head-unit --usb
   ```

   Alternatively use ADB tunneling: in Android Auto's overflow menu choose **Start head unit
   server**, check that **Add new cars to Android Auto** is enabled, then run:

   ```bash
   adb forward tcp:5277 tcp:5277
   ```

   ```bash
   ./desktop-head-unit
   ```

4. Screen profiles for 800×480 and 1280×720 head units will be added under `dhu/` in stage 2 and
   used with `-c`, for example `./desktop-head-unit --usb -c /path/to/repo/dhu/800x480.ini`.
5. Switch between day and night with the `day` and `night` console commands, or Shift+N and Ctrl+N.

In a real car, a navigation app built with the Car App Library has to come from Google Play: the
build from the internal testing track does (see
[Release through Google Play](#release-through-google-play)).

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every push to `main` and on demand.
It builds the debug APK for arm64 phones (every current phone), runs the unit tests and lint, checks
16 KB page alignment and uploads the APK, unzipped, as an artifact that is kept for 30 days. Local
builds also include x86_64 for the emulator. With the release secrets it also builds, checks and
uploads the app bundle for Google Play, and with the Google Play key a second job publishes it on
internal testing (see [Release through Google Play](#release-through-google-play)). The steps
themselves live in [`scripts/ci.sh`](scripts/ci.sh), which first tests the CI scripts, the upload to
Google Play included, against a local stand-in for Google's servers. CI needs no Mapbox token, and
all signing secrets are optional.

## Mapbox usage

- Mapbox bills navigation by monthly active users and trips, with a free tier (see
  [mapbox.com/pricing](https://www.mapbox.com/pricing)). Free drive counts as a trip too.
- The app starts a trip session only when you press Start, once the navigator has the route, so a
  drive is one active guidance session and never a free drive first. It stops the session on
  arrival and on End. From stage 2, when the car disconnects mid-drive, guidance continues on the
  phone, so the session keeps running until arrival or End. Every start is logged, and **Settings → Debug** shows
  the count for the month, the latest starts and a link to the
  [Mapbox statistics page](https://console.mapbox.com/account/statistics/), which has the billed numbers.
  The log starts empty with each installation, so after one during the month the count says since
  which day it runs.
- Route and search requests made outside a trip session are billed per request.
- Every installation counts as a user: install updates over the app instead of reinstalling it.

## Attribution

Maps from Mapbox must show the Mapbox logo and the © Mapbox © OpenStreetMap attribution, behind the
(i) button. This applies to every map made with Mapbox, free or paid, commercial or not. Mapbox
allows moving the logo but not resizing, recolouring or fading it. On the phone the logo and the (i)
button keep a fixed strip at the bottom left, above the navigation bar or the keyboard, and the
panels stay above it; the car screen gets the same rule in stage 2.
