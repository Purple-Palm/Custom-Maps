# Privacy policy

Custom Maps is a navigation app for Android, a personal project published in the GitHub repository
[Purple-Palm/Custom-Maps](https://github.com/Purple-Palm/Custom-Maps). It is distributed through
Google Play's internal testing and as test builds made by the repository's CI; all of them work as
described here. This policy says what the app keeps on the phone, what leaves the phone and why, and
how to delete it.

Last updated: 2026-11-13. It describes the app as it is today; see [Changes](#changes).

## In short

- The app has no account, no ads, no analytics or crash reporting service and no server of its own,
  and it sends the developer nothing. Google Play shows the developer some reports that Google itself
  collects; see [No other third party](#no-other-third-party).
- Maps, routes, traffic and search come from Mapbox. To provide them, the Mapbox software inside the
  app sends Mapbox the phone's location, what you search for, where you plan routes to, the Mapbox
  access token in use, and usage and device details. You can turn off Mapbox's telemetry with the
  (i) button on the map.
- On a car's screen the app shows itself through Android Auto, which runs on the phone: Android Auto
  gets the map picture the app draws for the car and what the app's car screen shows, and nothing
  else; see [Android Auto](#android-auto).
- Everything else stays on the phone, with two exceptions: Android's backup copies the settings and
  the trip session log to your Google account, and a debug log leaves the phone when you share it.
  Uninstalling the app deletes everything it keeps on the phone, but not that backup, which comes
  back if you install the app again; see [Deleting your data](#deleting-your-data).

## What stays on the phone

- **Your Mapbox access tokens**, up to five, which you create in a Mapbox account and enter
  yourself, each under a name you give it. Only one is used at a time, the one you choose in
  Settings. Each token names the Mapbox account that is billed for what the app does with it. They
  are stored only in the app's private storage, never in backups or device transfers, and never in
  the debug log, which names the token in use only by its place in the list and its name.
- **Settings:** day and night, camera style, lane zoom, lane details, and the switches for the
  simulated drive, for the app's own route line and for drawing the app's own lanes under Debug.
- **The trip session log:** when each guidance session started, its kind, whether it was
  simulated, and an id the app gave the token it ran on, never the token itself; the last 500 at
  most. It holds no places.
- **The debug log**, which records what the app did, for finding faults after a drive: each route
  request with its start and destination, the routes and their roads with the lanes the app would
  take on them and how it drew the route line and its own lanes (counts, sizes and times only: the
  drawing itself stays in memory with its route and is never stored), the position, speed and road
  every second of guidance (on freeways also the phone's turn rate and acceleration from its motion
  sensors, summarised as a few numbers each second; the sensors are read only then, and no reading
  itself is kept), a summary of each drive (how it ended, its length and time, and the data and
  battery it used), what was searched and found, settings changes, the phone's state (model, Android
  version, battery, heat, memory, network), what the car screen did (when Android Auto connected and
  disconnected, which Android Auto app it was by its package name and the version of the car
  interface it speaks, the car screen's size and look, and what it showed, in states and numbers)
  and crashes. It never holds a token. Days that are over are kept compressed on the phone, and when
  the log grows past 50 MB the oldest days are deleted. It leaves the phone only when you tap **Share debug log** in Settings and pick an app,
  which gets it as plain text; from then on that app's own terms apply.
- **Map and road data** that the Mapbox software saves so the map and guidance keep working without
  a connection, including the area along the routes you last previewed.
- The app keeps no search history.

Android's own backup copies only the settings and the trip session log to your Google account, and
only these two move to a new phone in a device transfer. On Android 9 and newer the backup is
end-to-end encrypted with the phone's PIN, pattern or password, if the phone has one.

## What Mapbox receives, and why

The app uses Mapbox's Maps, Navigation and Search software for Android. It sends data to Mapbox, Inc.
over encrypted connections (HTTPS):

- **To show the map:** requests for the map of the area on screen, and of the areas saved for use
  without a connection: along the routes you preview, and around the car, along the route and around
  the destination during guidance.
- **To plan routes and guide:** your position and direction of travel as the start of a route, and
  the destination, with the point where a car stops for it when Mapbox knows one; during guidance,
  traffic updates for the route and new routes from your position when you leave it.
- **To search:** the text you type, and the phone's position, so that results near you come first.
- **Mapbox telemetry**, unless you turn it off (see below): location data (latitude, longitude,
  altitude and accuracy) with a session identifier that changes every 24 hours, map loads and
  performance with device details (model, Android version, mobile carrier and network type, battery,
  screen and audio state), counts of the features used, and navigation events such as the start of a
  route, arrival, cancelling and new routes.
- **With every request:** the access token in use, which also names the Mapbox account it belongs
  to; the phone's IP address; the app's identifier and the versions of the Mapbox software; and
  random identifiers that Mapbox uses for billing. Mapbox counts the usage against the Mapbox
  account the token belongs to. The other saved tokens are not sent.

Mapbox uses this data to provide, secure and improve its services, to prevent fraud and to bill, and
to compute de-identified, aggregated data such as traffic. Mapbox says that it does not sell personal
data, that it keeps IP addresses for 30 days, and that it stores data in the United States.

Data is collected and used by Mapbox in accordance with the
[Mapbox Product Terms](https://www.mapbox.com/legal/product-terms), the
[Mapbox Data Processing Addendum](https://www.mapbox.com/legal/dpa) and the
[Mapbox Privacy Policy](https://www.mapbox.com/legal/privacy).

The map on a car's screen is a second Mapbox map: it asks Mapbox for the map of the area it shows as
the phone's map does, and Mapbox's telemetry counts that the car's map is in use.

### Turning off Mapbox telemetry

Tap the (i) button next to the Mapbox logo at the bottom left of the map, then **Mapbox Telemetry**,
then **Stop participating**. Mapbox then receives location data only for what you ask the app to do:
the map, routes, guidance and search. Those requests, and the count of monthly users that Mapbox bills
by, continue. The app never turns telemetry off on its own; Mapbox's terms leave that choice to you.

The same dialog offers **Improve This Map**, which opens Mapbox's map feedback page in your browser;
its address holds the area on screen and the access token in use.

## Android Auto

When the phone is connected to a car, or to Google's desktop head unit for testing, Android Auto on
the phone shows the app on the car's display. The app gives Android Auto, and nothing else:

- **The map picture** the app draws for the car's screen: the map around your position, the routes,
  the destination's pin and your position on it;
- **What the car screen shows over the map:** its buttons and messages and, during a drive, the
  next turn with its road name, arrow and lanes, the turn after it, the distances, the arrival time
  and the time left, and the destination's name, which Android Auto can also show on the car's
  instrument cluster and head-up display;
- whether the app is navigating, so that Android Auto knows which navigation app guides.

Android Auto sends back your taps on the car's screen and whether the car wants the light or the
dark map. The app answers only Android Auto, and the templates host of cars that run Android
themselves, each checked by the signature of its app. What Android Auto and the car do with what
they show is under Google's terms and the car's own. Nothing else leaves the phone for the car: no
account, no copy of the logs, and nothing to the developer.

## Permissions

- **Location, precise and approximate:** your position on the map, the start of routes, and
  guidance. During guidance a navigation service keeps it going, also with the screen off, and shows
  the trip notification; it ends with the drive. The app does not ask for location in the
  background otherwise. On the phone, to tell day from night: the app computes the sun's height for
  the phone's last known position. That position is not stored or sent, and the debug log records
  only which look was shown and why. On Android 13 and later, Android's list of recent apps shows a
  plain dark tile for the app, not a picture of the map.
- **Notifications:** the trip notification during guidance.
- **Network access and network state:** to reach Mapbox, and to tell you when the phone is offline.
- **Android Auto's navigation templates and car display surface:** to show the app's screen and
  draw its map on a car's display through Android Auto. Android grants them when the app is
  installed; they give the app nothing from the phone.

You can withdraw location access in Android's settings at any time; the map then shows no position,
and routes and guidance do not work.

## No other third party

No other company receives data from the app: there are no ads, no analytics and no crash reporting
service. The Mapbox software may use the network component of Google Play services on the phone to
make its connections, which go to Mapbox. Google Play, which installs and updates the app, and
Android's backup are Google services under Google's own privacy policy. In Play Console, Google shows
the developer crash and "app not responding" reports from phones whose users chose to share usage and
diagnostics data with Google, and, for test versions, "certain data on your use of the app (such as
device information, app usage information, and user-triggered events)". Turning that sharing off in
the phone's Google settings stops the crash and "app not responding" reports from your phone. Testers
are added by the email address of their Google account, which stays on the developer's testers list.
Two buttons in Settings open a web page in your browser, Mapbox's usage statistics and this policy;
those sites see the visit as any other.

The app is meant for adult drivers and is not directed to children.

## Deleting your data

- **On the phone:** **Clear** under Settings, Debug log deletes the debug log and the copies made
  for sharing. **Delete** in a token's menu under Settings, Mapbox access token, deletes that
  token. Uninstalling the app deletes everything it keeps on the phone, including the map data
  Mapbox saved for it.
- **In your Google account:** uninstalling does not delete Android's backup of the settings and the
  trip session log, and it is restored when the app is installed again. It can be deleted with the
  phone's backup in the backup settings of that account.
- **At Mapbox:** to have it deleted, use Mapbox's
  [data request form](https://www.mapbox.com/legal/ccpa-deletion-request); for your other rights,
  which the [Mapbox Privacy Policy](https://www.mapbox.com/legal/privacy) describes, write to
  Mapbox's privacy office at privacy@mapbox.com.
- **With the developer:** the app sends the developer nothing to delete, and Google keeps the Play
  Console reports under its own privacy policy. To be taken off the testers list, or to have a debug
  log you sent to the developer deleted, ask the developer (see [Contact](#contact)). Any other debug
  log you shared is with the app or the person you shared it with.

## Changes

Before a version that changes what the app keeps or sends is published, for example background
location or the trip history planned in the [roadmap](ROADMAP.md), this policy is updated. Its full
history is in the repository. 2026-11-13: the car screen through Android Auto.

## Contact

Questions about this policy: open an issue in the
[GitHub repository](https://github.com/Purple-Palm/Custom-Maps/issues).
