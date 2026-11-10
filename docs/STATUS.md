# Status: stage 1.1 in progress (2026-11-10)

The owner answered the checkpoint questions on 2026-11-10 and said to continue. Stage 1.1, the next
phone build, is being built; stage 2 (Android Auto) follows it.

## Where things stand

- **Stage 1 (phone navigation) is done** and on `main`; CI builds the APK on every push.
- **Two real drives** were logged and analysed: [DRIVES.md](DRIVES.md).
- **The guidance camera was redesigned** from that analysis: [CAMERA.md](CAMERA.md).
- **Stage 2 (Android Auto) is designed**, with the owner's choices: [STAGE-2.md](STAGE-2.md).
- Decisions 26 to 36 in [DECISIONS.md](DECISIONS.md) record what was chosen and why.

## The owner's answers of 2026-11-10

1. **System animations** were off through an accessibility switch (Reduce animations). The owner
   turned them back on, and guidance became smooth. The app never overrides that setting.
2. **Lane size at turns:** a **Lane zoom** setting with two choices, **Default** (today's zoom) and
   **Large**: a very close view where the junction fills the screen and the lanes are clearly
   visible. Large also zooms in on freeways where lanes split off while the route goes straight on.
3. **Freeways:** the long view ahead stays as it is.
4. **Old routes at Start:** start at once and refresh the routes inside the trip. New: a **trip
   history**. Every trip is recorded on the phone (where, when, how often); search shows it; the app
   suggests likely destinations by time, day and frequency; and if the app stays open and the car
   drives off toward one of them, the predictor grows more confident until guidance starts by
   itself. No End or Cancel within the first minute counts as a successful prediction. The map must
   stay smooth through all of it.
5. **Arriving on the right side:** no.
6. **Internal App Sharing in stage 2:** yes.
7. **Taking the car back** when Custom Maps is opened on the car screen again: yes.
8. **Go there now** is offered on the phone and in the car. Links and addresses shared into the app
   from other apps come with stage 2, as designed ([STAGE-2.md](STAGE-2.md), shared links).
9. **Order:** stage 1.1 first, then stage 2.

## What is being built

Stage 1.1, in this order, each part pushed to `main` once CI is green:

1. Diagnostics: crashes, earlier exits, device state, network, a camera line, folded map errors.
2. Camera styles in Settings, with Smooth follow as the default; the map follows the car before Start
   too.
3. The **Then** chip under the maneuver card.
4. Lane zoom (Default, Large) and lane markings on the map where Mapbox has them.
5. Routes that end where a car stops, the place pin, and Start at once with older routes.
6. Offline data along the route and an offline chip.
7. Trip history, history in search, suggestions, the "Heading to" hint and guidance that starts by
   itself.
8. Docs and the checklist for the next drive.

Then a test drive with that build, and stage 2, the car screen, checked on the Desktop Head Unit.

## Checks on the phone

The full list, with Samsung's menu paths, is in [DRIVES.md](DRIVES.md) ("What the owner checks on
the phone"). In short:

- **Mobile data for the app:** the first drive lost the internet at the edge of home Wi-Fi and never
  got it back. Does the phone have a SIM with a data plan, and was Mobile data on? Check Settings →
  Connections → Data usage: Mobile data usage (did Custom Maps use any data on the day of the first
  drive?), Allowed networks for apps → Custom Maps (must be Mobile data or Wi-Fi), Data saver and
  any data limit; and Settings → Apps → Custom Maps → Mobile data → Allow background data usage.
  Quick test: turn Wi-Fi off, open Custom Maps and move the map to a town it has never shown. Does
  it load?
- **Battery:** Settings → Apps → Custom Maps → Battery → Unrestricted, and add Custom Maps to Never
  sleeping apps (Settings → Battery → Background usage limits).
- **The restart before the first drive:** did the app close by itself ("Custom Maps keeps
  stopping"), was it swiped away from Recents or force-stopped, or was a setting changed then (a
  permission, mobile data, battery)? From the next build the log records the reason itself.
- **Also:** any VPN, ad blocker or firewall; Settings → Display → Motion smoothness, Adaptive
  (120 Hz) or Standard (60 Hz); whether the phone was charging in a mount, warm, or in Power saving
  during the drive; where Google Maps ran during the first drive (this phone, the car display or
  another phone) and whether it showed live traffic; and whether the map was touched about 22 s
  before End, when the camera stopped following.

## For stage 2, when convenient

Create the private repository for trip logs and a fine-grained token, as described in
[STAGE-2.md](STAGE-2.md) (GitHub log upload). Keep the token on the phone; it is pasted into the app
once the upload is built.
