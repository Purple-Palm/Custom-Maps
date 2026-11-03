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
   chip, routes that end at the entrance, offline downloads along the route and an offline chip,
   and diagnostics that survive a crash.
2. **A test drive** with that build: try each camera style, search the store from the first drive
   by its address and by its name, and share the log.
3. **Stage 2, the car screen**, checked on the Desktop Head Unit. The first test there is whether
   Start pressed in the car works with the phone locked (see STAGE-2.md, the start problem).

## Questions for the owner

1. **System animations.** The first drive's log shows the phone's system animations were most
   likely off: Settings → Accessibility → Visibility enhancements → Remove animations, or Developer
   options → Animator duration scale = Off. Was that so, and is it deliberate? If it is, should the
   app keep its map motion smooth anyway (a setting that animates the car marker itself)?
2. **Lane size at turns.** How large should lanes get before a turn? The proposal is that the
   junction fills the view and a lane is about 4 mm wide on the screen.
3. **Freeways.** On the freeway the camera zoomed out to show several kilometres ahead. Keep that,
   or stay closer, about 3 to 4 km ahead?
4. **An old route preview at Start.** When the preview is a few minutes old or the car has moved
   since, should Start wait a few seconds for a fresh route, or start at once and switch to a fresh
   route a moment later? Both cost nothing at this use. The proposal is to start at once.
5. **Arriving on the right side.** For house addresses, should routes always arrive with the place
   on the right (no crossing traffic), even if that adds a loop around the block? The proposal is no.
6. **The real car before stage 3.** A car runs only apps installed from Google Play. When the Play
   account is verified, set up Internal App Sharing during stage 2, so the car screen can be tried
   in the real car early? The proposal is yes.
7. **Order of work.** Stage 1.1 first, then stage 2? The proposal is yes.

## Checks on the phone

- **Animations:** see question 1. For the next drive, set Animator duration scale to 1x and turn
  Remove animations off, unless that is deliberate.
- **Mobile data for the app:** the first drive lost the internet at the edge of home Wi-Fi and never
  got it back. Check Settings → Connections → Data usage: Mobile data usage (does Custom Maps show
  any data?), Allowed networks for apps → Custom Maps (must be Mobile data or Wi-Fi), Data saver,
  and Settings → Apps → Custom Maps → Mobile data → Allow background data usage.
- **Battery:** Settings → Apps → Custom Maps → Battery → Unrestricted, and add Custom Maps to Never
  sleeping apps (Battery → Background usage limits).
- **The restart at the start of the first drive:** was the app swiped away or force-stopped, or was
  a setting changed then? From the next build the log records the reason itself.
- **Display:** Settings → Display → Motion smoothness: Adaptive (120 Hz) or Standard (60 Hz)?

## For stage 2, when convenient

Create the private repository for trip logs and a fine-grained token, as described in
[STAGE-2.md](STAGE-2.md) (GitHub log upload). Keep the token on the phone; it is pasted into the app
once the upload is built.
