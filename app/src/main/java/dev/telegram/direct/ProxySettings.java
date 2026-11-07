package dev.telegram.direct;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/**
 * SOCKS5 / MTProto configuration for the Telegram client (not a device-wide VPN).
 * The proxy's credentials and secret are encrypted by Android Keystore at rest.
 */
public final class ProxySettings {
    private static final String STORE = "telegram_proxy_encrypted";
    private static final String KEY = "telegram_direct_proxy_aes_v1";
    private static final String DIRECT = "{\"type\":\"direct\"}";

    private ProxySettings() {}

    public static String load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        String iv = preferences.getString("iv", "");
        String payload = preferences.getString("data", "");
        if (iv.isEmpty() || payload.isEmpty()) return DIRECT;
        try {
            KeyStore keystore = KeyStore.getInstance("AndroidKeyStore");
            keystore.load(null);
            SecretKey key = (SecretKey) keystore.getKey(KEY, null);
            if (key == null) return DIRECT;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP)),
                              StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return DIRECT;
        }
    }

    public static void save(Context context, String json) throws Exception {
        KeyStore keystore = KeyStore.getInstance("AndroidKeyStore");
        keystore.load(null);
        SecretKey key = (SecretKey) keystore.getKey(KEY, null);
        if (key == null) {
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            key = generator.generateKey();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] bytes = cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .apply();
    }

    public static String summary(Context context) {
        try {
            JSONObject data = new JSONObject(load(context));
            String type = data.optString("type", "direct");
            if ("direct".equals(type)) return "Proxy: Off (direct)";
            if (!"socks5".equals(type) && !"mtproto".equals(type)) return "Proxy: Not configured";
            return "Proxy: " + ("socks5".equals(type) ? "SOCKS5" : "MTProto") +
                   " — " + data.optString("server") + ":" + data.optInt("port");
        } catch (Exception ignored) {
            return "Proxy: Not configured";
        }
    }

    private static EditText entry(Activity activity, LinearLayout layout, String hint, int inputType) {
        EditText text = new EditText(activity);
        text.setHint(hint);
        text.setInputType(inputType);
        text.setSingleLine(true);
        layout.addView(text);
        return text;
    }

    private static void importLink(Activity activity, String link, Spinner type,
                                   EditText server, EditText port, EditText username,
                                   EditText password, EditText secret) {
        try {
            Uri uri = Uri.parse(link.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();
            String kind;
            if ("tg".equalsIgnoreCase(scheme)) {
                kind = host;
            } else if ("https".equalsIgnoreCase(scheme) &&
                    ("t.me".equalsIgnoreCase(host) || "telegram.me".equalsIgnoreCase(host))) {
                kind = path != null && path.length() > 1 ? path.substring(1) : "";
            } else {
                throw new IllegalArgumentException();
            }
            if (!"proxy".equalsIgnoreCase(kind) && !"socks".equalsIgnoreCase(kind)) {
                throw new IllegalArgumentException();
            }
            String address = uri.getQueryParameter("server");
            String portValue = uri.getQueryParameter("port");
            if (address == null || address.isEmpty() || portValue == null) {
                throw new IllegalArgumentException();
            }
            type.setSelection("proxy".equalsIgnoreCase(kind) ? 2 : 1);
            server.setText(address);
            port.setText(portValue);
            username.setText(uri.getQueryParameter("user") == null ? "" : uri.getQueryParameter("user"));
            password.setText(uri.getQueryParameter("pass") == null ? "" : uri.getQueryParameter("pass"));
            secret.setText(uri.getQueryParameter("secret") == null ? "" : uri.getQueryParameter("secret"));
            Toast.makeText(activity, "Proxy link imported. Tap Apply to connect.", Toast.LENGTH_LONG).show();
        } catch (Exception ex) {
            Toast.makeText(activity, "Invalid Telegram proxy link.", Toast.LENGTH_LONG).show();
        }
    }

    private static String collectConfig(Spinner type, EditText server, EditText port,
                                        EditText username, EditText password,
                                        EditText secret) throws Exception {
        JSONObject config = new JSONObject();
        int option = type.getSelectedItemPosition();
        config.put("type", option == 1 ? "socks5" : option == 2 ? "mtproto" : "direct");
        if (option != 0) {
            String address = server.getText().toString().trim();
            String number = port.getText().toString().trim();
            if (address.isEmpty() || number.isEmpty() || address.length() > 253 ||
                    address.matches(".*[\\\\s/@#?].*"))
                throw new IllegalArgumentException("Check proxy server and port.");
            int parsedPort = Integer.parseInt(number);
            if (parsedPort < 1 || parsedPort > 65535)
                throw new IllegalArgumentException("Port must be between 1 and 65535.");
            config.put("server", address);
            config.put("port", parsedPort);
            if (option == 1) {
                config.put("username", username.getText().toString());
                config.put("password", password.getText().toString());
            } else {
                String value = secret.getText().toString().trim();
                if (!value.matches("(?i)([0-9a-f]{32}|dd[0-9a-f]{32}|ee[0-9a-f]{32}([0-9a-f]{2})*)"))
                    throw new IllegalArgumentException("Enter a valid MTProto secret (hex).");
                config.put("secret", value);
            }
        }
        return config.toString();
    }

    private static void showInputError(Activity activity, Exception exc) {
        String text = exc instanceof NumberFormatException ?
                "Enter a valid numeric port." : exc.getMessage();
        Toast.makeText(activity, text == null ? "Invalid proxy settings." : text,
                Toast.LENGTH_LONG).show();
    }

    public static void show(Activity activity, Consumer<String> onApply, Consumer<String> onTest) {
        JSONObject stored;
        try {
            stored = new JSONObject(load(activity));
        } catch (Exception ignored) {
            stored = new JSONObject();
        }
        ScrollView scroll = new ScrollView(activity);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        scroll.addView(layout);

        TextView explain = new TextView(activity);
        explain.setText("Telegram connection only. A proxy does not change other apps' traffic.");
        layout.addView(explain);
        Spinner type = new Spinner(activity);
        type.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Off / Direct", "SOCKS5", "MTProto"}));
        layout.addView(type);
        EditText server = entry(activity, layout, "Server / IP", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText port = entry(activity, layout, "Port (1-65535)", InputType.TYPE_CLASS_NUMBER);
        EditText username = entry(activity, layout, "SOCKS5 username (optional)", InputType.TYPE_CLASS_TEXT);
        EditText password = entry(activity, layout, "SOCKS5 password (optional)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText secret = entry(activity, layout, "MTProto secret (hex)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText link = entry(activity, layout, "tg://proxy or t.me/proxy or tg://socks link",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        Button importButton = new Button(activity);
        importButton.setText("IMPORT PROXY LINK");
        importButton.setOnClickListener(v -> importLink(activity, link.getText().toString(),
                type, server, port, username, password, secret));
        layout.addView(importButton);

        String initialType = stored.optString("type", "direct");
        type.setSelection("socks5".equals(initialType) ? 1 : ("mtproto".equals(initialType) ? 2 : 0));
        server.setText(stored.optString("server", ""));
        if (stored.has("port")) port.setText(String.valueOf(stored.optInt("port")));
        username.setText(stored.optString("username", ""));
        password.setText(stored.optString("password", ""));
        secret.setText(stored.optString("secret", ""));
        Runnable visibility = () -> {
            int index = type.getSelectedItemPosition();
            server.setVisibility(index == 0 ? View.GONE : View.VISIBLE);
            port.setVisibility(index == 0 ? View.GONE : View.VISIBLE);
            username.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
            password.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
            secret.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        };
        type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                visibility.run();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        visibility.run();

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("PROXY SETTINGS")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("TEST CONNECTION", null)
                .setPositiveButton("APPLY", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    onApply.accept(collectConfig(type, server, port, username, password, secret));
                    dialog.dismiss();
                } catch (Exception exc) {
                    showInputError(activity, exc);
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                try {
                    onTest.accept(collectConfig(type, server, port, username, password, secret));
                    dialog.dismiss();
                } catch (Exception exc) {
                    showInputError(activity, exc);
                }
            });
        });
        dialog.show();
    }
}
