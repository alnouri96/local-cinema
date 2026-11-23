# Cinema Engine implementation checkpoint

The current APK uses the pinned NOVA 6.4.51 ARMv7 native binaries. Java settings
cannot change the FFmpeg parser, IEC61937 packetization, or native cache inside
those binaries.

- HardwareCapabilityProfiler.java reports Android-visible RAM, display HDR
  modes, advertised Dolby Vision Profile 7 decoding, and display modes.
  Cinema Ultra eligibility is informational at this checkpoint.
- CinemaDisplayModes.java selects an advertised display mode with an integer
  cadence match. Resolution matching prefers the exact video dimensions when
  available, with the existing resolution as the fallback.
- Player.java uses FFmpeg-derived rational FPS already exposed by NOVA, delays
  preparation when requesting a different display mode, and clears the window
  and surface requests when playback stops. PlayerActivity also clears them on
  destruction.
- CinemaSettingsActivity.java exposes these settings under Cinema Engine.
  Until a choice is made, the existing NOVA refresh-rate setting applies.

The TCL reports 2.35 GiB and is below the 2.8 GiB eligibility threshold.
The APK built, installed, and opened this settings page. Physical HDMI mode
transitions and the audio/video delay require playback validation.

## Native work remaining

Build the pinned AVOS and FFmpeg sources through the native build path and
validate parity with the current APK before changing native behavior.
The current PowerShell script selects prebuiltNative and extracts the upstream
libraries on every build. Native Dolby Vision base-layer selection, dynamic
HDR10+ control, route- and codec-specific passthrough, HDR subtitle brightness,
and configurable buffering are pending. The settings page exposes only the
controls wired to playback in this checkpoint.

Relevant Kodi source references are WinSystemAndroid for display mode requests,
DVDVideoCodecAndroidMediaCodec for decoder negotiation, ActiveAE for audio
routing and passthrough, and system/settings/settings.xml for settings.
The Java changes are an implementation for the existing NOVA integration.

## Audio sync preview

NOVA 6.4.51 includes the upstream audio clock changes for seeks. The existing
Local Cinema app version and package ID are retained. AVOS audio/subtitle track
metadata strides include the upstream disposition field. The Dolby Vision
callback maps to the existing preference. Legacy frame timing is optional
because the new native engine does its own scheduling.

On TCL, a 4K Dolby Vision episode (DD+ 5.1 over SMB) resumed and kept playing
after seeks to 50% and 60%. ADB confirmed advancing positions and a started
AudioTrack. Audible lip sync still requires listening on the TV.