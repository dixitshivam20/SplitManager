package com.splitmanager.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.splitmanager.app.api.SplitwiseApiClient;
import com.splitmanager.app.databinding.ActivitySetupBinding;
import com.splitmanager.app.util.SecurePrefsHelper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SetupActivity extends AppCompatActivity {

    private ActivitySetupBinding binding;
    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySetupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                             WindowManager.LayoutParams.FLAG_SECURE);
        executor = Executors.newSingleThreadExecutor();

        String savedKey = "";
        try { savedKey = SecurePrefsHelper.getApiKey(this); } catch (Exception ignored) {}
        if (!savedKey.isEmpty()) {
            String masked = "••••••••••••" + savedKey.substring(Math.max(0, savedKey.length() - 6));
            binding.etApiKey.setHint(masked);
            binding.tvKeyStatus.setText("API key saved \u2713");
            binding.tvKeyStatus.setTextColor(0xFF2E7D32);
            binding.tvKeyStatus.setVisibility(View.VISIBLE);
            binding.btnDone.setVisibility(View.VISIBLE);
        }

        binding.btnOpenSplitwiseApps.setOnClickListener(v ->
            startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://secure.splitwise.com/oauth_clients")))
        );

        binding.btnTestAndSave.setOnClickListener(v -> testAndSaveKey());

        binding.btnDone.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
    }

    private void testAndSaveKey() {
        String key = binding.etApiKey.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "Please enter your API key", Toast.LENGTH_SHORT).show();
            return;
        }

        // Basic sanity check — Consumer Key looks very different from API Key
        // Consumer Keys are short alphanumeric (~20 chars), API Keys are longer (~40+ chars)
        if (key.length() < 20) {
            showError("That looks too short to be an API key. Make sure you copied the \"API Key\" field, not the Consumer Key or Consumer Secret.");
            return;
        }

        binding.btnTestAndSave.setEnabled(false);
        binding.btnTestAndSave.setText("Testing...");
        binding.progressSetup.setVisibility(View.VISIBLE);
        binding.tvKeyStatus.setVisibility(View.GONE);

        executor.submit(() -> {
            SplitwiseApiClient client = new SplitwiseApiClient(key);
            String errorReason = client.authenticateWithReason();

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                binding.progressSetup.setVisibility(View.GONE);
                binding.btnTestAndSave.setEnabled(true);
                binding.btnTestAndSave.setText("Test & Save");

                if (errorReason == null) {
                    // Success
                    SecurePrefsHelper.saveApiKey(this, key);
                    binding.tvKeyStatus.setText("Connected! API key saved securely \u2713");
                    binding.tvKeyStatus.setTextColor(0xFF2E7D32);
                    binding.tvKeyStatus.setVisibility(View.VISIBLE);
                    binding.btnDone.setVisibility(View.VISIBLE);
                    binding.etApiKey.setText("");
                    binding.etApiKey.setHint("Key saved — enter new key to replace");
                } else {
                    showError(errorReason);
                }
            });
        });
    }

    private void showError(String msg) {
        binding.tvKeyStatus.setText(msg);
        binding.tvKeyStatus.setTextColor(0xFFC62828);
        binding.tvKeyStatus.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) { executor.shutdown(); executor = null; }
    }
}
