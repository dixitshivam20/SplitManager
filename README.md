# SplitManager — Android Payment Auto-Splitter

Automatically detects payment SMS and app notifications, then lets you split them in Splitwise with one tap.

---

## How It Works

```
You pay → SMS / PhonePe / GPay notification arrives
        → SplitManager detects and parses it
        → Shows notification: "₹850 paid at Swiggy — Split now?"
        → You tap → Pick your Splitwise group → Done ✓
```

**Supported payment sources:**
- SMS from HDFC, SBI, ICICI, Axis, Kotak, Yes Bank, PNB, BOI
- Notifications from PhonePe, Google Pay, Paytm, CRED, Amazon Pay, BHIM, Mobikwik

---

## Build the APK

### Option A — GitHub Actions (recommended)

1. Upload this folder to a GitHub repository
2. Go to **Actions** tab → **Build SplitManager APK** → **Run workflow**
3. Wait ~5 minutes → Download `SplitManager-debug-apk` from Artifacts
4. Transfer to your phone and install

### Option B — Android Studio

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## Install on Phone

1. Transfer `app-debug.apk` to your Android phone
2. Tap the file → if prompted, enable **"Install from unknown sources"**
3. Install → Open **SplitManager**

---

## First-Time Setup

### Step 1 — Grant SMS Permission
Tap **Grant** next to "SMS permission needed" → Allow

### Step 2 — Grant Notification Access
Tap **Grant** next to "Notification access needed" → find **SplitManager** in list → toggle ON

### Step 3 — Connect Splitwise
1. Tap **Configure** → tap **"Open Splitwise Apps Page"**
2. Log into Splitwise → Register an app → copy the **API Key**
3. Paste it in SplitManager → tap **Test & Save**
4. Shows "Connected ✓" → you're live

---

## Usage

Once set up, you don't need to open the app. Every payment triggers a notification automatically:

1. Make a payment (UPI, card, net banking)
2. Tap the SplitManager notification
3. Select your Splitwise group
4. Tap **Confirm Split**

Done — your group members are notified on Splitwise instantly.

---

## Project Structure

```
app/src/main/
├── java/com/splitmanager/app/
│   ├── api/          SplitwiseApiClient.java      — Splitwise REST API
│   ├── model/        PaymentEvent.java             — Payment data model
│   │                 SplitwiseGroup.java           — Group/member model
│   ├── parser/       PaymentParser.java            — SMS/notification parser
│   ├── service/
│   │   ├── PaymentService.java                    — Core orchestrator service
│   │   ├── SmsReceiver.java                       — Bank SMS listener
│   │   └── PaymentNotificationListener.java       — App notification listener
│   ├── ui/
│   │   ├── MainActivity.java                      — Home screen
│   │   ├── SetupActivity.java                     — API key setup
│   │   └── SplitReviewActivity.java               — Split confirmation
│   └── util/
│       └── SecurePrefsHelper.java                 — Encrypted storage
└── res/
    ├── xml/network_security_config.xml            — Certificate pinning
    └── xml/data_extraction_rules.xml              — Backup exclusions
```

---

## Troubleshooting

| Problem | Fix |
|---|---|
| No notification after payment | Check SMS permission is granted |
| PhonePe/GPay not detected | Enable Notification Access for SplitManager |
| "Invalid Splitwise API key" | Re-copy key from splitwise.com/apps |
| Splitwise groups not loading | Check internet connection |

---

## Privacy & Security

- Payment messages are parsed **locally** — nothing sent to any external server
- Only the Splitwise API is called (using your own API key)
- API key stored with **AES-256-GCM** encryption backed by Android Keystore hardware
- No analytics, no tracking, no third-party SDKs
