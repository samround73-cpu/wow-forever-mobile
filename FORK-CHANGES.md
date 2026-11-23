# Changes in this fork

Based on bthginfo/wow-forever-mobile at tag `v2.4.4-mobile.24-pixel.1-fold-test.1` (21 Nov 2026). Modified November 2026.

- **Hide on-screen controls**: an "On-screen controls: Shown/Hidden" button on the launcher, a "Hide on-screen
  controls" item in the in-game bar's ⋮ menu, and a switch on the Controller tab of the quick menu (Android back
  gesture). It hides the whole touch
  overlay: movement stick, camera area, Jump/Target/Interact buttons, the toolbar and the "Check connection" pill.
  Touches then go straight to the game as a touchscreen/mouse. The choice is remembered between launches.
- **Minimise the toolbar in every mode**: the minimise arrow now also shows in touch-screen/login mode, not only
  while touch controls are on. When minimised, the "Check connection" pill is hidden too.
- **Separate app**: building with `-PforeverFork=true` uses the app ID suffix `.fork` and the name
  "WoW Forever (My Build)", so it installs beside the official apps.
- **Build workflow**: `.github/workflows/build-my-apk.yml` builds the Pixel APK (including the patched DXVK) from the
  Actions tab, with optional signing.
