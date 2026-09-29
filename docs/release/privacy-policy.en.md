# Anomalops privacy policy (draft)

Translation of privacy-policy.md, first draft of 2026-09-29 · Status: draft; section 6 of the Chinese original lists what must be confirmed before publishing

---

## Anomalops privacy policy

Effective date: (set on publishing)

Anomalops is an open-source underwater camera app. Its source code is public at
<https://github.com/tengigabytes/Anomalops>. The app has no accounts, no ads and no servers of its own.

### 1. Data the app creates on your phone

| Data | Purpose | Where it is kept | How to delete it |
| --- | --- | --- | --- |
| Photos (JPEG / Ultra HDR JPEG) and the RAW files (DNG) you choose to keep | The pictures you take | The phone's photo library, folder `Pictures/Anomalops` | Delete them in a gallery or file manager app; uninstalling the app does not delete them |
| Sensor log while dive lock is on: air pressure, ambient light, magnetometer, battery temperature and level, system thermal status, touch positions and times, camera settings of each shot | Analysing touch operation and heat inside the housing afterwards | The app's own storage (`Android/data/io.github.tengigabytes.anomalops/files/dives/`) | Removed by the system when you uninstall the app; can also be deleted with a file manager app |
| Settings: mounted filter, dive-lock state | Kept for the next launch | The app's private settings file | Uninstall the app, or clear its data in system settings |

This data stays on your phone. The app does not read your location, contacts, microphone or other apps' files.

### 2. Permissions

- **Camera**: to take pictures and show the preview.
- Other permissions depend on the edition; see section 3.

### 3. Network and third parties

- **Editions from GitHub and F-Droid (`foss`)**: the app declares no network permission, so it cannot send any data
  off the phone.
- **Google Play edition (`play`)**: includes the Google Play Billing Library, used only for voluntary donations,
  and has network permission for it. When you donate, Google Play handles the payment; the Anomalops developer
  receives only the transaction record Google provides and never your payment details. The Google Play Billing
  Library may send its own usage records to Google, which the [Google Privacy Policy](https://policies.google.com/privacy)
  covers. Apart from that, the app sends no photos, sensor logs or other data.

### 4. Children

The app is not directed at children and collects no personal data from anyone.

### 5. Contact and changes

Please open an issue on the GitHub project: <https://github.com/tengigabytes/Anomalops/issues>. When this policy
changes, the effective date is updated, and the history of changes is on GitHub.
