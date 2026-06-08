# PhhIms — VoLTE/VoWiFi for LineageOS on Samsung Exynos devices

Open-source SIP/IMS stack for LineageOS, based on [phhusson/ims](https://github.com/phhusson/ims) and the Samsung-focused fork history from [amikhasenko/ims](https://github.com/amikhasenko/ims).

This fork is used as a privileged userspace `ImsService` / MmTel provider for Samsung Exynos devices where the vendor IMS stack is missing, unusable, or not practical to port to current LineageOS releases.

The current tested focus is Samsung Exynos LineageOS 23.x / Android 16 bring-up:

- Samsung Galaxy S9 / S9+ / Note9 / Exynos9810 (`starlte`, `star2lte`, `crownlte`)
- Samsung Galaxy S20 / S20+ / S20 Ultra / Exynos9830 (`x1s`, `y2s`, `z3s` family; current testing mainly `x1s`)
- O2 Germany / Telefónica Germany family as the main known-good carrier test environment

This is not a universal drop-in IMS replacement. It still depends on carrier provisioning, Samsung RIL behavior, correct Android telephony overlays, CarrierConfig, sepolicy, audio HAL behavior, and ROM-side integration.

## What this app does

Android expects a privileged app implementing `android.telephony.ims.ImsService` and registering itself as the MmTel provider. This app provides that service with a pure-userspace SIP/IMS stack.

At a high level it:

- requests and tracks the IMS bearer network
- reads P-CSCF information from `LinkProperties` or falls back to 3GPP DNS discovery
- performs SIP AKA registration
- reports IMS registration state and capabilities back to Android telephony
- handles VoLTE and VoWiFi voice calls with SIP and RTP
- handles basic SMS over IMS
- bridges incoming and outgoing SIP call state into Android `ImsCallSession` callbacks
- avoids tearing down active calls during LTE/IWLAN tech-only handover events

## Current status

Status is based on the current Samsung LineageOS 23.x test branches. Expect carrier and device differences.

| Area | Status |
| --- | --- |
| IMS registration | Working in current Exynos9810 and Exynos9830 tests, including retry/reconnect handling after IMS bearer loss or failed REGISTER attempts. |
| VoLTE outgoing calls | Working in tested configs, including Android call UI, SIP setup, RTP, BYE handling, and two-way audio when ROM-side audio fixes are present. |
| VoLTE incoming calls | Working in current tests for accept, local end, remote end, and reject paths. Re-test after every dialog/call-state change. |
| VoWiFi | Working enough for current testing. IWLAN/LTE route changes are still sensitive and depend heavily on QNS / CarrierConfig behavior. |
| Active VoLTE ↔ VoWiFi switching | Current code defers tech-only IMS reconnects while a call is active/pending or while media threads are still running, so QNS LTE/IWLAN flips no longer kill RTP and leave a fake silent call. Phone-info may still show the original registration tech until the call ends. |
| SMS over IMS | Basic send/receive path tested. Hardcoded carrier SMSC fallback was removed; the app now relies on framework/identity/SmsManager SMSC sources. |
| USSD/MMI | IMS UT/USSD is not the current goal. Potential USSD/MMI requests are routed over CS when IMS UT/USSD is unavailable. |
| Video calling / RCS / UT | Not a goal for now. Voice and basic SMS are the focus. |

A common non-IMS fallback mode is: Android has no usable IMS registration when a call starts, so telephony uses circuit-switched fallback and the modem may drop to GSM/EDGE during the call. In that case, inspect IMS network acquisition, P-CSCF discovery, SIP REGISTER, 401 challenge handling, and reconnect retry behavior before assuming the SIP call path failed.

## Important Samsung-specific background

Samsung devices usually do not expose a clean generic AOSP IMS stack. A working ROM needs cooperation between several layers:

1. Android telephony must bind this package as the MmTel IMS provider.
2. Carrier config and framework overlays must expose VoLTE/VoWiFi capability.
3. The RIL must expose a usable IMS APN/network and P-CSCF information, or DNS fallback must work.
4. Samsung audio HAL routing must allow userspace RTP audio instead of forcing modem/baseband call paths.
5. IMS registration must survive network loss, IWLAN/LTE transitions, REGISTER failures, and Android/QNS route changes.
6. Incoming SIP dialog state must be bridged correctly into Android call sessions, otherwise the UI may show an incoming call while the remote side keeps ringing.

The code has been iterated around these problem areas:

- delayed SIP handler startup until a valid service state/RPLMN exists
- correct AKA challenge realm handling for SIP registration
- reconnect/backoff after IMS bearer loss or failed REGISTER attempts
- avoiding IMS access switches while a call is active, pending, or media is still running
- outgoing provisional response handling for ringback/progress
- separate incoming/outgoing call session state
- incoming `INVITE` parsing robustness
- incoming accept path: build dialog state before notifying Android, then send `200 OK` and handle ACK correctly
- incoming reject path: signal busy/reject to the remote side instead of only closing Android UI state
- call cleanup after BYE/CANCEL/network failure
- SMS-over-IMS plumbing through the same SIP handler

## Repository integration

### Local manifest

```xml
<project path="packages/apps/PhhIms" remote="github" name="krazey/ims" revision="main" />
```

### `lineage.dependencies`

```json
{
  "repository": "krazey/ims",
  "target_path": "packages/apps/PhhIms",
  "branch": "main"
}
```

After syncing, initialize the `rnnoise` submodule. `repo sync` does not do this automatically:

```sh
cd packages/apps/PhhIms
git submodule update --init app/jni/rnnoise
```

## Building in-tree

Use the Soong/LineageOS build. This is the intended build path.

`Android.bp` builds `PhhIms` as a privileged platform-signed app using `platform_apis: true`, so it can access the internal telephony/IMS APIs required by `MmTelFeature`, `ImsConfigImplBase`, `Rlog`, and friends.

No Gradle build or public SDK modification is needed for production ROM builds.

Add the package from your device or common tree:

```makefile
PRODUCT_PACKAGES += \
    PhhIms
```

`PhhImsOverlay` is pulled in by the app module's `required` entry.

## Device tree integration

The exact paths differ per tree, but current Samsung Exynos9810 / Exynos9830 integration usually needs the following pieces.

### Packages

```makefile
# IMS over Wi-Fi data service and network qualification service.
# These are also useful for VoLTE-only bring-up because the telephony
# framework still expects the WLAN data/network service hooks to exist.
PRODUCT_PACKAGES += \
    Iwlan \
    QualifiedNetworksService \
    PhhIms
```

### Debug availability overrides

For bring-up, these properties are useful when carrier config defaults would otherwise hide IMS capability:

```makefile
PRODUCT_PROPERTY_OVERRIDES += \
    persist.dbg.volte_avail_ovr=1 \
    persist.dbg.wfc_avail_ovr=1 \
    persist.dbg.allow_ims_off=1
```

Do not treat these as a replacement for correct carrier config. They are bring-up helpers.

### Framework overlay

Example: `overlay/frameworks/base/core/res/res/values/config.xml`

```xml
<resources>
    <bool name="config_carrier_volte_available">true</bool>
    <bool name="config_device_volte_available">true</bool>
    <bool name="config_device_vt_available">true</bool>
    <string name="config_wlan_data_service_package">com.google.android.iwlan</string>
    <string name="config_wlan_network_service_package">com.google.android.iwlan</string>
    <string name="config_qualified_networks_service_package">com.android.telephony.qns</string>
</resources>
```

### Telephony overlay

Example: `overlay/packages/services/Telephony/res/values/config.xml`

```xml
<resources>
    <string name="config_ims_mmtel_package" translatable="false">me.phh.ims</string>
</resources>
```

### Privapp permissions

Example target path:

```makefile
PRODUCT_COPY_FILES += \
    $(COMMON_PATH)/privapp-permissions-me.phh.ims.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-me.phh.ims.xml
```

Example file:

```xml
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="me.phh.ims">
        <permission name="android.permission.READ_PRIVILEGED_PHONE_STATE"/>
        <permission name="android.permission.MODIFY_PHONE_STATE"/>
    </privapp-permissions>
</permissions>
```

### CarrierConfig overlay

A real device tree should carry a CarrierConfig overlay for the tested carrier/device combination. The debug properties above may make the UI expose toggles, but stable behavior should come from proper CarrierConfig values.

Useful areas to check:

- `carrier_volte_available_bool`
- `editable_enhanced_4g_lte_bool`
- `carrier_wfc_ims_available_bool`
- `carrier_wfc_supports_wifi_only_bool`
- `carrier_default_wfc_ims_enabled_bool`
- `carrier_default_wfc_ims_mode_int`
- `editable_wfc_mode_bool`
- IMS SMS availability
- default WFC mode and roaming behavior
- IWLAN handover policy / integrity algorithm quirks where required by the carrier/device

### Vendor IMS properties / sepolicy

Some Samsung RIL components set or read `vendor.ril.ims.*` properties. If your device tree needs this, add a vendor property type and allow the Samsung radio service to set it.

Example:

```te
# sepolicy/vendor/property.te
vendor_internal_prop(vendor_ims_prop)
```

```text
# sepolicy/vendor/property_contexts
vendor.ril.ims.                u:object_r:vendor_ims_prop:s0
```

```te
# sepolicy/vendor/sehradiomanager.te
allow sehradiomanager vendor_ims_prop:property_service set;
```

## Exynos9810 integration notes

Current Exynos9810 testing covers the Galaxy S9 / S9+ / Note9 family (`starlte`, `star2lte`, `crownlte`) on LineageOS 23.x.

Known ROM-side pieces used in current testing:

- Add `PhhIms`, `Iwlan`, and `QualifiedNetworksService` to the device/common product packages.
- Bind `me.phh.ims` as the MmTel provider through the Telephony overlay.
- Expose VoLTE/VoWiFi capability through framework overlays and carrier config.
- Carry a CarrierConfig overlay for the tested carrier. For Telefónica Germany family testing this includes deterministic WFC availability and IWLAN behavior.
- Use ROM-side audio HAL fixes where needed. The current Exynos9810 audio bring-up uses an `EXYNOS9810_CALLVOL_FIX` Soong flag in the Samsung audio HAL to map Android call volume to the Samsung receiver gain mixer control.
- Keep Samsung RIL / IMS property sepolicy in sync if your tree exposes `vendor.ril.ims.*` properties.

Validation checklist used for Exynos9810:

- IMS registers on LTE.
- Outgoing VoLTE call connects, has ringback/progress, two-way audio, and clean BYE handling.
- Incoming VoLTE accept, local end, remote end, and reject work.
- SMS send and receive work after IMS reconnects.
- USSD/MMI routes over CS when IMS UT/USSD is unavailable.
- VoWiFi registers and calls work when WFC is enabled.
- VoLTE ↔ VoWiFi transitions do not leave stale/frozen IMS registration.
- Active-call LTE/IWLAN tech-only switches do not kill RTP/media.

## Exynos9830 integration notes

Current Exynos9830 testing covers the Galaxy S20 5G / Exynos9830 family, mainly `x1s`, on LineageOS 23.x.

Known ROM-side pieces used in current testing:

- Add `PhhIms`, `Iwlan`, and `QualifiedNetworksService` to the product packages.
- Bind `me.phh.ims` as the MmTel provider.
- Expose VoLTE/VoWiFi capability through framework overlays and carrier config.
- Include the WLAN data service, WLAN network service, and QNS framework overlay strings.
- Use carrier config for WFC availability/defaults and IWLAN handover behavior.
- Be aware that QNS may select IWLAN while idle when LTE quality is weak even when the user-facing WFC mode looks like “mobile preferred”. This is expected Android/QNS policy behavior, not a SipHandler-triggered switch.

Validation checklist used for Exynos9830:

- IMS registers on LTE.
- IMS can register on IWLAN / VoWiFi when WFC is enabled.
- Outgoing and incoming calls work on VoLTE and VoWiFi.
- LTE ↔ IWLAN route changes during active calls keep audio alive.
- Phone-info may keep showing the original IMS registration tech during a deferred active-call switch; the important runtime check is that RTP and DTMF continue.
- If IMS is unavailable when a call starts, Android may use CS fallback and show GSM/GPRS/2G radio tech for that call.

## Samsung audio notes

Audio is not solved only inside this app. Samsung HALs often special-case cellular calls and may route capture/playback through modem/baseband paths instead of normal userspace audio paths.

### Exynos9810 / S9 family

The Exynos9810 LineageOS 23.x bring-up uses a ROM-side audio HAL change guarded by an `EXYNOS9810_CALLVOL_FIX` Soong flag. That fix maps Android voice-call volume to the Samsung mixer control `Rcv Digital Gain`, so the in-call earpiece volume follows the Android call volume slider.

This is not part of this app directly, but without the matching ROM-side audio fixes, the SIP/IMS stack may register and place calls while audio behavior still looks broken.

### Telecom audio mode

Some Samsung HALs treat `MODE_IN_CALL` as a modem-call path. For a userspace IMS stack, `MODE_IN_COMMUNICATION` may be required so `AudioRecord` stays on the real microphone ADC path instead of a baseband uplink PCM.

If calls connect but the microphone is silent, check the HAL routing first before assuming SIP/RTP is broken.

## Useful debug commands

```sh
adb shell dumpsys ims
adb shell dumpsys telephony.registry
adb shell dumpsys carrier_config
adb shell dumpsys connectivity
adb shell dumpsys package me.phh.ims
```

For focused IMS logs:

```sh
adb logcat -c
adb logcat -v threadtime \
  'PHH SipHandler:D' 'PHH SipConnection:D' 'PHH MmTelFeature:D' \
  'ImsPhone:D' 'ImsPhoneCallTracker:D' 'DNC-0:D' 'TNP:D' \
  'Qns:*' 'QualifiedNetworksService:*' 'Iwlan:*' \
  '*:S'
```

For broad logs:

```sh
adb logcat -b all -v threadtime | grep -iE \
  'PHH|PhhIms|SipHandler|MmTel|Ims|Iwlan|Qns|P-CSCF|REGISTER|401|INVITE|PRACK|ACK|BYE|CANCEL|RTP|SMS'
```

Useful UI check:

```text
*#*#4636#*#*
```

Check whether Android says IMS is registered and whether voice/SMS over IMS are available.

## Debugging common failure modes

### LTE call drops to 2G / EDGE

Usually means Android did not have an active IMS registration when the call started, so it used circuit-switched fallback.

Check:

- did `getVolteNetwork()` receive a valid IMS network?
- did `LinkProperties` contain P-CSCF addresses?
- did DNS fallback produce a P-CSCF?
- did the initial SIP REGISTER receive the expected `401 Unauthorized` challenge?
- did the second REGISTER use the correct realm from `WWW-Authenticate`?
- did reconnect retry trigger after failures?

### IMS registration freezes after VoWiFi/VoLTE switching

Usually means the app or framework still believes an old IMS access/network is valid.

Check:

- IWLAN/LTE registration tech reported to `ImsRegistrationImplBase`
- whether a call is active or pending while access changes
- stale `NetworkCallback` state
- reconnect/re-request of the IMS bearer after access becomes unsuitable
- whether the switch was only a tech-only LTE/IWLAN change with unchanged network/local address/P-CSCF

### Active call goes silent after LTE ↔ IWLAN switch

Check for a tech-only IMS access switch during the call:

```text
networkChanged=false
localChanged=false
pcscfChanged=false
techChanged=true
```

Current code should log and defer that reconnect while the call is active, pending, or media threads are still running:

```text
Deferring tech-only IMS reconnect while SIP call is active or pending
```

If media dies immediately after the switch, check for accidental cleanup from:

```text
Stopping call runtime state: IMS reconnect
Encode thread exiting: callStopped=true
Decode thread cleanup complete: callStopped=true
```

### Phone-info shows stale VoLTE/VoWiFi during active-call switching

This can be expected with the current deferral behavior. The app intentionally avoids re-registering mid-call for tech-only LTE/IWLAN changes, because reconnecting can kill RTP/media and leave a fake silent call. The important checks are whether RTP continues, DTMF works, and the call ends cleanly.

### Incoming call UI appears, but remote keeps ringing

Usually means Android was notified before SIP dialog state was fully usable, or accept handling did not send/complete the expected SIP response path.

Check:

- `INVITE` parsing
- `Call-ID`, tags, CSeq, Contact, Record-Route/Route handling
- whether `currentCall` exists before `notifyIncomingCall()`
- whether accept sends `200 OK`
- whether ACK is received and matched
- PRACK/100rel handling; do not wait for a PRACK that was never negotiated

### Reject/decline does not reach the caller

Check that reject sends a SIP reject response such as busy/reject while the call is still an incoming dialog. Closing only the Android call session is not enough; the remote network must receive a SIP response.

### Caller shown as unknown in call history

The incoming call profile must carry usable caller identity extras from the SIP `From`/P-Asserted-Identity information:

- `ImsCallProfile.EXTRA_OI`
- `ImsCallProfile.EXTRA_CNA`
- `ImsCallProfile.EXTRA_DISPLAY_TEXT`
- presentation flags such as `EXTRA_OIR` / `EXTRA_CNAP`

### Outgoing call has no ringback/progress

Check provisional SIP responses:

- `180 Ringing`
- `183 Session Progress`

Android should be notified with call-session progress before final answer, otherwise the remote side may ring while the local UI/audio state feels wrong.

### Audio is one-way or silent

Separate SIP success from audio routing.

If SIP says the call is established but audio is broken, check:

- Android audio mode used by Telecom
- Samsung HAL route/mixer state
- receiver/earpiece gain mixer controls
- RTP socket lifecycle
- AMR/AMR-WB codec negotiation
- whether cleanup from a previous call left stale media threads or sockets

## P-CSCF fallback

If the RIL does not report P-CSCF addresses via `LinkProperties`, the app attempts standard 3GPP DNS discovery:

```text
ims.mnc<MNC>.mcc<MCC>.3gppnetwork.org
```

A last-resort manual override is available:

```sh
adb shell setprop persist.ims.pcscf_fallback <ip>
```

## Enabling VoLTE toggle state

On some builds it helps to force the enhanced 4G setting once:

```sh
adb shell settings put global enhanced_4g_mode_enabled 1
```

On fresh installs this usually defaults to enabled when overlays/carrier config expose VoLTE correctly.

## Building with Gradle

The public SDK stubs do not expose all internal IMS APIs used here. For development-only Gradle builds, you need a full `android.jar` from an AOSP/LineageOS build in `app/libs/android.jar`, and you may need to remove duplicate public IMS stubs from the platform SDK jar.

For ROM integration, use the in-tree Soong build instead.

## Notes

- The app has no launcher icon and does not appear in the app drawer.
- The app must be privileged and platform-signed.
- Carrier provisioning still matters. A carrier that does not provision IMS for the SIM/device combination may never register.
- Keep registration, VoLTE, VoWiFi, SMS, and audio changes in separate commits while rebasing; it makes regressions much easier to isolate.
- For Samsung bring-up, always test: registration, outgoing call, incoming accept, incoming reject, SMS, VoWiFi-only, VoLTE-only, VoLTE→VoWiFi, VoWiFi→VoLTE, and active-call route switching.

## License

GPL-2.0, following the upstream project.
