package com.splitmanager.app.parser;

import com.splitmanager.app.model.PaymentEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Indian bank SMS — UPI, Debit/Credit Card, NEFT, IMPS, RTGS.
 *
 * Security:
 * - Input capped before regex runs
 * - Amount anchored to debit context (prevents credit+debit SMS attack)
 * - All patterns compiled once (static final, thread-safe)
 */
public class PaymentParser {

    private static final int    MAX_INPUT_LENGTH = 2000;
    private static final double MAX_AMOUNT       = 1_000_000.0;
    private static final double MIN_AMOUNT       = 1.0;

    // ── Amount patterns ──────────────────────────────────────────────────────

    // Pattern A: debit keyword THEN amount  e.g. "debited Rs. 210.00"
    // Note: "non-payment" is filtered before this runs via NON_PAYMENT_FILTER in isPaymentMessage
    private static final Pattern DEBIT_THEN_AMOUNT = Pattern.compile(
        "(?:debited?|spent|paid(?!\\s+to\\s+you)|payment(?:\\s+of)?|purchase(?:d)?|" +
        "withdrawn|sent|transferred|charged|utilized|used|deducted|done|approved|processed)" +
        "[^.]{0,80}?(?:Rs\\.?|INR|\\u20B9)\\s*([0-9]{1,8}(?:,[0-9]{2,3})*(?:\\.[0-9]{1,2})?)",
        Pattern.CASE_INSENSITIVE
    );

    // Pattern B: amount THEN debit keyword  e.g. "Rs. 210 debited"
    private static final Pattern AMOUNT_THEN_DEBIT = Pattern.compile(
        "(?:Rs\\.?|INR|\\u20B9)\\s*([0-9]{1,8}(?:,[0-9]{2,3})*(?:\\.[0-9]{1,2})?)" +
        "[^.]{0,40}?(?:debited?|spent|payment|withdrawn|sent|charged|deducted)",
        Pattern.CASE_INSENSITIVE
    );

    // Pattern C: "transaction of Rs. X" / "txn of Rs. X"
    private static final Pattern TXN_AMOUNT = Pattern.compile(
        "(?:transaction|txn|purchase|payment)\\s+(?:of|for|worth)?\\s*(?:Rs\\.?|INR|\\u20B9)\\s*" +
        "([0-9]{1,8}(?:,[0-9]{2,3})*(?:\\.[0-9]{1,2})?)",
        Pattern.CASE_INSENSITIVE
    );

    // Pattern D: Card-specific  e.g. "INR 500.00 spent on card"
    private static final Pattern CARD_AMOUNT = Pattern.compile(
        "(?:Rs\\.?|INR|\\u20B9)\\s*([0-9]{1,8}(?:,[0-9]{2,3})*(?:\\.[0-9]{1,2})?)" +
        "\\s+(?:spent|used|charged|debited|done)\\s+(?:at|on|for|via|using|by)",
        Pattern.CASE_INSENSITIVE
    );

    // ── Merchant patterns ────────────────────────────────────────────────────

