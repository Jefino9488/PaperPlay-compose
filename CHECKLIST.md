# PaperPlay Compose: what's done and what's left

Updated 13 Nov 2026, code audit (section 6 lists what the code does and does not contain; compiles, mostly not tried on a device; `[x]` in section 1 not individually re-checked)

**Marks:** `[x]` done and written, `[~]` half done, `[ ]` not done.
"Compiles, not tried" means the Android release and desktop release built on 12 Nov (night) but the feature has not been tried on a device.

> Heads up: "done" means it was written and (unless noted) compiles. The phone release build with the
> speed fixes feels much better, but most features below have not been checked one by one on the phone.


---

## 1. What works now

### App and look
- [x] Home, Search, Library, Profile, Settings, mini player, full player, queue
- [x] Desktop layout (sidebar, now playing panel, player bar)
- [x] Glass and Material styles, themes, accent from the cover colour
- [x] Material 3 Expressive and Tabler icon options
- [x] Tabler icons work: `AppIcon` strips the `Rounded.` prefix before the lookup (phone too)
- [x] Unified onboarding, Telegram bot login and backup restore wizard, with Material 3 Expressive cards
- [x] Onboarding "About you" step: name and photo (`OnboardingSteps.kt`, `ProfilePage`)
- [x] Nunito variable typography, text contrast and Material 3 Expressive shapes (12dp to 36dp) on all screens
- [x] Scan chip, sticky play/shuffle, sort sheet, swipe up for queue, seek preview, recent searches
- [x] Style, Appearance, Navigation bar and Glass pages: glass on cards and top bars (blur and tint);
      desktop glass is blur, tint and gradient only
- [x] Full Glass controls (blur, refraction, saturation, tint, depth, colour fringing, edge, shadow, inner glow,
      reset), `GlassStyle` in `UiPrefs.kt` (compiles, not tried)
- [x] Settings: Repair library moved into Library, Style and Appearance merged, categories ordered by use (compiles, not tried)
- [x] Backgrounds: Plain, Cover, Blurred cover, Gradient, Wallpaper, Animated (stars, snow, aurora, bubbles,
      waves) with colour controls, in Settings > Appearance > Background (compiles, not tried)
- [x] Cover gradient on by default (off only when the pref is `false`)
- [x] Status toast hides after 20 seconds; swipe threshold lowered
- [x] Dark Windows title bar that follows the app colours (needs a PC rebuild to see)
- [x] Windows icon with all sizes (needs a PC rebuild and an icon cache refresh)
- [x] Backup carries the `bg_*` and `standby_*` prefs as layout (`bg_image` stays per device); needs the new test run

### Playback
- [x] Android (Media3) and desktop (bundled libvlc, no VLC install needed)
- [x] Shuffle, repeat, saved volume, play next, sleep timer, 10 band equalizer with response graph (compiles, not tried)
- [x] Media notification, keep-alive service with scan progress
- [x] Lyrics with synced lines, tap a line to jump there
- [x] Playback speed 0.5x to 2x from the full player (compiles, not tried)
- [x] Edit the queue: row menu with move, play next, remove; no drag yet (compiles, not tried)
- [x] Restore last session (paused; setting in Listening) (compiles, not tried)
- [x] Standby mode, Android only: black AMOLED screen from the full player, clock, song, lyrics, weather and
      battery widgets, Bars/Wave/Rings/Orb animation, customise sheet (compiles, not tried)
- [ ] Standby: auto-start while charging; animation does not follow song position

### Desktop
- [x] Spotify-style player: now playing view, full-screen synced lyrics, mini always-on-top window,
      device picker (this PC only), keyboard shortcuts overlay (compiles, not tried)
- [x] Sidebar: corner radius slider (`nav_radius`) and width (`sidebar_width`) are in `UiPrefs.kt` and Settings > Navigation bar (found in the 13 Nov audit; glass option and selection style not re-checked) (compiles, not tried)
- [x] Sidebar Tools group (Sort General, Duplicates, Song info left)
- [x] Mute button restores the previous volume (`DesktopShell.kt` remembers `lastVolume`; found in the 13 Nov audit)
- [x] Settings back arrow goes back one page, leaves Settings only from a top-level page
- [x] Media keys (`WindowsMedia.kt`)
- [x] Tray menu has a Mini player item (`TrayMenu.kt`); the bar lyrics button no longer opens the old dialog (see the 13 Nov update below)
- [ ] Queue drag-reorder (rows only have the move / play next / remove menu; no drag code)

### Telegram and library
- [x] Bot login, credentials, channels, main chat, TDLib on Android and desktop
- [x] Scan, full rescan, forum topics, backup restore
- [x] Own account: phone, code, password, QR login
- [x] Chats and channels page: index a chat, add songs the bot can't see (they play through the account);
      reachable from Channels, Profile and Settings; More toggle, select all / add all under the chips
- [x] Index refresh and Spotify refresh in the background
- [x] Duplicates: find them, tick extras, keep best quality, delete through the bot, Bot API or own account
- [x] Sort General: topic suggestions and moving songs between topics
- [x] Uploads hub: queue, retry, rate limit, share to PaperPlay on Android
- [x] Extra bots: add and remove tokens, bot pool shares uploads, rate limits per bot
      (compiles, not tried; bots from a restored backup are not started)
- [x] Save backup in the Flutter format with an optional password (Settings > Storage and backup),
      restore from file, round trip test passes
