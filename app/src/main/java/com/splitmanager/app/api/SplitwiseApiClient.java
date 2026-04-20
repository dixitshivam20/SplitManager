package com.splitmanager.app.api;

import android.util.Log;

import com.splitmanager.app.model.SplitwiseGroup;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okio.ByteString;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class SplitwiseApiClient {

    private static final String TAG = "SplitManager.Splitwise";
    private static final String BASE_URL = "https://secure.splitwise.com/api/v3.0";

    private static final int    MAX_DESCRIPTION_LENGTH = 100;
    private static final double MAX_AMOUNT = 1_000_000.0;
    private static final double MIN_AMOUNT = 0.01;
    // Cap API responses at 512 KB to prevent OOM from unexpectedly large payloads.
    // Normal Splitwise responses are a few KB at most; this is a generous safety margin.
    private static final long   MAX_RESPONSE_BYTES = 512 * 1024L;

    private final OkHttpClient httpClient;
    private final Gson gson;

    // API key stored as char[] instead of String.
    // Strings are immutable and stay in the heap/string pool until GC.
    // char[] can be explicitly zeroed after use, minimising the window
    // during which a heap dump could expose the key.
    private final char[] apiKeyChars;

    private long currentUserId = -1;

    public SplitwiseApiClient(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("API key must not be empty");
        }
        // Store as char[] — never kept as a String field
        this.apiKeyChars = apiKey.toCharArray();

        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // Prevent duplicate expense on retry
            .addInterceptor(chain -> {
                // Reconstruct key string only for the header, then discard immediately
                String bearer = "Bearer " + new String(apiKeyChars);
                Request req = chain.request().newBuilder()
                    .addHeader("Authorization", bearer)
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .build();
                // bearer is now eligible for GC — we hold no reference to it
                return chain.proceed(req);
            })
            .build();
        this.gson = new Gson();
    }

    /**
     * Zero out the key material when this client is no longer needed.
     * Call this when the activity/service that owns the client is destroyed.
     */
    public void destroy() {
        Arrays.fill(apiKeyChars, '\0');
    }

    /**
     * Read a response body with a hard size cap to prevent OOM attacks.
     * rb.string() reads the full body regardless of size; a malicious/proxied server
     * returning megabytes of JSON would exhaust heap. We cap at MAX_RESPONSE_BYTES.
     *
     * @throws IOException if the body exceeds the size cap
     */
    private static String readBodySafe(ResponseBody rb) throws IOException {
        if (rb == null) return "";
        long contentLength = rb.contentLength();
        // contentLength() returns -1 when unknown — we still enforce the cap via buffer read
        if (contentLength > MAX_RESPONSE_BYTES) {
            throw new IOException("Response body too large (" + contentLength + " bytes)");
        }
        // Read up to MAX_RESPONSE_BYTES+1 bytes; if we get more, reject
        okio.BufferedSource source = rb.source();
        okio.Buffer buffer = new okio.Buffer();
        source.read(buffer, MAX_RESPONSE_BYTES + 1);
        if (buffer.size() > MAX_RESPONSE_BYTES) {
            throw new IOException("Response body exceeds size limit");
        }
        return buffer.readUtf8();
    }

    private static String sanitize(String input, int maxLen) {
        if (input == null) return "";
        String clean = input.replaceAll("[\\p{Cntrl}\"\\\\]", " ").trim();
        return clean.length() > maxLen ? clean.substring(0, maxLen) : clean;
    }

    /** Safely get a string from JsonObject — returns fallback if null or JsonNull */
    private static String safeString(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try { return obj.get(key).getAsString(); }
        catch (Exception e) { return fallback; }
    }

    private static void validateAmount(double amount) throws IOException {
        if (amount < MIN_AMOUNT || amount >= MAX_AMOUNT)
            throw new IOException("Amount out of valid range: " + amount);
        if (Double.isNaN(amount) || Double.isInfinite(amount))
            throw new IOException("Invalid amount value");
    }

    /** Returns null on success, or an error message string on failure */
    public String authenticateWithReason() {
        Response resp = null;
        try {
            Request req = new Request.Builder()
                .url(BASE_URL + "/get_current_user")
                .get()
                .build();
            resp = httpClient.newCall(req).execute();

            int code = resp.code();
            if (code == 401) return "Invalid API key (HTTP 401). Make sure you copied the \"API Key\" field, not the Consumer Key.";
            if (code == 403) return "Access forbidden (HTTP 403). Your key may not have the right permissions.";
            if (code >= 500) return "Splitwise server error (HTTP " + code + "). Try again in a moment.";
            if (!resp.isSuccessful()) return "Unexpected error (HTTP " + code + "). Please try again.";

            ResponseBody rb = resp.body();
            if (rb == null) return "Empty response from Splitwise. Check your internet connection.";
            String body;
            try { body = readBodySafe(rb); }
            catch (IOException e) { return "Response from Splitwise was too large or unreadable."; }

            JsonObject json = gson.fromJson(body, JsonObject.class);
            if (json == null) return "Could not parse Splitwise response. Try again.";
            JsonObject user = json.getAsJsonObject("user");
            if (user == null) return "Unexpected response format from Splitwise.";

            if (!user.has("id") || user.get("id").isJsonNull()) return "Could not read user ID from response.";
            currentUserId = user.get("id").getAsLong();
            Log.d(TAG, "Authenticated successfully");
            return null; // null = success
        } catch (java.net.UnknownHostException e) {
            return "No internet connection. Connect to Wi-Fi or mobile data and try again.";
        } catch (java.net.SocketTimeoutException e) {
            return "Connection timed out. Check your internet and try again.";
        } catch (Exception e) {
            Log.e(TAG, "Authentication failed");
            return "Connection failed. Check your internet and try again.";
        } finally {
            if (resp != null) resp.close();
        }
    }

    /** Backwards-compatible boolean version */
    public boolean authenticate() {
        return authenticateWithReason() == null;
    }

    public List<SplitwiseGroup> getGroups() throws IOException {
        Response resp = null;
        try {
            Request req = new Request.Builder()
                .url(BASE_URL + "/get_groups")
                .get()
                .build();
            resp = httpClient.newCall(req).execute();

            ResponseBody rb = resp.body();
            if (!resp.isSuccessful() || rb == null) {
                throw new IOException("Failed to fetch groups: " + resp.code());
            }
            String body = readBodySafe(rb);

            JsonObject json = gson.fromJson(body, JsonObject.class);
            if (json == null) return new ArrayList<>();
            JsonArray groupsArr = json.getAsJsonArray("groups");

            List<SplitwiseGroup> groups = new ArrayList<>();
            if (groupsArr == null) return groups;

            for (JsonElement elem : groupsArr) {
                if (!elem.isJsonObject()) continue;
                JsonObject g = elem.getAsJsonObject();

                // FIX: null-check id before using
                if (!g.has("id") || g.get("id").isJsonNull()) continue;
                if (g.get("id").getAsInt() == 0) continue;

                // FIX: null-safe group name
                String groupName = safeString(g, "name", "Unnamed Group");

                SplitwiseGroup group = new SplitwiseGroup();
                group.setId(g.get("id").getAsLong());
                group.setName(sanitize(groupName, 80));

                JsonArray membersArr = g.getAsJsonArray("members");
                List<SplitwiseGroup.Member> members = new ArrayList<>();
                if (membersArr != null) {
                    for (JsonElement mElem : membersArr) {
                        if (!mElem.isJsonObject()) continue;
                        JsonObject m = mElem.getAsJsonObject();

                        // FIX: null-check member id
                        if (!m.has("id") || m.get("id").isJsonNull()) continue;

                        SplitwiseGroup.Member member = new SplitwiseGroup.Member();
                        member.setId(m.get("id").getAsLong());

                        // FIX: null-safe first_name — fallback to "Member"
                        member.setFirstName(sanitize(safeString(m, "first_name", "Member"), 50));

                        if (m.has("last_name") && !m.get("last_name").isJsonNull())
                            member.setLastName(sanitize(m.get("last_name").getAsString(), 50));
                        if (m.has("email") && !m.get("email").isJsonNull())
                            member.setEmail(sanitize(m.get("email").getAsString(), 200));
                        members.add(member);
                    }
                }
                group.setMembers(members);
                groups.add(group);
            }
            return groups;
        } finally {
            if (resp != null) resp.close();
        }
    }

    public String createCustomSplit(long groupId, double totalAmount, String description,
                                     List<SplitwiseGroup.Member> members,
                                     List<Double> shares) throws IOException {
        if (currentUserId < 0) throw new IOException("Not authenticated");
        if (members.size() != shares.size()) throw new IOException("Members/shares count mismatch");
        validateAmount(totalAmount);

        String safeDesc = sanitize(description, MAX_DESCRIPTION_LENGTH);
        if (safeDesc.isEmpty()) safeDesc = "Expense";

        // Use integer cents to avoid floating point rounding errors
        // Splitwise rejects splits where owed_shares don't sum exactly to cost
        long totalCents = Math.round(totalAmount * 100);
        long[] shareCents = new long[shares.size()];
        long assignedCents = 0;
        for (int i = 0; i < shares.size(); i++) {
            shareCents[i] = Math.round(shares.get(i) * 100);
            assignedCents += shareCents[i];
        }
        // Absorb any rounding difference into the last member's share
        if (assignedCents != totalCents) {
            shareCents[shareCents.length - 1] += (totalCents - assignedCents);
        }

        // Check if current user (the payer) is already in the members list
        boolean currentUserIncluded = false;
        for (SplitwiseGroup.Member m : members) {
            if (m.getId() == currentUserId) { currentUserIncluded = true; break; }
        }

        JsonObject body = new JsonObject();
        body.addProperty("cost",          String.format("%.2f", totalAmount));
        body.addProperty("description",   safeDesc);
        body.addProperty("group_id",      groupId);
        body.addProperty("currency_code", "INR");

        // Build user entries from the included members list
        for (int i = 0; i < members.size(); i++) {
            SplitwiseGroup.Member member = members.get(i);
            body.addProperty("users__" + i + "__user_id",    member.getId());
            body.addProperty("users__" + i + "__owed_share", String.format("%.2f", shareCents[i] / 100.0));
            body.addProperty("users__" + i + "__paid_share",
                member.getId() == currentUserId
                    ? String.format("%.2f", totalAmount) : "0.00");
        }

        // CRITICAL: Splitwise requires paid_shares to sum to cost.
        // If the current user (payer) was excluded from the split (owed_share = 0),
        // they still must appear with paid_share = totalAmount and owed_share = 0.00.
        // Without this, all paid_shares = 0 and Splitwise rejects with:
        // "The total of everyone's paid shares (₹0.00) is different than the total cost"
        if (!currentUserIncluded) {
            int idx = members.size(); // next slot
            body.addProperty("users__" + idx + "__user_id",    currentUserId);
            body.addProperty("users__" + idx + "__owed_share", "0.00");
            body.addProperty("users__" + idx + "__paid_share", String.format("%.2f", totalAmount));
        }

        return postExpense(body);
    }

    private String postExpense(JsonObject body) throws IOException {
        RequestBody reqBody = RequestBody.create(
            body.toString(), MediaType.parse("application/json"));
        Request req = new Request.Builder()
            .url(BASE_URL + "/create_expense")
            .post(reqBody)
            .build();

        Response resp = null;
        try {
            resp = httpClient.newCall(req).execute();
            ResponseBody rb = resp.body();
            if (rb == null) throw new IOException("Empty response from Splitwise");
            String respBody = readBodySafe(rb);

            if (!resp.isSuccessful()) {
                Log.e(TAG, "Create expense failed: HTTP " + resp.code());
                throw new IOException("Failed to create expense: HTTP " + resp.code());
            }

            JsonObject respJson = gson.fromJson(respBody, JsonObject.class);
            if (respJson == null) throw new IOException("Could not parse Splitwise response");

            // Check for API-level errors (HTTP 200 but Splitwise rejected the data)
            // e.g. {"errors":{"base":["Owed shares do not add up to cost"]}}
            if (respJson.has("errors")) {
                JsonObject errors = respJson.getAsJsonObject("errors");
                if (errors != null && errors.size() > 0) {
                    // Extract first error message for a meaningful exception
                    String errMsg = "Splitwise rejected the split";
                    try {
                        JsonElement base = errors.get("base");
                        if (base != null && base.isJsonArray() && base.getAsJsonArray().size() > 0)
                            errMsg = base.getAsJsonArray().get(0).getAsString();
                    } catch (Exception ignored) {}
                    Log.e(TAG, "Splitwise API error: " + errMsg);
                    throw new IOException(errMsg);
                }
            }

            // Extract expense ID from response
            JsonArray expenses = respJson.getAsJsonArray("expenses");
            if (expenses != null && expenses.size() > 0) {
                JsonObject exp = expenses.get(0).getAsJsonObject();
                if (exp.has("id") && !exp.get("id").isJsonNull()) {
                    return exp.get("id").getAsString();
                }
            }

            // HTTP 200 with no errors and no ID — treat as success
            // (Splitwise occasionally returns expenses:[] for valid splits)
            Log.d(TAG, "Expense created — no ID returned by Splitwise (treated as success)");
            return "created";

        } finally {
            if (resp != null) resp.close();
        }
    }

    public long getCurrentUserId() { return currentUserId; }
}
