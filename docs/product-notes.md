# Product Notes

## Product Direction

Moon Service helps photographers catch photogenic Moon opportunities near locations they care about. The core value is a timely alert that says, in effect: the Moon will be low, visible, and the weather may be worth trying.

The MVP should be alert-first and Android-friendly. It should not attempt to clone full planning tools such as The Photographer's Ephemeris. Advanced composition planning can come later.

## Target User

The first user is a photographer who wants practical prompts for local Moon shots:

- Moon near the horizon.
- Ambient light still useful for foreground detail.
- Forecast suggests clear sky, partial cloud, or visually interesting conditions.
- Alert arrives early enough to travel or set up.

The product should support saved locations before saved compositions. Exact landmark alignment is a later feature.

## MVP User Promise

No account required. Locations and alert settings live on the device by default. Add email only if backup, email alerts, or calendar integration becomes useful.

The first useful alert can include:

- Location name.
- Date and time window.
- Moon altitude and azimuth.
- Moon phase or illumination.
- Sun state, such as daylight, golden hour, civil twilight, or night.
- Weather summary and confidence.
- A short reason the opportunity scored well.

## MVP Scope

In scope:

- Save one or more locations.
- Find upcoming Moon windows for those locations.
- Score opportunities using Moon geometry, Sun state, and weather.
- Present ranked opportunities.
- Trigger local Android notifications or manual refresh during early prototype.

Out of scope for the first MVP:

- Mandatory accounts.
- Full map planning.
- Exact house/church/landmark alignment.
- Terrain and obstruction modeling.
- Cross-device sync.
- Calendar OAuth.
- Paid subscriptions.

## Privacy Stance

The product should avoid collecting permanent location data by default.

If the backend is used for scoring, the first API can be stateless: the app sends locations and preferences, and the backend returns ranked opportunities without storing the request beyond operational logs. If push subscriptions or cloud sync are introduced, document exactly what is stored and why.

## Identity Direction

Avoid mandatory accounts initially.

Preferred future identity model:

- Anonymous device identity created at install.
- Local credential storage on Android.
- Optional recovery code.
- Optional email magic link for backup, email notifications, or calendar features.
- Push token registration can be associated with the anonymous identity later.

Device-only identity must not become a trap. Recovery should be designed early, even if implemented minimally.

## Later Product Ideas

- Map view with Moon azimuth corridor.
- Shooting-position to subject-position planning.
- Focal length and composition hints.
- Horizon elevation and terrain modeling.
- Skyline or building obstruction checks.
- Saved compositions.
- `.ics` export and later calendar OAuth.
- Email alerts for users who opt in.