- [x] Cloud save in Saved Messages also holds favorites, plays, skips, pins, edited tags and playlists;
      restored once after login, saved on logout
- [x] Plus button on a not-added track copies the indexed match to the main chat (when auto copy is off)
- [x] Offline cache: eviction, pinning and the Cache manager page (Settings > Storage)
- [x] Quality badges: format badge plus Hi-Res badge on song rows
- [x] Delete from Telegram from the song menu (compiles, not tried)
- [x] Export list screen: Settings > Library > Saved export lists (compiles, not tried)
- [x] Library status tiles at the top of Settings > Library (compiles, not tried)
- [x] Topic picker (already in `SortPage`)
- [x] Songs missing: cause found, a bot cannot see posts another bot made as itself (anonymous admin or
      forwarding them again fixes new and old posts)
- [x] Rescans never remove songs by themselves: songs whose post looks gone are kept and listed in
      Repair library with a Remove button
- [x] Uploader saving right after an extra bot sends it: not needed for now (same Telegram limit as above)

### Playlists and Spotify
- [x] Import a public Spotify playlist link, match review
- [x] Spotify login with my own client id (PKCE), reads whole playlists
      - redirect to register: `paperplay://spotify` (phone), `http://127.0.0.1:8888/callback` (desktop)
- [x] Spotify added dates kept; playlist "Date added" sort uses them; manual refresh uses the Spotify login
- [x] Playlist page: sort menu, refresh from link, rename, delete
- [x] "N not in your library yet" sheet with index matches and Add buttons
- [x] Library Playlists tab (Favorites, Recently played, New playlist, Import)
- [x] Song menu: Open in Telegram, Song info, Find metadata, Edit tags, Change cover
- [x] Playlist add-songs picker (`AddSongsDialog.kt`, compiles, not tried)

