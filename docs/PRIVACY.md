# Privacy policy

Custom Maps is a navigation app for Android, a personal project published in the GitHub repository
[Purple-Palm/Custom-Maps](https://github.com/Purple-Palm/Custom-Maps). It is distributed through
Google Play's internal testing and as test builds made by the repository's CI; all of them work as
described here. This policy says what the app keeps on the phone, what leaves the phone and why, and
how to delete it.

Last updated: 2026-11-11. It describes the app as it is today; see [Changes](#changes).

## In short

- The app has no account, no ads, no analytics or crash reporting service and no server of its own,
  and it sends the developer nothing. Google Play shows the developer some reports that Google itself
  collects; see [No other third party](#no-other-third-party).
- Maps, routes, traffic and search come from Mapbox. To provide them, the Mapbox software inside the
  app sends Mapbox the phone's location, what you search for, where you plan routes to, your Mapbox
  access token, and usage and device details. You can turn off Mapbox's telemetry with the (i) button
  on the map.
- Everything else stays on the phone, with two exceptions: Android's backup copies the settings and
  the trip session log to your Google account, and a debug log leaves the phone when you share it.
  Uninstalling the app deletes everything it keeps on the phone, but not that backup, which comes
  back if you install the app again; see [Deleting your data](#deleting-your-data).

## What stays on the phone

- **Your Mapbox access token**, which you create in your own Mapbox account and enter yourself. It is
  stored only in the app's private storage, never in backups or device transfers, and never in the
  debug log.
- **Settings:** camera style, lane zoom, lane details and the simulated drive switch.
- **The trip session log:** when each guidance session started, its kind and whether it was
  simulated; the last 500 at most. It holds no places.
- **The debug log**, which records what the app did, for finding faults after a drive: each route
  request with its start and destination, the routes and their roads, the position, speed and road
  every second of guidance, a summary of each drive (how it ended, its length and time, and the data
  and battery it used), what was searched and found, settings changes, the phone's state (model,
  Android version, battery, heat, memory, network) and crashes. It never holds the token. Days that
  are over are kept compressed on the phone, and when the log grows past 50 MB the oldest days are
  deleted. It leaves the phone only when you tap **Share debug log** in Settings and pick an app,
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
- **With every request:** your access token, which also names the Mapbox account it belongs to; the
  phone's IP address; the app's identifier and the versions of the Mapbox software; and random
  identifiers that Mapbox uses for billing. Mapbox counts the usage against the Mapbox account the
  token belongs to.

Mapbox uses this data to provide, secure and improve its services, to prevent fraud and to bill, and
to compute de-identified, aggregated data such as traffic. Mapbox says that it does not sell personal
data, that it keeps IP addresses for 30 days, and that it stores data in the United States.

Data is collected and used by Mapbox in accordance with the
[Mapbox Product Terms](https://www.mapbox.com/legal/product-terms), the
[Mapbox Data Processing Addendum](https://www.mapbox.com/legal/dpa) and the
[Mapbox Privacy Policy](https://www.mapbox.com/legal/privacy).

### Turning off Mapbox telemetry

Tap the (i) button next to the Mapbox logo at the bottom left of the map, then **Mapbox Telemetry**,
then **Stop participating**. Mapbox then receives location data only for what you ask the app to do:
the map, routes, guidance and search. Those requests, and the count of monthly users that Mapbox bills
by, continue. The app never turns telemetry off on its own; Mapbox's terms leave that choice to you.

The same dialog offers **Improve This Map**, which opens Mapbox's map feedback page in your browser;
its address holds the area on screen and your access token.

## Permissions

- **Location, precise and approximate:** your position on the map, the start of routes, and
  guidance. During guidance a navigation service keeps it going, also with the screen off, and shows
  the trip notification; it ends with the drive. The app does not ask for location in the
  background otherwise.
- **Notifications:** the trip notification during guidance.
- **Network access and network state:** to reach Mapbox, and to tell you when the phone is offline.

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
  for sharing. **Remove token** deletes the token. Uninstalling the app deletes everything it keeps
  on the phone, including the map data Mapbox saved for it.
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

Before a version that changes what the app keeps or sends is published, for example the car screen,
background location or the trip history planned in the [roadmap](ROADMAP.md), this policy is updated.
Its full history is in the repository.

## Contact

Questions about this policy: open an issue in the
[GitHub repository](https://github.com/Purple-Palm/Custom-Maps/issues).
