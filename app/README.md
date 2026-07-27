# :app

The main application module that integrates all features and hosts app-specific UI screens not large enough for their own module.
`PodcastApp` initializes the app; `ClientConfigurator` registers service implementations (download, sync) at startup.

The miniplayer (the collapsed player bar at the bottom of the screen) is implemented in `ExternalPlayerFragment`.
It is hosted in `MainActivity` as a bottom sheet. `MainActivity` controls its visibility via `setPlayerVisible()` based on playback state.

## Screen layouts

The app draws edge to edge and `MainActivity` consumes only the navigation bar insets, so every full-screen
fragment has to claim the status bar inset itself. Put the toolbar inside an `AppBarLayout` carrying
`android:fitsSystemWindows="true"` (see `queue_fragment.xml`); a bare `Toolbar` at the top of a layout ends up
underneath the clock and battery icons.

## Wear OS Communication (play flavor only)

`WearListenerService` is a `WearableListenerService` that handles `DataLayer` messages from connected watches.
It responds to watch-initiated requests.
The service is kept alive by the Android framework while at least one watch is connected.