### Metadata, covers, lyrics
- [x] Metadata lookups: MusicBrainz, Deezer, iTunes, TheAudioDB, all at once
- [x] Find metadata picker, edit tags, change cover (file, link or online)
- [x] Song info left page (what's missing, look up again, copy list)
- [x] Read tags and covers from inside the audio files (ID3, FLAC, Ogg, MP4)
- [x] Lyrics from LRCLIB, NetEase, Kugou, QQ Music, lyrics.ovh, run over the whole library, misses skipped for 14 days
- [x] Cover colours, daily mixes, genre tiles, weather mixes
- [x] Mood scan starts after the first catch-up and metadata run (untested on a device)
- [x] Mixes: Home opens a mix with remove and undo
- [x] "Doesn't fit" votes feed the daily mixes (rebuilt as the scan advances); no vote screens, no Mix settings page
- [x] Listening stats, ListenBrainz, mood code in core

### Speed and battery (12 Nov, big win on the phone)
- [x] Library reloads batched to about one every 2 seconds, one shared song list
- [x] Fewer background workers (metadata 2, covers 1, lyrics 2), batched writes
- [x] Full rescan of every chat resumes instead of restarting, runs in the foreground or while charging
- [x] Keep-running service off by default and stops when nothing is running
- [x] Position updates no longer redraw the whole screen, scan progress split out
- [x] Chats and channels page uses lazy lists, covers use a real cache and smaller thumbnails, theme and shapes remembered
- [x] Songs start faster: lookup runs next to queue setup, next and previous prefetched, stream reads use the known downloaded size
- [x] Smaller release APK: 35 MB down to 15.7 MB
- [x] Detail screen no longer redraws on every library change, seek bar reads progress at draw time,
      stable queue keys, handwritten baseline profile
- [x] Seek bar wave stops drawing when the Android app is in the background (needs a phone check)
- [x] Home mixes cached so Home opens faster

### Song list, rows, search and playlist toolbar redesign (12 Nov, edited, compiles not tried)
- [x] Slim song rows (one format chip plus status icon, size only when wide, `maxLines = 1`), slim search field with clear (`PillField`), grouped search results (Songs, Albums, Artists headers), playlist top bar overflow menu: all found in the code on 13 Nov (compiles, not tried). The flat opaque sticky Play/Shuffle/Sort bar was not re-checked

### Profile and navigation (12 Nov, compiles; debug build not yet on the phone)
- [x] Profile sections collapse with a chevron and always start collapsed (state not saved)
- [x] Profile rows fixed: Duplicates goes to the right page, new rows for Upload songs, Sort General, Connection and Devices
- [x] Back from a page opened from Profile returns to Profile, not the Settings list
- [x] Phone and desktop Back return to the previous page or tab through a history stack (needs a device check)
- [x] Nav bar polish: outlined Search icon, 24dp icons, shadow and outline, 16dp side margin (compiles, not tried)
- [x] Permission checker: Settings > Devices > Permissions lists notifications, Bluetooth (permission and on/off), battery and Wi-Fi with Allow / Open settings buttons, refreshed every second (`PermissionsPage.kt`, `Permissions.kt`, hooks in `MainActivity.kt`; compiles, not tried)
- [x] Extra bottom scroll room on phone pages so nothing sits behind the nav bar (compiles, not tried)
- [x] Previews for equalizer, ListenBrainz, account, song info rows, stats and log

### Other
- [x] Task centre page (running tasks, history, clear)
- [x] Task centre: stop buttons for scan, index, sync and upload; history kept across restarts (untested on a device)
- [x] Statistics, play next, clear cache, log out, profile photo
- [x] Android debug and release signed with the odo key, blue icon, desktop packaging (libvlc, TDLib, java.sql)


---

## 2. Half done, finish these

### Build and device checks
- [~] **Small TDLib:** built (14.4 MB raw, about 7.8 MB in the APK, same exports as the current one).
      Swapped into `androidApp/src/main/jniLibs/arm64-v8a`, old one backed up in `D:\tdlib-build\backup`.
      Not built or tested. Desktop unchanged (no Windows build). Would give an APK of about 10.8 MB.
- [~] **Desktop opens slowly (over 2 minutes):** fixes compile (VLC cache saved on exit and made at build time,
      services built off the main thread with a splash, player starts on first play, `startup.log` with timings),
      not tested on the PC.
- [~] **PC app is still the old build:** new icon, dark title bar and one shuffle button only show after the
      PC image is built and installed.
- [~] **Baseline profile** is handwritten, not recorded on a phone.

### Features
- [~] **Uploads:** queue kept across restarts; desktop Choose folder and window drag and drop; Profile has an
      Upload songs row (edited, compiles, not tried). Android folder pick not done.
- [~] **Missing info page:** multi select, "Retry no match", Copy as CSV and Save to file (edited, compiles, not tried)
- [x] **Detail screens:** add-songs picker done; playlist reorder written, compiles, not tried (schema v15
      `playlist_songs.position`, migration ranks by `added_at`, up/down dialog in `PlaylistReorder.kt`;
      cloud save keeps order, old snapshots restore in list order; `LibraryMerge` has no order yet)
- [~] **Playlist page:** Pin for offline now pins every song (untested on a device); Favorites and Recently
      played open as fixed lists
- [~] **Pin playlists and songs for offline:** Pin button and song menu work (account-read songs skipped)
- [~] **Sort General:** asks Telegram if the bot is an admin that can post and delete (skips with a note if not),
      leaves the playing song in General; deletes still go only through the bot (edited, compiles, not tried)
- [x] **Spotify login:** the in-progress login (state, PKCE verifier, redirect) is saved so a return link after
      Android ended the app finishes it (edited, compiles, not tried); the Spotify page does not refresh by itself after a late finish
- [x] **Loudness normalising:** Listening setting. Android limiter (Android 9+), desktop VLC `normvol` (compiles, not tried)
- [~] **LAN sync:** core, Connection page and Network play are written (see Devices). Queue messages are sent (`sendQueue`) and received (`LanMessage` type `queue`), but `AppServices.bindLan` only acts on `control` (play, pause, next, previous) and sends now-playing, so queue and now-playing sharing between own devices is still not used

### Needs keys or has known gaps
- [~] **Metadata:** Last.fm, Discogs, Spotify, Genius not used (they need keys)
- [~] **Lyrics:** Genius and Musixmatch not used (they need keys)
- [~] **Covers:** online cover thumbnails in Change cover may not show
- [~] **Embedded tags:** very big Ogg covers get cut (ID3 genre numbers now become names)
- [~] **Duplicates:** Dart duplicates tests not ported


---

## 3. Not started

### Fix first
- [ ] Android crash after the merge (error text is in Settings > Telegram > Activity log). The 16:29 crash was the volume poller and is fixed. The older TDLib "Receive must not be called simultaneously" aborts most likely came from a restore starting a second receive loop while the old one still ran; that is fixed in `AppServices.shutdown` (13 Nov audit, R1, not tried on a device; other causes are not ruled out)
- [ ] Check everything on the phone: login, QR, indexing, copying, refresh, cloud restore, uploads,
      lyrics run, duplicates delete

### Playback
- [~] Audio output choice: Android system output switcher from the player (compiles, not tried); desktop follows the Windows default output
- [ ] Remote and group playback

### Library
- [ ] Auto filing (new songs go to the right topic by themselves); moves real Telegram posts
- [ ] Neighbours view in Discover (core `recs/Suggestions.kt` exists; no UI found)
- [~] Recover songs the bot cannot read (Settings > Library > Recover songs): account copy, or account download and main bot upload, then delete the old post. UI wired (`RecoverPage.kt`, Settings row). Not built or tried. Describing the account as optional in onboarding still to do
- [x] Channels and chats on one page, per channel Copy to main chat switch (default off; `ChannelsPage.kt` exists). Not built or tried
- [~] Music folders with optional sending to Telegram (desktop picker, Android typed path only, no watcher). Not built or tried
- [ ] Backup to a Telegram topic: song database, covers and lyrics only (no tokens or session), selectable
      period in Settings (daily, 3 times a week, weekly, manual), restore through the owner's account

### Devices
- [x] LAN sync core (edited, compiles, not tried, no UI): `core/.../sync/LanSync.kt` (API), `JavaLanSync.kt`, `LanCrypto.kt` (X25519, HKDF, AES-256-GCM frames),
      `AddressBook.kt`, `PairingGuard.kt`, `LanSnapshot.kt`, `Lan.sq`; off by default (`AppServices.enableLanSync`). Pairing code, password,
      same-bot-token mode, only-paired switch, lockout, discovery, network scan, saved addresses. Tests: `LanCryptoTest`, `LanSyncTest`, `PairingAndBookTest` (not run)
- [x] Connection page (edited, compiles, not tried): `ui/app/LanConnectionPage.kt` inside Settings > Connection (route `conn`), `CoreBackend.lanSync` and `setLanOn`, starts at launch when pref `lan_on` is 1, manifest INTERNET and ACCESS_WIFI_STATE, `lan_*` prefs in `devicePrefs`
- [x] Network play (edited, compiles, not tried): other people on the same network join a listening session by QR link or 6 digit PIN and songs play in sync. Core `core/.../sync/netplay/` (`NetplayModel`, `NetplayGuard` PIN and `GuestPolicy`, `NetplaySync` clock offset, drift, song match, `NetplayLink`) and `javaMain/.../netplay/JavaNetplay.kt` (own port 47873 and discovery 47874, same X25519 and AES-GCM transport, guests get play control and audio ranges only); player glue `app/PlaybackNetplayPlayer.kt`, `TrackResolver.setExternalSource`; UI `ui/app/NetplayPage.kt` (Settings > Devices, Profile > Devices, pill in `Shell.kt`). Tests: `NetplayCoreTest`, `NetplayLoopbackTest` (not run). Gap: no camera QR scan (paste the link) and no `paperplay://` manifest intent filter yet
- [x] Apply a sync merge to the database (snapshot build and apply written in `LanSnapshot.kt`, edited, compiles, not tried)
- [ ] Remote playback and PC offload
- [ ] Log out of all devices
- [ ] Session export and import

### Desktop
- [x] Tray icon, close to tray (`desktopApp/.../Main.kt`, not tried)
- [x] Keyboard shortcuts (`PlayerShortcuts.kt`, overlay in `Shell.kt`, not tried)
- [ ] Battery and notification permissions
- [~] macOS and Linux libvlc and packaging (Linux .deb target in `desktopApp/build.gradle.kts`; no macOS)
- [ ] Check release packaging on Windows, macOS and Linux

### Carried over from TODO.txt (merged 13 Nov, TODO.txt removed)
- [ ] Windows now playing overlay and lock screen controls (needs WinRT media session; `WindowsMedia.kt` has media keys only)
- [ ] Phone: Settings entry in the side menu (asked; the phone has no side menu, decide where it goes)
- [ ] Check the first-run tour on a fresh install: restore choice, Skip, bot connect failing, swipe past the locked bot page
- [~] Repair library: embedded tags are applied when a song's download finishes (`AppServices.kt`, `EmbeddedTagFiller`), but Repair does not walk files already cached. Still to do: run lyrics after lookups, retry bot-posted songs it cannot see
- [ ] Cover search for compilations (e.g. "00s Mixtape") still weak; Deezer and iTunes do not list them
- [ ] Activity log: keep lines across restarts, add warning level, log more sources (playback, sound, sync, covers) like the Flutter app

### Ideas I haven't built yet
- [ ] Album pages that fill themselves from all the sources
- [ ] Lyrics run and metadata run progress on the Home scan chip
- [ ] Pick lyrics source order in Settings
- [ ] Lyrics from the audio file itself (embedded lyrics tag)
- [ ] Per-song "wrong lyrics" button that tries the next source
- [ ] GitHub builds TDLib and the app downloads it, in-app update from Releases
      (skipped for now because the bot needs TDLib too)
- [ ] Parts in Rust (tags, cover resize, mix scoring, search), only if a profile shows they are slow


---

## 4. Tests

- [x] Kotlin tests: backup (writer round trip too), database, TDLib client, audio info, index, selection,
      auto sync, QR, user session, playback controller, streaming, scanner, covers
- [x] Kotlin tests that exist but were not listed here (25 files: 23 in `core`, 2 in `player`, none in `ui`, `androidApp` or `desktopApp`): LAN crypto, LAN sync, pairing and address book, library merge, netplay core and loopback, album split, genre, JVM player engine, stream HTTP server, TDLib JSON smoke. Tests do not run in CI at the moment (see 6e)
      auto sync, QR, user session, playback controller, streaming, scanner, covers
- [ ] Dart tests with no Kotlin version yet: album_art, cover, cover_colors, duplicates_view, equalizer,
      glass_style, home_mix, later_stamp, listen_recorder, listen_stats, listenbrainz, logic, loudness,
      main_chat, migration, missing_export, mood, neighbours, playlist_import, setup, spotify, sync, taste,
      td_compat, topic_sort, upload
- [ ] No tests written for most of what was added in the last two days


---

## 4b. Added 2026-11-12 late (edit-only, not built)

- [~] Onboarding: animated logo, auto-advance after the bot connects, optional Spotify page, permission shows allowed
- [~] Desktop: native Explorer-style dialogs for open, save and folder (compiles, not tried), frameless mini player, adaptive player bar, song menu, cover-gradient now playing
- [~] Cover colours and sharper covers
- [x] Settings: merged Appearance page, All parts incl. sidebar, bold transport icons
- [x] Android: one AppServices per process (`MainActivity.shared`; found in the 13 Nov audit). Notification icon not checked. A restore from Settings now shuts the services down properly first (R1, fixed, not tried on a device)
- [ ] Background fill of info and lyrics
- [~] Playlists: From Telegram, starred channels; duplicates covers
- [x] Installer asks to delete data on uninstall
- [ ] Measure play start time on the PC; find the Android crash; check PC vs phone song count

---

## 5. Order I'd do it in

1. Everything now compiles (Android release 10.9 MB and desktop installer built); install both and try the features marked "compiles, not tried"
2. Build and install the PC app, check the slow start with `startup.log`
3. Install the debug APK with the small TDLib; test bot login, scan and playback on the phone
4. Go through the device checks, then the Android crash
5. Check the half-done items on a device (uploads, reorder, sort, Spotify resume)
6. Auto filing and the Telegram backup topic
7. LAN hub, pairing and devices
8. Desktop tray and shortcuts, then macOS and Linux
9. Port the Dart tests as each thing lands

## Update 2026-11-13 (desktop player, player bar, glass)
- [x] Full player: Queue/Lyrics tabs, window full screen with exit icon (native title bar)
- [x] Now playing page: Queue/Lyrics switch; bar lyrics and three-dot buttons removed
- [x] Player bar settings, custom volume bar, Space focus fix, phone Devices button
- [x] Cover search dedupe and ranking; sort label width; pinned list bar colour
- [x] Desktop glass blur off by default (switch on the Glass page); covers mipmapped
- [ ] Try on screen: player bar settings, glass without blur, covers, volume bar, full screen
- [ ] Custom title bar (reverted: no drag, laggy)
- [x] Esc exits full screen (borderless full screen, compiles, not tried)
- [ ] Lag check; "Wrong remote file identifier" on the phone; prefs missing from backups

## Update 2026-11-13 (fixes round; desktop and Android release Kotlin compile, nothing tried on screen)
- [x] Tray: left click opens the app (press/release plus action listener, stronger raise); right click opens a themed menu window (`TrayMenu.kt`)
- [x] One copy only: a second launch shows the running window (`app.lock`, `app.port` in `%APPDATA%\PaperPlay`)
- [x] Full screen on Windows is borderless over the monitor (`BorderlessFullscreen.kt`); Esc leaves it; the player layout uses the width better
- [x] Panel toggle hides while the panel or full player is open and comes back when closed
- [x] Mini player: graphicsLayer clip for the corner artifacts, time readout fills the right side
- [x] "Song info left" icon is the checklist icon
- [x] File dialogs: Explorer-style open and save on Windows (WinForms through PowerShell), COM folder picker kept, zenity or kdialog on Linux, AWT fallback
- [x] Desktop app icon art enlarged (`icon.png`, `app.ico`; Windows may need an icon cache refresh)
- [x] Memory: JVM limits (`-Xmx448m`, SerialGC, metaspace 160m), one shared cover cache (32 MB), covers decoded at the size shown with shrunk copies in `~/.paperplay/thumbs`
- [x] Equalizer: 10 bands, 13 presets, draggable response curve that glides between presets; old 5 band saves are stretched to 10
- [x] Desktop audio: WASAPI (`--aout=mmdevice`), soxr resampler, EQ preamp against clipping, EQ re-applied for each track, normalise ceiling 1.0, 500 ms buffering. The cause of the "weird noise" is not confirmed
- [x] Output info dialog (opens from the format badge and the player three-dot menu) on both platforms: source, rate, depth, output, effects, bit-perfect or not
- [x] Android: Devices button in the player tools row (Network play, Connect devices, Audio output) and the device pill opens the system output switcher
- [x] Channels page: songs, topics, size, duration, lossless, offline, pinned, favourites and copied counts per channel
- [x] `scripts/release.ps1`: Android release build and install, then the desktop installer to Downloads (`-SkipAndroid`, `-SkipDesktop`)
- [ ] Try all of the above on screen and on the phone; check tray click, full screen click-to-minimise, the noise, file dialogs
- [ ] RAM: earlier build used about 450 MB (heap 40 MB, metaspace 67 MB, Intel D3D12 driver files about 175 MB, TDLib and libvlc about 100 MB). Not a Rust problem. Test `-Dskiko.renderApi=OPENGL` and `SOFTWARE`, lazy libvlc, fewer classes
- [ ] Not done: live level meter in the equalizer (curve only), channel last-scanned time, panel toggle also hidden in the now playing view, VLC output rate not shown on desktop

## Update 2026-11-13 (late: sync, bots, search, volume, icons; compiles, not tried on a device)
- [x] Search no longer stops after the first query (the result flow was collected, never cancelled: `collectLatest` in `SearchScreen`); search also matches the year; film songs match through the album tag
- [x] Plain lyrics (no timestamps, any language) drift down with the song; dragging takes over, they follow again after 6 s
- [x] Phone mini player: devices button opens the app's Devices dialog (This phone, live linked devices with controls, Audio output switcher, Network play, Connect devices)
- [x] Desktop player bar Devices popup is live (it was a static placeholder): own addresses, linked devices with now playing and controls, paired devices away with Connect (`DevicesPanel.kt`, `LocalLanSync` from the shell)
- [x] Connection page: one entry per device with all its addresses, each editable and deletable, plus add an address to a device; "This device" card with own addresses first; sync result says it goes both ways and counts both sides
- [x] A paired device that announces itself is linked at once (old or new address, tried first), and a seen address shows "Seen on this network" instead of "Trying now"
- [x] Profile photo and name changes show a saved message
- [x] Bots page: name, @username and picture for the main bot and every extra bot (read through each bot's own TDLib session)
- [x] Spotify client id is hidden with an eye button; the browser page after Spotify login is themed (light or dark)
- [x] PC player bar, now playing view and mini player use the phone's rewind, play and fast-forward glyphs; mini player has format badge (switch in Player bar settings), width that follows the song text, play/pause/cover/progress animations, Windows rounded corners (no transparent window)
- [x] System volume: the volume bar follows and sets the Android media volume and the Windows master volume (`SystemVolume` in the player module, Core Audio through JNA); app-only volume stays where there is no system volume. Windows part is compile-checked only
- [ ] Try on screen and phone: all of the above, especially system volume on Windows, tray click, full screen, themed login page, device popups
- [ ] Idea list: remote control and live device list already exist; per-device permissions, safety code on pairing, trusted networks, offline-proof pairing (PAKE), "Only paired devices may link" on by default
- [ ] RAM (measured on the new build, idle): desktop 319 MB working set, heap 46 MB, metaspace 64 MB, Intel D3D12 driver files about 175 MB, libvlc loaded at idle (create it on first play), 52 threads, CPU 1%; Android native heap 158 MB allocated (TDLib), Java heap 14 MB, private 64 MB. Next: lazy libvlc, `-Dskiko.renderApi=OPENGL`/`SOFTWARE` test, TDLib options (no message database, smaller caches)

## Update 2026-11-13 (last: reading with every bot; compiles, not tried)
- [x] Reading speed is the real number of requests a minute (one at a time when slow; lanes only above 30 a minute) so Full rescan and Repair stop hitting Telegram's rate limit; the slider is on the Repair page and above Full rescan (Library settings)
- [x] Repair can read each chat with every logged-in extra bot (switch "Also read with the extra bots", on by default). Each bot adds only songs the library lacks; songs only an extra bot can read are stored as `bot:<id>:<remote id>` and played, pinned and given covers through that bot
- [x] Repair can also read the main chat with your own account (switch "Also read with your account", off by default); songs no bot could read are added to play through the account (the Recover page's index and `addMissingFromMainChat`)
- [ ] Try on the real chat: Repair with the bots switch on; the log shows one line per bot. Check a song that only an extra bot can read plays, and that the song count rises towards the phone's 3,112
- [ ] Not done: start extra bots only when a scan or upload needs them and stop them after idle (they all run at launch now and cost memory); find which bot sent a song from the account's view; a device without a song's owner bot falls back to the main bot, which cannot read it

## Update 2026-11-13 (end of session: clean devices, memory leaks, docs)
- [x] Devices: no addresses in the player popups; phone sheet "Play on" (current output card with the song, other devices with controls, tiles for Connection, Network play and Output); PC keeps the minimal popup with Connection and Network play buttons that open those settings pages
- [x] Memory leaks (desktop, grow with songs played): `StreamHttpServer` kept every played song registered (now the last 8), `QueueItem` cached one stream source per item for the life of the queue (now asks the resolver each time). An idle app was flat over 8 minutes (private 678 MB, heap 24 to 38 MB, 68 threads)
- [x] Extra bots run with no message database (less memory per bot)
- [x] README updated: features, release script, known limitations
- [ ] Proposed, not built: Spotify style active device. Selecting another device sends it the queue, song and position and it starts there (the first pauses); this player mirrors the active device's song and progress with a "playing on <device>" icon and controls it; selecting this device pulls the queue back; a linked device that starts playing becomes the active one on the others automatically. Needs queue and position messages in `JavaLanSync` (`LanMessage` and `sendQueue` exist as stubs) and an `activeDevice` state in `CoreBackend`
- [ ] Next memory steps: start extra bots only when a scan or upload needs them and stop them when idle; create libvlc on the first play (restoring the last session creates the player at startup); test `-Dskiko.renderApi=OPENGL` and `SOFTWARE`; measure a long listening session
- [ ] Try everything on screen and phone before trusting it: the unchecked lines of the last four sections

## Update 2026-11-13 (after the phone test: crash, standby, menu, search, sort; compiles, not tried)
- [x] Crash fixed (last log, 16:29): the system volume poller in `CoreBackend` started before `playerStarted` existed and hit a null; a rotation recreates the backend, which exposed it. The poller now starts after the player setup. The older crashes in the log (11-12) are TDLib "Receive must not be called simultaneously from two different threads" aborts, not fixed
- [x] Turning the phone no longer recreates the activity (`configChanges` on `MainActivity`)
- [x] Standby: full screen (status and navigation bars hidden, swipe from the edge shows them, draws under the cutout); burn-in drift of up to 6% of the shorter side along a path that repeats only every few minutes, including the settings gear (setting "Shift the screen slightly" is on by default); portrait and landscape layouts exist
- [x] Song menus stay 95% opaque in Glass: menus are separate windows and cannot blur the page, so text behind showed through. Real blur inside menus is not possible without drawing the menu inside the page
- [x] Search: every query word must start a word of the title, artist, album, genre or year (no more "udi" matching "Studio"); combining marks of Indian scripts stay inside a word; results ranked title, artist, album, with an exact title first; the in-list search uses the same rule (`searchTokens` in `LibraryRepository`, `searchWords` and `searchScore` in `SongLogic`)
- [x] Sort order: song lists already saved one shared order; each playlist now also remembers its own "Sort by" (`playlist_sort:<id>`, `UiPrefs.getString` and `putString`)
- [ ] Try on the phone: standby in portrait and landscape (bars hidden, drift visible), rotate during standby and during playback, the song menu in Glass, search for "udi udi" and a Malayalam word
- [ ] Not done: sort order of the albums, artists and playlists tabs themselves (only song lists and playlist contents remember theirs); the TDLib thread abort in the older crash log; menus with real blur

## Update 2026-11-13 (player cleanup, cover search, seek touches; compiles not checked, not tried)
- [x] Seek bar: only touches on the line itself seek (tap, long press, drag); brushing past the bar or the times no longer resets or skips
- [x] Full player: star and three dot buttons have no background and the title is clipped before them; bottom row is Lyrics, Shuffle, Repeat, Queue and a Tune menu (Sleep timer, Speed, Standby, Devices)
- [x] Format badge reads "FLAC" or "FLAC Hi-Res" instead of "FLAC Lossless"
- [x] Change cover starts with title and artist like Find metadata (album text found nothing); cover search no longer asks Deezer and iTunes twice
- [ ] Try on the phone: tap near the progress bar and times, long titles beside the star, the Tune menu, Change cover search. Deezer returned empty results from the PC, so covers may come from iTunes only

---

## 6. Code audit, 13 Nov 2026

Method: file inventory of every module, then a search of the code for each "not done" and "half done" line, a
sample of the `[x]` lines, and a search for code that no line mentions. Nothing was run on a device. "In the
code" means the code exists, not that it works.

### 6a. Not in the code at all (every search came back empty)
- [ ] Auto filing (new songs go to the right topic by themselves). The filing code (`filing/TopicMover`, `TopicSort`, `Topics`) only runs from the Sort General page
- [ ] Backup to a Telegram topic with a schedule (no schedule or topic backup code; `BackupExport.kt` only writes the zip)
- [ ] Neighbours view in Discover, and any other use of `recs/Suggestions.kt` (nothing calls it)
- [ ] Remote playback and PC offload; Spotify style active device handoff (`sendQueue` and the `queue` message exist, nothing uses them)
- [ ] Log out of all devices; session export and import
- [ ] Queue drag-reorder
- [ ] Windows now playing overlay and lock screen controls (`WindowsMedia.kt` has media keys only)
- [ ] Standby auto-start while charging (the standby code reads the battery level only)
- [ ] Embedded lyrics tag; pick the lyrics source order; per-song "wrong lyrics" button
- [ ] Lyrics and metadata run progress on the Home scan chip (progress shows in Tasks)
- [ ] In-app update from GitHub Releases; the app downloading TDLib
- [ ] Android folder picker for music folders (no `OpenDocumentTree`) and any folder watcher
- [ ] Camera QR scan for Network play (the page says so) and the `paperplay://netplay` manifest intent filter (the manifest only has `paperplay://spotify`)
- [ ] macOS packaging (`VlcLoader.kt` knows macOS library paths; there is no macOS target)
- [ ] Activity log kept across restarts and a warning level
- [ ] Desktop battery and notification permissions

### 6b. Partly in the code
- [~] LAN queue and now-playing sharing: transport exists, `AppServices.bindLan` ignores `queue`
- [~] Repair library reading embedded tags: applied after a download, not over cached files
- [~] Secrets: Android Keystore and Windows DPAPI protect them; on Linux and macOS `DesktopSecretStore` writes plain files
- [~] Extra bots all start at launch (no lazy start); libvlc is created at startup
- [~] Sort order is remembered for song lists and each playlist only, not for the albums, artists and playlists tabs
- [~] Netplay: join by pasted link or PIN works in code; no camera, no intent filter
- [~] Dart tests ported: only some (sync merge, LAN, netplay, album split, genre); the list in section 4 is mostly still open

### 6c. In the code but missing from this list
- [x] Work splitting between linked devices (`sync/WorkOwner.kt`: which device does song info, covers, lyrics and mood scans for a song). **Written but nothing calls it**
- [x] Library merge rules for synced libraries (`sync/LibraryMerge.kt`: the newest change to a song wins, plays take the larger count, deleted playlists and removed playlist items are remembered; has tests)
- [x] Automatic refresh of imported Spotify playlists (`integrations/PlaylistAutoSync.kt`, wired in `AppServices`, has a test)
- [x] Albums that share a name split by artist (`library/AlbumSplit.kt`, used by `CoreBackend`, has a test)
- [x] Crash capture: an uncaught exception is written to `crash.txt` and shown in the Activity log on the next start (`MainActivity.kt`)
- [x] Android Keystore secret store (`core/.../AndroidSecretStore.kt`) and Windows DPAPI store (`DesktopSecretStore.kt`)
- [x] Desktop loopback stream server for libvlc with range support (`player/.../StreamHttpServer.kt`, has a test)
- [x] Home shelves (`recs/HomeShelves.kt`: rows of albums and artists, each cover used once on the page)
- [x] CI (14 Nov): the workflow builds the Android debug and release APKs (signed with the repository keystore secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) and the Windows and Linux desktop apps on `main` and `beta`, 30 minute timeout per job. The tests and the GitHub Release job are switched off with `if: false`

### 6d. Dead or empty code (written, never used)
Removed on 13 Nov: `account/Stubs.kt` (`NoAccount`, `NoIndex`) and the empty `library/Placeholder.kt`.
- `desktopApp/.../AppTitleBar.kt`: the custom title bar that was reverted; nothing calls it
- `core/.../recs/Suggestions.kt`: written, never called
- `core/.../sync/WorkOwner.kt`: written, never called

### 6e. Build and CI to do
- [ ] Find the test that hangs `:core:jvmTest` on CI (one run ran 6 hours, run 31 ran 53 minutes), then put the tests back in the workflow
- [x] Windows CI job failed every time at the "Get libvlc" step: it now downloads with `curl --fail` from download.videolan.org and unzips with 7-Zip; the TDLib step no longer fails when the `deps` release is missing (14 Nov; the MSI step was still running when this was written)
- [~] Linux and Windows jobs are back on; the release-publishing job is still off (remove the leading `false &&` from its condition)
- [x] README now says Gradle runs on a JDK 25 daemon it downloads on first use. A machine that cannot reach the download (or the Google Maven repository for the Android plugin) cannot build
- [ ] The new tests have never been run: `backgroundAndStandbyFollowTheLayoutChoice` (backup prefs), `oldWrongTriesDoNotAddUpToALockout` and `aSuccessClearsTheOverallCount` (pairing)

### 6f. Bugs found in the audit
Fixed and pushed to `beta` (compiled by CI except where noted):
- [x] Keep-running setting was read once, so turning it off was undone by the next scan or upload
- [x] Pairing lockout counters never decayed or reset on success (15 old typos locked pairing for 5 minutes)
- [x] Shared audio import updated its list without an atomic update; crash handler was wrapped again on every activity
- [x] Manifest lacked `FOREGROUND_SERVICE` and `POST_NOTIFICATIONS` (confirm a notification shows on Android 13 and later)
- [x] Release-group cache in `MetadataEnricher` was an unlocked `HashMap` used by two workers
- [x] Backup: `bg_*` and `standby_*` prefs now follow the "apply layout" choice; `bg_image` stays per device
- [x] `StreamHttpServer.parseRange` threw on huge numbers (desktop source, not compiled by CI)
- [x] Desktop Spotify login could stall on an idle connection; reads now time out after 5 s (desktop source, not compiled by CI)
- [x] R1 Restore while connected: `AppServices.shutdown()` stops LAN sync (keeping the `lan_on` choice), netplay, playback, the main and extra bots and the account, waits for TDLib's receive loop (`TdRuntime.stop()`), then closes the database; `App.kt` calls it behind a spinner before the restore screen, and Android never reuses services that were shut down. Not tried on a device
- [x] R2 Upload retries now wait: a failed job carries a retry time that every worker honours, so Telegram's flood wait is respected (the Uploads page already shows "Retrying, attempt N")
- [x] R3 "Listened" time now adds up normal forward playback steps; a seek no longer counts as listening (`PlaybackController`)
- [x] R4 Deleting a song with no main chat set now fails with a clear message instead of removing it only locally (`DeleteQueue.deleteSong`)

Open:
- [ ] R5 Many empty `catch` blocks, for example in `JavaNetplay.kt` (the poll loop swallows every `tick` failure). They need a logger passed in (netplay has none, only a notice flow); not changed without one
- Not a bug (R6): the local stream server cutting a response short when a read comes back empty ends the connection, and the player sees the short body; nothing to fix

## Update 2026-11-14 (device toast, player sheet, motion; compiles in CI, not tried on a device)
- [x] Device toast: "Connecting to X" is shown once per device per run. `JavaLanSync.autoConnect` dials an offline paired device every 20 seconds and the old toast appeared each time (`Shell.kt`). "Connected to X" still shows on every real link
- [x] Full player is a slide-up sheet (`PlayerSheet.kt`): it follows your finger when you drag the cover down, settles open or closed with the fling speed, and shrinks with Android's back swipe (cancel puts it back). It moves with `graphicsLayer`, so it does not recompose per frame. The mini player fades as it rises; the queue and song views crossfade
- [x] Pages slide and fade (`PageTransitions.kt`): going deeper (detail page, Tasks, Settings page) slides in over the old page, going back reverses it, switching tabs fades through. On the phone page area, the desktop page card and the Settings pages
- [x] Song list and queue rows glide when they move (`animateItem`); a cover that loads late fades in over the mosaic; the nav pill uses the shared spring; the dead constant scale animation is gone
- [x] Desktop motion (14 Nov, compiles, not tried): the sidebar glides open and closed (`animatedWidth`, runs in the layout phase so `Shell` does not recompose), sidebar items and song rows ease their selected colour and lighten under the mouse pointer, the now playing panel expands and collapses from the right edge, the keyboard shortcuts overlay fades, the tray menu fades in and rises a few pixels. The page transitions, player sheet, list gliding and cover fade apply on the desktop too (they live in shared code)
- [x] Motion tokens (`theme/Motion.kt`) and a Settings > Style > Reduce motion switch (pref `reduce_motion`, per device, carried as layout in backups); everything above becomes instant with it on
- [ ] Try on the phone: drag the player down slowly, fling it, drag half way and release, back swipe and cancel, open it from the notification and the mini player quickly, rotate while it is open, switch the four tabs, open an album and go back, walk through Settings pages, scroll long lists with the glass nav on and off, reduce motion on
- [ ] Not done: press-scale on the mini player and nav items, `animateItem` on the library grids and search groups, a "moving" flag to drop blur while the sheet moves (add it only if the drag stutters on glass)
- [ ] The signed debug APK from CI failed to install on the phone with INSTALL_FAILED_NO_MATCHING_ABIS although the phone reports `arm64-v8a` and the bundled `libtdjson.so` is a valid AArch64 library. Cause unknown; waiting for the list of `lib/` folders inside the APK (`unzip -l`)
- [ ] The Windows and Linux builds have no TDLib (no `deps` release with `tdlib-windows.zip`), so those apps cannot reach Telegram yet

