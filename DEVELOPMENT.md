# Glance development and widget details

## Project layout

- `apps/web`: React configuration screen, departure boards, journey timeline, and map.
- `apps/android`: Capacitor 8 Android shell, Kotlin Entur integration, widget provider, WorkManager refresh, and shared preferences.
- `scripts/build-android-release.sh`: signed APK build using a local keystore. `scripts/android-ota.sh` publishes the web app alone; both use `scripts/release-common.sh`.
- `releases/`: local APK output, ignored by git.

The app was adapted from `/home/gjessing/projects/shell`. Atlas supplied the Entur endpoint and request conventions, and its commute panel inspired the commute widget, but Glance has no runtime dependency on Atlas: both widgets query Entur directly. Entur requires `ET-Client-Name: gjessing-glance` and needs no API key. See [Entur's Journey Planner documentation](https://developer.entur.org/pages-journeyplanner-journeyplanner/).

## Development

Node 22.12+ or 24+, Android SDK 36, and JDK 21 are needed for Android builds. On this host, `source ~/android-sdk/env.sh` sets the SDK and JDK paths.

```sh
npm ci
npm ci --prefix apps/android
npm run dev
npm run typecheck
npm test
npm run build
npm run android:debug
```

`npm run dev` previews the management interface in a browser. Live boards require the Android app's native plugin; the browser shows the four default groups and allows editing a local preview. `npm run android:debug` bundles the web app, syncs Capacitor, and builds `apps/android/android/app/build/outputs/apk/debug/app-debug.apk`. Install it with `adb install -r` or open the APK on a device. Add **Glance** from your launcher's widget picker. Resize it to show one, two, or four journey groups. The refresh arrow on the widget requests an immediate update.

## Signed release

The release build reads `~/.config/glance/keystore.env`, which exports `GLANCE_KEYSTORE_FILE`, `GLANCE_KEYSTORE_PASSWORD`, `GLANCE_KEY_ALIAS`, and `GLANCE_KEY_PASSWORD`. Keep the keystore and credentials safe: an APK signed with another key cannot update an installed release.

```sh
npm run android:release
```

The command creates `releases/glance-<versionName>.apk` and publishes it to the homepage downloads, where the app's update check finds it. Increase `versionCode` and `versionName` in `apps/android/android/app/build.gradle` before a future release.

### Web-only (OTA) updates

```sh
npm run android:ota
```

When only `apps/web` changed, this publishes the web app as `glance-web.zip` without a new APK. The app shows **A Glance update is ready**; **Update** downloads it, checks its SHA-256 and reloads into it in about a second. A bundle targets exactly one `versionCode`, and Capacitor goes back to the APK's own bundle when another APK is installed. Each bundle must call `webReady` once it renders. One that does not within 15 seconds, or by the next launch, is rolled back and not offered again. The footer shows the bundle id (`web <id>`).

The widgets, `JourneyActivity`, layouts and plugin methods are native and still need `npm run android:release`. The script refuses to run if anything under `app/src/main` (except the synced assets) or `build.gradle` is newer than the published APK. It cannot tell when web code calls a plugin method that the installed APK lacks, so a change like that also needs an APK release.

## How departures are selected

Default stops were verified against the live Entur Journey Planner on 2026-10-24:

| Journey                                 | Origin ID            | Destination ID       |
| --------------------------------------- | -------------------- | -------------------- |
| Engelsrud → Asker stasjon (bus)         | `NSR:StopPlace:3369` | `NSR:StopPlace:3247` |
| Asker stasjon → Engelsrud (bus)         | `NSR:StopPlace:3247` | `NSR:StopPlace:3369` |
| Asker stasjon → Nationaltheatret (rail) | `NSR:StopPlace:418`  | `NSR:StopPlace:288`  |
| Nationaltheatret → Asker stasjon (rail) | `NSR:StopPlace:288`  | `NSR:StopPlace:418`  |

Entur has distinct bus and rail stop places named **Asker stasjon**. The native integration fetches candidate departures from each origin, then checks each service journey's actual stop sequence. It requires the destination after the boarding stop and boarding from the correct quay. Bus defaults include lines 71 and 280. A group can board some lines at another stop with `lineOrigins` (`line=NSR:StopPlace:id`, comma separated). The Engelsrud → Asker group times line 71 from Engelsrud terrasse (`NSR:StopPlace:70146`), one minute before Engelsrud, which line 280 does not serve. Both lines share one row of departures. Fast rail defaults require regional or R-line service with no more than five stop intervals on the selected corridor, which includes R12/R13/R14 where they serve both stops and excludes L1. The app sorts by expected time and displays cancellations, delays, and track information.

Each Android widget ID has an independent JSON configuration and cached board in app private storage. A 15-minute WorkManager request refreshes widgets subject to Android scheduling and connectivity rules. The foreground app can refresh more frequently. Failed requests preserve cached departures and mark them stale.

The widget fits as many groups as its height allows and as many departures as its width allows, up to each group's count. Tapping a departure opens `JourneyActivity`, a native sheet over the home screen. It renders the cached timeline at once without starting the WebView, then refreshes from Entur. **See in map** and **Open in app** hand the same journey to the Capacitor app. The map cuts the service journey's Entur geometry at the boarding and destination stops, draws that segment in full and fades the rest of the route.

## Commute widget

A commute is three stops: from, via and to. The default is Engelsrud terrasse (`NSR:StopPlace:70146`) → Asker (`NSR:StopPlace:59616`, the multimodal parent, so the bus terminal and the rail platforms both count) → Nationaltheatret (`NSR:StopPlace:288`). `CommuteRepository.kt` sends one Entur GraphQL request per refresh with aliased `trip` queries: door-to-door journeys in both directions through the via stop, plus the two hops leaving it (via → to, via → from). A journey is dropped when another leaves no earlier and arrives no later. This rule removes slow trains such as an L1 leaving with an RE11, the way “fast rail only” does on the departure widget. Hops keep direct rides only. An optional line filter (public codes) applies to every vehicle.

The widget has three chips (destination, via stop and origin, labelled with each stop's short name) and one tap switches between them. The two door-to-door modes show one row per journey, with each vehicle's badge and departure, the planned change minutes between them, and the arrival and total. A change that live times make tight turns amber; one they break turns red with an explanation under the row. Disruptions get one line: a notice that applies to every journey is shown once under the list, not on each row. Routine notices stay in the app. The via mode reuses the departure widget's cards for the two boards leaving the change. Row layout scales with width: full badges, then no total, then narrow badges, then only the first vehicle's time.

Without a tap, the widget follows the clock (outbound before noon, home after). A tapped chip holds for three hours. **Tapping a journey tracks it.** The journey stays at the top of its direction, marked with where you are in it, until five minutes after its expected arrival, even once it has left and no longer appears in Entur's “from now” answers. Each refresh re-asks Entur from the journey's departure time, matches it by service journey and timetabled departure, and updates its live times. Tapping a tracked row opens the app's commute screen; ✕ stops tracking.

In the app, the commute screen follows the Atlas panel: direction tabs, the tracked journey with its itinerary, “If you leave now” with a countdown to the expected departure, then the next journeys as rows with a proportional ribbon that expand to an itinerary with platforms, the change and the walks. Scheduled times lead and live estimates are labelled beside them. The via tab shows both boards leaving the change.

## Smart Glance, agenda and weather widgets

**Smart Glance** stacks three sections under a header with the live clock and the temperature where
you are: the commute, the agenda and the weather. A preset picks which sections show (all three,
commute + agenda, agenda + weather, or one); they keep their order and step down in size, least
urgent first, when the widget is short of room. **Glance agenda** and **Glance weather** are single
sections.

On workdays the widget turns to the commute at 03:00 (Settings → Commute focus from) and stays there
until the return window ends: the commute shows both vehicles side by side with a transfer verdict
and the later departures, towards work before 12:00 and towards home from it (commute windows
07:00–12:00 and 12:00–18:30), and the weather is a rain
check of both ends of the commute for the windows still ahead, with the hourly forecast beneath it
when there is room, including for a dry commute. Evenings, nights and weekends the
commute steps aside unless a journey is tracked, and the weather is the plain forecast with an
hourly strip. Outside commute focus (workday evenings and nights, and days off) the commute's
place shows the local buses to the change and back (Engelsrud ⇄ Asker by default), so Sunday's
and each weeknight's buses hand over to the next commute at 03:00 by default. The Weekend Glance
label stays through midnight until commute focus begins. Tapping a vehicle in the commute card, one of the "Later" line chips under it, or the
one-line commute opens that ride's journey sheet (its stops, live times and the same stretch's other
departures), with **Whole commute** to open the journey in the app; the card's title row opens the
app directly. Tapping the weather opens the longer forecast: 48 hours and nine
days at both ends of the commute.

The agenda comes from Nodecal's `GET /api/glance/agenda`, authenticated with a token created in
Nodecal under Settings → Integrations and entered in Glance's settings (⚙); the token stays in native
storage. Nodecal sends each profile's hidden calendars, so Glance filters locally: *Automatic* is
Combined on workdays from 03:00 until the return window ends (18:30), Personal otherwise. Events
after today have a faint background tint alongside their day label. Tapping an event opens it in the Nodecal app
(or the browser), the video icon joins the meeting and the pin opens maps. Weather comes from MET
Norway directly (`locationforecast` + `nowcast`, honouring `Expires`), at the commute config's two
end stops.

`RefreshCoordinator` is the one background job, every 30 minutes by default (Settings: 15, 30 or 60): transport and agenda every run, weather when MET's
data has expired. The ↻ button on each widget refreshes everything at once. `BoundaryAlarm` re-renders from cache at countdown steps, event starts and ends
and the time rules' edges, with a non-waking alarm, and refreshes transport every 15 minutes
inside a commute window while the phone is awake. Check layouts on the emulator with
`WidgetPreviewActivity --es provider smart --ez fixture true` (see its KDoc).

## Living Tree widget

**Living Tree** is a 1×5 picture with no text: a weeping silver birch (*Betula pendula*) over a
cross-section of its soil and roots. It follows the season, the light and the weather at home (the
commute's first stop), using the MET forecast and nowcast the weather widgets already cache, so it
makes no weather requests of its own apart from those for its own place (the night sky's come from NOAA, below). `TreeScene.kt` holds the model: the birch's year (buds and
catkins from mid-April, leaves in May, yellowing from mid-September, bare by late November), the
sun and moon positions, and a small weather memory. MET has no history, so the tree keeps its own
record per place, stepped through the hours since the last render (nowcast for the last hour, the
cached forecast's own hours before that, nothing beyond the cache). Recent precipitation and thaw
feed saved topsoil, root-zone and deeper wetness in sequence; the upper soil darkens first and dries
first, while deeper soil and water pockets recede over days. This is an illustrative approximation,
not a soil-moisture measurement. Puddles and wet bark dry within hours. Snow stays until
degree-hours above zero melt it, an autumn gale strips leaves early, and the sky eases between
refreshes instead of jumping. The calendar is only the frame for the birch's year: the record keeps
how much warmer or colder than Oslo-Blindern's 1991–2020 normal the last two weeks (autumn) and
month (spring) have been, and moves budburst about four days per °C (±14 days) and leaf colour and
fall about two and a half days per °C the other way (±10). The shift never runs the year backwards,
and a hard autumn frost strips leaves early. The same record keeps hoar frost on the grass (clear
freezing nights, gone in the thaw) and dry spells that bleach the grass in summer. Birds are rare
discoveries: about one calm, daylit two-hour visit in ten brings a blackbird (most often, and the only
one staying into the dusk), a great tit working the bare twigs in winter, a wood pigeon in summer or a
crow. Geese pass over in the migration weeks and crows fly to roost on winter-half evenings. None
appear by night or in rain, snow or fog.

Light has a direction: a low sun warms the sunlit side of the white trunk, rims the limbs and
turns the sun-side curtains copper, while the far side drops into cool shade. In twilight the warmth
gathers in a band low over the horizon on the sun's side while the upper sky deepens to blue; the
large view also shows the earth's shadow opposite and the band in the lake. The large view's ground
follows the year too: grass greens blade by blade through last year's straw, seed heads stand from
July, heather flowers in August, leaf carpets lie under the birch and the maple, pines put up pale
candles in late May, yellow their inner needles in autumn and drop a rusty needle mat, and thin snow
melts in patches.

`TreePainter.kt` paints the scene on a Canvas from a fixed seed and fixed segment counts, so it is
the same tree at every size and in every state. Home screens cannot run animation code, so the
picture is redrawn on each refresh and every 10–30 minutes as the light moves (more often around
sunrise and sunset). When something moves (rain, snow, wind, falling leaves) it sends two frames to a
`ViewFlipper` that cross-fades between them every nine seconds. The fade runs only while the home
screen is visible. A tap opens the longer forecast. Check the look with
`WidgetPreviewActivity --ez gallery true` (twenty-one fixed scenes at the phone's 59×494dp, saved to
`files/tree_gallery.png`; add `--ei width 88 --ei height 739` on `glance_s24u` for the phone's pixel count).
`--es provider tree` hosts the real widget, and adding `--ez motion true` shows its rain frames.

### Northern lights and meteor showers

Both picture widgets show the night sky's events when they could really be seen from home, so a
glance says whether it is worth stepping outside. `SpaceWeather.kt` fetches NOAA SWPC's public
products directly (no key, no other service in between):

- the planetary K-index forecast (a few kB, three-hour blocks, about three days ahead), every three hours;
- OVATION's aurora probability grid (155 kB gzipped, the next 30–45 minutes), only on promising
  nights (dark at home, Kp 3 or more, not fully overcast), at most every 20 minutes and with its ETag.
  Only home's chance is kept: overhead, or up to ten degrees further north, where it shows lower.

`NightSky.kt` weighs that by darkness and cloud with the same factors as Atlas
(`packages/conditions/src/sky.ts`, copied rather than shared, so Glance stays standalone): civil
twilight shows almost nothing, and MET's low cloud hides most aurora while high cloud hides little.
The moon and suburban sky glow take a share too. Without a current OVATION outlook the Kp alone
decides (a faint glow low in the north from about Kp 4 at Asker). The aurora is painted as a muted
green arc with rays rising from behind the crown or the mountains, with a faint red top only in a
strong display; its rays shift between the two cross-fading frames.

Meteor showers come from a fixed table: the Quadrantids (around 3 January), Orionids (21 October),
Leonids (17 November), Geminids (14 December) and Ursids (22 December), with their peak rates and
radiants. The rate seen at home follows the shower's activity curve, the radiant's height (from its
right ascension and declination), darkness, cloud and moonlight. A picture shows a moment, so a
meteor appears now and then on an ordinary shower night and two or three at a strong peak,
streaking away from the radiant and flashing in during the cross-fade. The widgets' descriptions
name the aurora and the shower when they show.

## Tiny Living World widget

**Tiny Living World** is a 1×1 picture with no text: a small round tree in a clearing by a mountain
lake, two pines, a rock, the forest on the far shore and snow-capped mountains behind, drawn as a
soft, flat-shaded little diorama. It shares the Living Tree's model (`TreeScene.kt`): the same
season curves, sun and moon, weather memory and night sky for the place at home, so it makes no requests of its
own. `WorldScene.kt` adds what the clearing keeps (`WorldMemory`). Mushrooms come up after a day or
so of damp, mild weather (most in autumn) and wither in the dry. Flowers open with the season on mild
days and a hard frost cuts them back. The lake freezes from the frost degree-hours the forecast
records and thaws the same way. Snow, puddles and wet ground come from the tree's record. After
snowfall, a fox, hare or bird leaves tracks that stay until new snow or a thaw.

Visitors are picked per twenty-minute slot from a fixed hash, so a refresh never chases them away:
about one slot in six has one, chosen from those that suit the light and weather. A fox (mostly at
dusk and night), a robin or bullfinch on the tree, a hare (white in winter) behind the rock, a
butterfly on warm days, a squirrel, an owl at night, or a shooting star on clear nights. Small birds
cross the morning sky and fireflies come out on warm summer nights.

`WorldPainter.kt` paints it in a 100×100 square. In one cell, which One UI makes taller than wide,
`WorldWidget` draws a true square centred in the cell (`widget_world.xml`, `fitCenter`), as One UI
centres the icons and folders beside it; resized larger, it fills the widget, the square bottom-centred. Like
the Living Tree it is redrawn on each refresh and every 10–20 minutes. When something moves (rain,
snow, wind, falling leaves, wings, fireflies, a shooting star) it uses the same two-frame cross-fade.
A tap opens `WorldActivity`, the same moment painted large. Check the look with
`WidgetPreviewActivity --ez worldGallery true --ei scale 2` (twenty fixed scenes, saved to
`files/world_gallery.png`). `--es provider world` hosts the real widget.

## Widget sizes on the owner's phone

Measured from home-screen screenshots of the Galaxy S24 Ultra (WQHD+ 1440×3120, 3.5 px/dp, 5-column
grid, labels hidden; 2026-10-25). Launchers report sizes loosely, so check layouts at these:

| Widget | Cells | Size (dp) | Notes |
| --- | --- | --- | --- |
| Grid cell | 1×1 | ~82 × 101 pitch | App icons and folders are ~59dp squares, centred vertically |
| Tiny Living World | 1×1 | ~58 × 83 | Drawn as a ~58dp square centred in it, like the folders |
| Living Tree | 1×5 | ~59 × 494 | About 8:1; the gallery's default size. The birch keeps a natural ~2.8:1 shape, sky above with cloud layers, skyglow and mist by night |
| Glance (departures) | 4×5 | ~286 × 487 | |
| Smart Glance | | ~286 × 386 | Estimated from an earlier screenshot |

The `glance_s24u` emulator runs at 1080×2340 and 2.34 px/dp, so a size in dp there has fewer
pixels than on the phone; multiply by 1.5 to preview at the phone's pixel count.

## Current limits

Configuration accepts NSR stop IDs directly (both widget types); stop search and adding custom groups in the interface can follow. WorkManager's 15-minute periodic work is inexact and may be deferred by battery settings. The map shows the route geometry and stops, not live vehicle positions. Multiple widgets can have separate settings, but a new widget starts from the saved default setup; assignment is handled by the launcher's widget picker.
