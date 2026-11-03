# Status: checkpoint of 2026-11-03

Work is **paused** at the owner's request. Nothing is built until the owner answers the questions
below and says to continue.

## Where things stand

- **Stage 1 (phone navigation) is done** and on `main`; CI builds the APK on every push.
- **Two real drives** were logged and analysed: [DRIVES.md](DRIVES.md).
- **The guidance camera was redesigned** from that analysis: [CAMERA.md](CAMERA.md).
- **Stage 2 (Android Auto) is designed**, with the owner's choices: [STAGE-2.md](STAGE-2.md).
- Decisions 26 to 36 in [DECISIONS.md](DECISIONS.md) record what was chosen and why.

## What comes next

1. **Stage 1.1, the next phone build** ([ROADMAP.md](ROADMAP.md)): camera styles in Settings with
   the new Smooth follow camera as the default, a gradual tilt and more zoom at turns, the **Then**
   chip, routes that end where a car stops for the place, a refresh of old routes at Start, offline
   downloads along the route and an offline chip, and diagnostics that survive a crash.
2. **A test drive** with that build: try each camera style, search the store from the first drive
   by its address and by its name, and share the log (see "What the next drive tests" in
   [DRIVES.md](DRIVES.md)).
3. **Stage 2, the car screen**, checked on the Desktop Head Unit. The first test there is whether
   Start pressed in the car works with the phone locked (see STAGE-2.md, the start problem).

## Questions for the owner

Where a question has a proposal, "as proposed" is enough as an answer.

### Camera

1. **System animations.** Both drives fit only system animations switched off: Settings →
   Accessibility → Vision enhancements → Remove animations (older menus: Visibility enhancements;
   some versions say Reduce animations), or Settings → Developer options → Animator duration
   scale = Off. Was that so, and is it deliberate? If it is, should the drawn car stay smooth
   anyway, as a separate setting? The proposal: turn animations back on for the next drive, and no
   override of the setting unless the owner asks for one, because Remove animations is an
   accessibility choice.
2. **Lane size at turns.** How large should lanes get before a turn? Today a lane is under 1 mm on
   the screen. The proposal is that the junction fills the view, with a lane about 4 mm wide (zoom
   about 18.75). The closer the zoom, the less road around the junction fits on the screen.
3. **Freeways.** On the freeway the camera zoomed out to show several kilometres ahead. Keep that,
   or stay closer, about 3 to 4 km ahead?

### Routes

4. **An old route preview at Start.** When the preview is a few minutes old or the car has moved
   since, should Start wait a few seconds for a fresh route, or start at once and switch to a fresh
   route a moment later? Both cost nothing at this use. The proposal is to start at once.
5. **Arriving on the right side.** For house addresses, should routes always arrive with the place
   on the right (no crossing traffic), even if that adds a loop around the block? The proposal is no.

### Car (stage 2)

6. **The real car before stage 3.** A car runs only apps installed from Google Play. When the Play
   account is verified, set up Internal App Sharing during stage 2, so the car screen can be tried
   in the real car early? The proposal is yes.
7. **Taking the car back.** After another navigation app takes over in the car, Custom Maps keeps
   guiding on the phone. When the driver opens Custom Maps on the car screen again during the
   drive, should it take the car back (the routing card, the turn notifications and the cluster)?
   The proposal is yes.
8. **Where Go there now asks.** A link shared during a drive is confirmed on the phone, where it
   arrived. Should the car show the offer too? The proposal is the phone only.

### Order

9. **Order of work.** Stage 1.1 first, then stage 2? The proposal is yes.

## Checks on the phone

The full list, with Samsung's menu paths, is in [DRIVES.md](DRIVES.md) ("What the owner checks on
the phone"). In short:

- **Animations:** see question 1. For the next drive, set Animator duration scale to 1x and turn
  Remove animations off, unless that is deliberate.
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