    private static final Pattern MERCHANT_TO = Pattern.compile(
        "(?:to|at|with|for|payee:|paid to|towards|merchant:?)\\s+" +
        "([A-Za-z0-9][A-Za-z0-9 &'.\\-/]{1,35}?)(?=\\s*(?:via|on|\\.|,|Ref|UPI|\\d|$|using))",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern MERCHANT_AT = Pattern.compile(
        "(?:spent|debited|charged|purchase(?:d)?)\\s+(?:at|on|by|from)\\s+" +
        "([A-Za-z0-9][A-Za-z0-9 &'.\\-]{1,35})",
        Pattern.CASE_INSENSITIVE
    );

    // ── Reference patterns ───────────────────────────────────────────────────

    private static final Pattern REF_PATTERN = Pattern.compile(
        "(?:Ref(?:erence)?(?:\\s*No\\.?)?|UPI(?:\\s*Ref)?|" +
        "Txn(?:\\s*(?:ID|No\\.?)?)?|Transaction(?:\\s*ID)?|" +
        "IMPS|NEFT|RTGS|Approval|Auth)[\\s:#]*([A-Z0-9]{6,25})",
        Pattern.CASE_INSENSITIVE
    );

    // ── Debit / Credit keywords ──────────────────────────────────────────────

    private static final Pattern DEBIT_PATTERN = Pattern.compile(
        "(?:debited?|debit|spent|paid|payment|purchase(?:d)?|withdrawn|" +
        "sent|transfer(?:red)?|charged|utilized|used|deducted|done|" +
        "approved|processed|successful|txn of|transaction of|card txn)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CREDIT_PATTERN = Pattern.compile(
        "(?:credited|received|refund(?:ed)?|reversal|cashback|reward(?:ed)?|" +
        "deposited|added to|top.?up|loaded)",
        Pattern.CASE_INSENSITIVE
    );

    // Catches billing/reminder SMS that are NOT actual debit events
    private static final Pattern NON_PAYMENT_FILTER = Pattern.compile(
        "(?:non-payment|due to non.payment|suspended|overdue|bill\s+due|" +
        "minimum\s+due|total\s+due|please\s+pay\s+(?:immediately|now|to)|" +
        "payment\s+due|unpaid|pending\s+payment|pay\s+to\s+restore|" +
        "outstanding\s+(?:amount|dues|balance)|payable|bill\s+dated|" +
        "to\s+avoid\s+disconnection|service\s+suspended|connection\s+suspended)",
        Pattern.CASE_INSENSITIVE
    );

    // Catches promotional / marketing messages — loan offers, credit limit ads, EMI schemes,
    // insurance ads — that contain an amount and slip past the debit detector because they
    // use words like "approved". Unlike NON_PAYMENT_FILTER (billing reminders), these are
    // unsolicited offers that were never actual transactions.
    //
    // Key design rules (to avoid false positives on real payment messages):
    //   - "approved for a loan/credit" → promo. "transaction approved, Rs.X debited" → real.
    //   - "up to Rs. X" → promo (ceiling). Real debits always state the exact amount.
    //   - "insurance premium starting/for Rs." → ad. "insurance premium of Rs. X debited" → real.
    //   - "no cost EMI / 0% EMI" → always a purchase offer, never a debit confirmation.
    private static final Pattern PROMOTIONAL_FILTER = Pattern.compile(
        "(?:" +
        // Loan offers — 'approved/pre-approved for a loan/credit' is promotional;
        // 'transaction approved' / 'payment approved' (no loan/credit noun after) is real.
        "pre.?approved\\s+(?:for\\s+)?(?:a\\s+)?(?:loan|credit|offer)|" +
        "(?:pre.?approved|approved)\\s+for\\s+(?:a\\s+)?(?:loan|credit\\s+limit|personal\\s+loan|home\\s+loan|car\\s+loan|gold\\s+loan|business\\s+loan|instant\\s+loan)|" +
        "eligible\\s+for\\s+(?:a\\s+)?(?:loan|credit|offer)|" +
        "avail\\s+(?:a\\s+)?(?:loan|credit|offer)|" +
        "(?:personal|home|car|business|gold|instant)\\s+loan\\s+(?:offer|approved|available|up\\s+to)|" +
        "loan\\s+(?:offer|approved|available|amount|limit)\\s+(?:of\\s+)?(?:up\\s+to\\s+)?(?:Rs\\.?|INR|\\u20B9)|" +
        // 'up to Rs.' / 'get up to Rs.' — ceiling phrasing is unique to promotions;
        // real debit alerts always state the exact amount debited, never a ceiling.
        "(?:up\\s+to|upto)\\s+(?:Rs\\.?|INR|\\u20B9)|" +
        "get\\s+(?:up\\s+to|upto)\\s+(?:Rs\\.?|INR|\\u20B9)|" +
        // Credit limit increase offers
        "credit\\s+limit\\s+(?:has\\s+been\\s+)?(?:increased|enhanced|upgraded|raised)|" +
        "(?:increase|enhance|upgrade|raise)\\s+(?:your\\s+)?credit\\s+limit|" +
        "your\\s+credit\\s+limit\\s+(?:is\\s+now|has\\s+been\\s+set|will\\s+be)|" +
        // Insurance offers — requires offer-pricing language (for/starting/at Rs.) to
        // avoid blocking real insurance premium debits ("insurance premium of Rs. X debited")
        "(?:term|life|health|motor|vehicle)\\s+insurance\\s+(?:for|from|at|starting)\\s+(?:Rs\\.?|INR|\\u20B9)|" +
        "insurance\\s+(?:plan|offer|policy)\\s+(?:for|from|at|starting)\\s+(?:Rs\\.?|INR|\\u20B9)|" +
        "insurance\\s+premium\\s+(?:starting|as\\s+low\\s+as|just)\\s+(?:Rs\\.?|INR|\\u20B9)|" +
        // Promotional CTAs with URLs — "apply/get now https://..." is a definitive promo signal
        "(?:apply\\s+now|click\\s+here|get\\s+now|avail\\s+now)\\s*(?:at\\s+)?https?://|" +
        "(?:limited\\s+time|special|exclusive|festive|pre.?launch)\\s+offer|" +
        "offer\\s+(?:valid|expires?|ends?)\\s+(?:till|until|on)|" +
        // You are eligible / selected / pre-qualified for something
        "you\\s+(?:are|have\\s+been)\\s+(?:eligible|selected|chosen|pre.?qualified)\\s+for|" +
        "congratulations[,!]?\\s+you.?(?:ve|r|re)?\\s+(?:been\\s+)?(?:pre.?)?approved\\s+for|" +
        // EMI schemes — 'no cost EMI' / '0% EMI' are always purchase promotions,
        // never debit confirmations. 'EMI of Rs. X debited' (real) does not match these.
        "(?:no[\\s\\-]cost\\s+emi|0%\\s+emi|zero\\s+(?:cost\\s+)?emi)|" +
        "emi\\s+(?:starts?\\s+(?:from|at)|starting\\s+(?:from\\s+)?(?:at\\s+)?|of\\s+just\\s+|as\\s+low\\s+as\\s+)(?:Rs\\.?|INR|\\u20B9)|" +
        // Earn/win cashback ads — distinct from actual cashback credits to your account
        "(?:earn|win)\\s+(?:up\\s+to\\s+)?(?:Rs\\.?|INR|\\u20B9)\\s*[0-9,]+\\s*(?:cashback|reward|bonus)\\s+on\\s+(?:every|your\\s+next)|" +
        // Pre-qualified / pre-selected for a loan/credit
        "(?:pre.?qualified|pre.?selected)\\s+for\\s+(?:a\\s+)?(?:loan|credit|offer)" +
        ")",
        Pattern.CASE_INSENSITIVE
    );

    // Catches OTP / login / password messages — never actual payments
    // These often contain "transaction" or "used" which confuse the debit detector
    //
    // SECURITY FIX: "\\b" (four backslashes in source = \b in the compiled string = regex
    // word boundary) is required. The original "\b" (two backslashes) compiles to a literal
    // backspace character (0x08) in the Java string, which the regex engine silently ignores,
    // making the digit-boundary anchor ineffective and allowing OTP messages whose digit
    // sequences are not at a word boundary to bypass this filter.
    private static final Pattern OTP_FILTER = Pattern.compile(
        "(?:OTP|one.time.pass(?:word|code)?|login\\s+(?:code|otp)|" +
        "verification\\s+(?:code|otp)|security\\s+code|" +
        "do not share|never share|not share.*(?:otp|code|pin)|" +
        "\\b[0-9]{4,8}\\b\\s+is\\s+(?:your|the)\\s+(?:otp|code|pin|password)|" +
        "(?:your|the)\\s+(?:otp|code|pin|password)\\s+(?:is|:)\\s*[0-9]{4,8}\\b|" +
        "valid\\s+for\\s+[0-9]+\\s+(?:min|minute|second)|" +
        "expires?\\s+in\\s+[0-9]+\\s+(?:min|sec)|" +
        "use\\s+[0-9]{4,8}\\b\\s+(?:to|for)\\s+(?:login|verify|confirm|complete|authenticate))",
        Pattern.CASE_INSENSITIVE
    );

    // ── Comprehensive bank/card sender IDs ──────────────────────────────────
    // Covers: SBI, HDFC, ICICI, Axis, Kotak, Yes, PNB, BOI, Indian Bank,
    // BOB, Canara, Union, IDFC, Federal, SIB, Karnataka, RBL, IndusInd,
    // UCO, Central, IOB, PSB, Bandhan, IDBI + all major credit card issuers

    private static final String[] BANK_SENDERS = {
        // ── SBI ──
        "SBIINB","SBIPSG","CBSSBI","SBIUPI","SBICRD","SBIBNK",
        // ── HDFC ──
        "HDFCBK","HDFCBANK","HDFCBN","HDFCCC","HDFCRD",
        // ── ICICI ──
        "ICICIB","ICICIBANK","ICICIN","ICICRD","ICICID",
        // ── Axis ──
        "AXISBK","AXISBN","AXISBANKL","AXISRD","AXISCC",
        // ── Kotak ──
        "KOTAKB","KOTAK","KOTAKRD","KOTMAH",
        // ── Yes Bank ──
        "YESBNK","YESBANKL","YESRD","YESCC","YESBK","YESCRD","YESCRDT","YESBKL",
        // ── PNB ──
        "PNBSMS","PNBBNK","PNBPAY",
        // ── Bank of India ──
        "BOIIND","BOINDB","BOISBI",
        // ── Indian Bank ──
        "INDBNK","INDBK","INDBK1","INDNBK","INDBNK2",
        // ── Bank of Baroda ──
        "BOBIDB","BOBSMS","BARODM","BARBAD","BOBBRD",
        // ── Canara Bank ──
        "CNRBNK","CNBBNK","CANBK","CANBNK","CANABN",
        // ── Union Bank ──
        "UBISMS","UBIBNK","UNIONB","UBINDB","UNIOND",
        // ── IDFC First ──
        "IDFCFB","IDFBNK","IDFCBK","IDFCRD",
        // ── Federal Bank ──
        "FDRLBK","FEDBK","FEDBNK","FEDKBL",
        // ── South Indian Bank ──
        "SIBSMS","SIBANK","SIBPAY",
        // ── Karnataka Bank ──
        "KTKBNK","KARNBK","KTKSMS",
        // ── RBL Bank ──
        "RBLBNK","RBLBK","RBLCRD",
        // ── IndusInd ──
        "INDSUP","INDUSB","INDUSL","INDNDB","INDIND",
        // ── UCO Bank ──
        "UCOBNK","UCOBAN","UCOSMS",
        // ── Central Bank ──
        "CBIBNK","CBISBI","CENTIN",
        // ── Indian Overseas ──
        "IOBANK","IOBSMS","IOBBNK",
        // ── Punjab & Sind ──
        "PSBSMS","PSBBNK","PUNJSB",
        // ── Bandhan Bank ──
        "BANDHN","BANDHB","BANDSM",
        // ── IDBI ──
        "IDBIBN","IDBIBK","IDBISM",
        // ── Saraswat Bank ──
        "SRSWT","SARSMS",
        // ── Citi Bank ──
        "CITI","CITIBN","CITIBK","CITICC",
        // ── Standard Chartered ──
        "SCBSMS","SCBBNK","SCBANK","STDCRD",
        // ── HSBC ──
        "HSBCSM","HSBCIN","HSBC",
        // ── American Express ──
        "AMEXIN","AMEXCC","AMEXSM",
        // ── Credit Card specific senders ──
        "SBICRD","HDFCCC","ICICRD","AXISCC","KOTMAH","HDFCRD",
        "SBICC","INDBNK","RBLCRD","IDFCRD","BOBICC",
        // ── UPI / Payment apps ──
        "PAYTM","PHONEPE","GPAY","CRED","CREDPAY","CREDCC","AMAZONPAY","MOBIKWIK",
        "JIOMNY","BHARPE","FREECHARGE","AIRPAY","LAZYP","LAZYPAY",
        // ── Fintech credit cards ──
        "UNICRD","UNIPAY","UNICAR",           // UNI Card
        "ONECRD","ONECAR","ONECAD","ONECARD", // OneCard
        "SCAPIA","SCAPFD","SCAPCA",           // Scapia Card
        "SLICEP","SLICRD","SLICE",            // Slice Card
        "FIMONY","FIMONP","FIMONE",           // Fi Money
        "JUPBNK","JUPITB","JUPMON",           // Jupiter
        "FREOCR","FREOCP",                    // Freo
        "KRDBEE","KRDBE","KRDBIT",            // Kreditbee
        "CASHE","CASHEQ",                     // Cashe
        "KISSHT",                             // Kissht
        "STASHF",                             // StashFin
        "FIBEIN","EARSAL",                    // Fibe / EarlySalary
        "PAYUMF","PAYUIN","PAYUCR",           // PayU
        "AMZNPAY","AMZPAY",                   // Amazon Pay (alt IDs)
        "OLAMNY","OLAMON",                    // Ola Money
        "SIMPLP","SIMPL",                     // Simpl
        "ZESTMO","ZESTMN",                    // ZestMoney
        "NAVIAP","NAVIBN","NAVICR",           // Navi
        "MMTBNK","MMTCRD",                    // MakeMyTrip ICICI Card
        "SWIGGY","BLINKT",                    // Swiggy Money / Blinkit
        // ── Telecom (sometimes send debit alerts) ──
        "AIRTEL","JIOBNK","VODAFN",
    };

    // ── Public API ────────────────────────────────────────────────────────────

    // Known RCS/RBM domain suffixes used by Indian banks and telecoms
    private static final String[] RCS_DOMAINS = {
        "@rbm.goog",           // Google RBM — most Indian banks (Yes Bank, HDFC, ICICI, SBI...)
        "@msg.fi.google.com",  // Google Fi RCS (rare in India)
        "@airtel-rbm.com",     // Airtel IQ RCS Business Messaging
        "@airteliq.in",        // Airtel IQ alternate domain
        "@jiorcs.com",         // Jio RCS
        "@jiobankrcs.com",     // Jio Payments Bank RCS
        "@vi-rcs.in",          // Vi (Vodafone Idea) RCS
        "@rcs.bsnl.co.in",     // BSNL RCS (limited)
        "@businessmsg.google", // Google Business Messages alternate
    };

    // Bank name keywords to match inside any RCS sender address
    private static final String[] BANK_RCS_KEYWORDS = {
        "YES","HDFC","ICICI","SBI","AXIS","KOTAK","PNB","BOB","BOI","CANARA",
        "UNION","INDIAN","INDUS","FEDERAL","IDFC","RBL","UCO","BANDHAN","IDBI",
        "CITI","HSBC","AMEX","SCBANK","AIRTEL","JIO","PAYTM","PHONEPE","AMAZON","CRED",
        "SLICE","SCAPIA","ONECARD","JUPITER","FIMONEY","UNI","NAVI","LAZYPAY",
    };

    public static boolean isBankSender(String sender) {
        if (sender == null) return false;

        String senderLower = sender.toLowerCase();

        // Check all known RCS/RBM domains
        for (String domain : RCS_DOMAINS) {
            if (senderLower.endsWith(domain)) {
                // Extract the local part before the @ and strip non-alpha chars
                String localPart = senderLower
                    .substring(0, senderLower.lastIndexOf('@'))
                    .replaceAll("[^a-z]", "")
                    .toUpperCase();
                // Match against sender IDs list
                for (String s : BANK_SENDERS) {
                    if (localPart.contains(s)) return true;
                }
                // Match against bank keyword list (broader)
                for (String kw : BANK_RCS_KEYWORDS) {
                    if (localPart.contains(kw)) return true;
                }
                return false;
            }
        }

        // SECURITY FIX: if the sender contains '@' but did NOT match any known RCS domain
        // above, it is an unknown domain address (e.g. "HDFCBANK@evil.com").
        // Falling through to the DLT path would strip '@' and '.' and then match the bank
        // ID substring ("HDFCBANK") — incorrectly accepting a spoofed sender.
        // Reject any '@'-containing sender that isn't a known RCS domain.
        if (senderLower.contains("@")) return false;

        // Traditional DLT SMS sender — strip prefixes like "VM-", "BP-", "TP-"
        String upper = sender.toUpperCase().replaceAll("^[A-Z]{2}-", "").replaceAll("[^A-Z0-9]", "");
        if (upper.length() > 25) return false;
        for (String s : BANK_SENDERS) {
            if (upper.contains(s)) return true;
        }
        return false;
    }

    public static boolean isPaymentMessage(String body) {
        if (body == null || body.length() > MAX_INPUT_LENGTH) return false;
        // Reject OTP / login / verification codes
        if (OTP_FILTER.matcher(body).find()) return false;
        // Reject billing reminders, suspension notices, overdue alerts
        if (NON_PAYMENT_FILTER.matcher(body).find()) return false;
        // Reject promotional messages — loan offers, credit limit ads, EMI schemes,
        // insurance ads — that contain amounts but are not actual debit transactions.
        // e.g. "pre-approved for a loan up to Rs. 9,00,000" passes the debit detector
        // because "approved" and the amount match, but PROMOTIONAL_FILTER catches it.
        if (PROMOTIONAL_FILTER.matcher(body).find()) return false;
        boolean hasDebit  = DEBIT_PATTERN.matcher(body).find();
        boolean hasAmount = hasDebitAmount(body);
        return hasAmount && hasDebit && !isPureCredit(body);
    }

    public static PaymentEvent parse(String message, String sender) {
        if (message == null || message.isEmpty()) return null;
        if (message.length() > MAX_INPUT_LENGTH) {
            message = message.substring(0, MAX_INPUT_LENGTH);
        }

        double amount = extractDebitAmount(message);
        if (amount < 0) return null;

        PaymentEvent event = new PaymentEvent();
        event.setAmount(amount);
        event.setMethod(detectMethod(message));
        event.setMerchant(extractMerchant(message));
        event.setReferenceId(extractReference(message));
        return event;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static boolean hasDebitAmount(String msg) {
        return DEBIT_THEN_AMOUNT.matcher(msg).find()
            || AMOUNT_THEN_DEBIT.matcher(msg).find()
            || TXN_AMOUNT.matcher(msg).find()
            || CARD_AMOUNT.matcher(msg).find();
    }

    private static double extractDebitAmount(String msg) {
        double amt;

        Matcher m1 = DEBIT_THEN_AMOUNT.matcher(msg);
        if (m1.find()) { amt = parseAmount(m1.group(1)); if (isValid(amt)) return amt; }

        Matcher m2 = AMOUNT_THEN_DEBIT.matcher(msg);
        if (m2.find()) { amt = parseAmount(m2.group(1)); if (isValid(amt)) return amt; }

        Matcher m3 = CARD_AMOUNT.matcher(msg);
        if (m3.find()) { amt = parseAmount(m3.group(1)); if (isValid(amt)) return amt; }

        Matcher m4 = TXN_AMOUNT.matcher(msg);
        if (m4.find()) { amt = parseAmount(m4.group(1)); if (isValid(amt)) return amt; }

        return -1;
    }

    private static boolean isPureCredit(String body) {
        boolean hasDebit  = DEBIT_PATTERN.matcher(body).find();
        boolean hasCredit = CREDIT_PATTERN.matcher(body).find();
        if (hasCredit && !hasDebit) return true;
        Matcher cm = CREDIT_PATTERN.matcher(body);
        Matcher dm = DEBIT_PATTERN.matcher(body);
        int ci = cm.find() ? cm.start() : Integer.MAX_VALUE;
        int di = dm.find() ? dm.start() : Integer.MAX_VALUE;
        return hasCredit && ci < di;
    }

    private static double parseAmount(String s) {
        try { return Double.parseDouble(s.replace(",", "")); }
        catch (NumberFormatException e) { return -1; }
    }

    private static boolean isValid(double v) {
        return v >= MIN_AMOUNT && v < MAX_AMOUNT && !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static PaymentEvent.PaymentMethod detectMethod(String msg) {
        String lo = msg.toLowerCase();
        if (lo.contains("upi"))                                       return PaymentEvent.PaymentMethod.UPI;
        if (lo.contains("credit card") || lo.contains("creditcard")
            || lo.contains("cc ") || lo.contains(" cc "))            return PaymentEvent.PaymentMethod.CREDIT_CARD;
        if (lo.contains("debit card") || lo.contains("dc "))         return PaymentEvent.PaymentMethod.DEBIT_CARD;
        if (lo.contains("neft") || lo.contains("rtgs"))              return PaymentEvent.PaymentMethod.NET_BANKING;
        if (lo.contains("imps"))                                      return PaymentEvent.PaymentMethod.NET_BANKING;
        if (lo.contains("net banking") || lo.contains("netbanking")) return PaymentEvent.PaymentMethod.NET_BANKING;
        if (lo.contains("wallet") || lo.contains("paytm")
            || lo.contains("phonepe") || lo.contains("gpay"))        return PaymentEvent.PaymentMethod.WALLET;
        return PaymentEvent.PaymentMethod.UPI;
    }

    private static String extractMerchant(String msg) {
        Matcher m1 = MERCHANT_TO.matcher(msg);
        if (m1.find()) {
            String s = m1.group(1).trim();
            if (s.length() > 1) return cleanMerchant(s);
        }
        Matcher m2 = MERCHANT_AT.matcher(msg);
        if (m2.find()) {
            String s = m2.group(1).trim();
            if (s.length() > 1) return cleanMerchant(s);
        }
        return extractKnownMerchant(msg);
    }

    private static String cleanMerchant(String raw) {
        raw = raw.replaceAll("(?i)\\s*(pvt|ltd|limited|india|store|foods|technologies|services|private)\\s*$", "").trim();
        // Remove trailing punctuation
        raw = raw.replaceAll("[.,;:]+$", "").trim();
        if (raw.length() > 1) return raw.substring(0, 1).toUpperCase() + raw.substring(1);
        return raw;
    }

    private static String extractKnownMerchant(String msg) {
        String[][] known = {
            // Food
            {"swiggy","Swiggy"},{"zomato","Zomato"},{"dunzo","Dunzo"},
            {"blinkit","Blinkit"},{"zepto","Zepto"},{"bigbasket","BigBasket"},
            {"jiomart","JioMart"},{"grofers","Blinkit"},{"instamart","Swiggy Instamart"},
            // Shopping
            {"amazon","Amazon"},{"flipkart","Flipkart"},{"myntra","Myntra"},
            {"meesho","Meesho"},{"ajio","AJIO"},{"nykaa","Nykaa"},
            {"snapdeal","Snapdeal"},{"tatacliq","Tata CLiQ"},
            // Travel
            {"ola","Ola"},{"uber","Uber"},{"rapido","Rapido"},
            {"irctc","IRCTC"},{"makemytrip","MakeMyTrip"},{"goibibo","Goibibo"},
            {"redbus","RedBus"},{"yatra","Yatra"},{"ixigo","ixigo"},
            // Entertainment
            {"netflix","Netflix"},{"hotstar","Hotstar"},{"spotify","Spotify"},
            {"prime video","Amazon Prime"},{"zee5","ZEE5"},{"sonyliv","SonyLIV"},
            {"bookmyshow","BookMyShow"},{"pvr","PVR"},{"inox","INOX"},
            // Utilities / Recharge
            {"airtel","Airtel"},{"jio","Jio"},{"bsnl","BSNL"},{"vodafone","Vodafone"},{"vi ","Vi"},
            {"bescom","BESCOM"},{"tata power","Tata Power"},{"adani","Adani Electricity"},
            // Finance
            {"phonepe","PhonePe"},{"gpay","Google Pay"},{"paytm","Paytm"},
            {"cred","CRED"},{"mobikwik","MobiKwik"},{"freecharge","Freecharge"},
            // Other
            {"tata","Tata"},{"reliance","Reliance"},{"hdfc","HDFC"},
            {"icici","ICICI"},{"sbi","SBI"},
        };
        String lower = msg.toLowerCase();
        for (String[] pair : known) {
            if (lower.contains(pair[0])) return pair[1];
        }
        return "Unknown";
    }

    private static String extractReference(String msg) {
        Matcher m = REF_PATTERN.matcher(msg);
        return m.find() ? m.group(1) : null;
    }
}
