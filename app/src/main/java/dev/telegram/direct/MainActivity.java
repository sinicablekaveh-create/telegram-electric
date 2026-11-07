package dev.telegram.direct;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.chaquo.python.Python;
import com.chaquo.python.PyObject;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText id, hash, phone, code, password, query, message;
    private TextView status, selected, proxyInfo;
    private LinearLayout results, queueResults;
    private String targetId = "", targetName = "";
    private boolean busy = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        ScrollView scroller = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,24,24,32);
        scroller.addView(root);
        setContentView(scroller);
        heading(root, "TELEGRAM DIRECT");
        label(root, "Private client | manual single-recipient sends");
        heading(root, "CONNECTION & PROXY");
        proxyInfo = label(root, ProxySettings.summary(this));
        button(root, "PROXY SETTINGS (SOCKS5 / MTProto)",
            () -> ProxySettings.show(this, config -> task("proxy", config)));
        id = field(root, "API ID", InputType.TYPE_CLASS_NUMBER);
        hash = field(root, "API Hash", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        phone = field(root, "Phone (+country code)", InputType.TYPE_CLASS_PHONE);
        button(root, "1. CONNECT / REQUEST CODE", () -> task("connect",
            getFilesDir().getAbsolutePath(), value(id), value(hash), value(phone), ProxySettings.load(this)));
        code = field(root, "Telegram verification code", InputType.TYPE_CLASS_NUMBER);
        button(root, "VERIFY CODE", () -> task("code", value(code)));
        password = field(root, "Two-step verification password",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button(root, "VERIFY PASSWORD", () -> task("password", value(password)));
        status = label(root, "Not connected.");
        heading(root, "SEARCH & CONVERSATIONS");
        query = field(root, "Search public groups, channels and users", InputType.TYPE_CLASS_TEXT);
        button(root, "SEARCH PUBLIC TELEGRAM", () -> task("search", value(query)));
        EditText localSearch = field(root, "Filter joined groups or contacts", InputType.TYPE_CLASS_TEXT);
        button(root, "FIND MY JOINED GROUPS", () -> task("dialogs", value(localSearch)));
        button(root, "FIND MY TELEGRAM CONTACTS", () -> task("contacts", value(localSearch)));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results);
        heading(root, "MANUAL MESSAGE");
        message = field(root, "Message text", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        message.setMinLines(2);
        selected = label(root, "No group selected.");
        button(root, "TEST: SEND TO SAVED MESSAGES", this::confirmSaved);
        button(root, "REVIEW & SEND TO SELECTED GROUP", this::confirmGroup);
        heading(root, "MANUAL REVIEW QUEUE");
        label(root, "Nothing is sent from the queue without reviewing and confirming one entry.");
        button(root, "ADD SELECTED GROUP MESSAGE TO QUEUE", this::confirmQueueAdd);
        button(root, "REFRESH REVIEW QUEUE", () -> task("queue_list"));
        button(root, "CLEAR SENT / CANCELLED ENTRIES", this::confirmQueueClear);
        queueResults = new LinearLayout(this);
        queueResults.setOrientation(LinearLayout.VERTICAL);
        root.addView(queueResults);
        button(root, "LOG OUT", () -> new AlertDialog.Builder(this)
            .setTitle("Log out of this app?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Log out", (d,w) -> task("logout")).show());
    }

    private String value(EditText field) { return field.getText().toString().trim(); }
    private TextView label(LinearLayout parent, String value) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(15); t.setPadding(0,8,0,8);
        parent.addView(t); return t;
    }
    private void heading(LinearLayout parent, String title) {
        TextView t = label(parent,title); t.setTextSize(20);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
    }
    private EditText field(LinearLayout parent,String hint,int type) {
        EditText t = new EditText(this); t.setHint(hint);
        t.setInputType(type); parent.addView(t); return t;
    }
    private void button(LinearLayout parent,String caption,Runnable run) {
        Button b = new Button(this); b.setText(caption);
        b.setOnClickListener(v -> run.run()); parent.addView(b);
    }

    private void confirmSaved() {
        String text = value(message);
        if (text.isEmpty()) { status.setText("Enter a message."); return; }
        new AlertDialog.Builder(this)
            .setTitle("Confirm Saved Messages")
            .setMessage(text)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Send", (d,w) -> task("saved",text))
            .show();
    }

    private void confirmGroup() {
        String text = value(message);
        if (targetId.isEmpty() || text.isEmpty()) {
            status.setText("Select a joined group and enter a message.");
            return;
        }
        final String groupId = targetId;
        new AlertDialog.Builder(this)
            .setTitle("Confirm one group message")
            .setMessage("Group: " + targetName + "\n\nMessage:\n" + text)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Send one", (d,w) -> task("send_group",groupId,text))
            .show();
    }

    private void confirmQueueAdd() {
        String text = value(message);
        if (targetId.isEmpty() || text.isEmpty()) {
            status.setText("Select a joined group and enter a message.");
            return;
        }
        String groupId = targetId;
        new AlertDialog.Builder(this)
            .setTitle("Add one message to review queue?")
            .setMessage("Group: " + targetName + "\n\nText:\n" + text
                        + "\n\nAdding does NOT send.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add to queue", (d,w) -> task("queue_add", groupId, text))
            .show();
    }

    private void confirmQueueClear() {
        new AlertDialog.Builder(this)
            .setTitle("Clear finished entries?")
            .setMessage("Only sent and cancelled entries are removed. Pending and uncertain entries remain.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear", (d,w) -> task("queue_clear"))
            .show();
    }

    private void renderQueue(JSONArray items) {
        queueResults.removeAllViews();
        label(queueResults, "Queue entries: " + items.length());
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String state = item.optString("state");
            String name = item.optString("group");
            String text = item.optString("text");
            String entryId = item.optString("id");
            String preview = text.length() > 140 ? text.substring(0, 140) + "..." : text;
            label(queueResults, "[" + state + "] " + name + "\n" + preview);
            if ("pending".equals(state)) {
                button(queueResults, "REVIEW / SEND ONE: " + name, () ->
                    new AlertDialog.Builder(this)
                        .setTitle("Confirm one queue delivery")
                        .setMessage("Joined group: " + name + "\n\nMessage:\n" + text
                          + "\n\nOnly this one entry will be attempted.")
                        .setNegativeButton("Keep pending", null)
                        .setPositiveButton("Send one", (d,w) -> task("queue_send", entryId))
                        .show());
                button(queueResults, "CANCEL PENDING: " + name, () ->
                    new AlertDialog.Builder(this)
                        .setTitle("Cancel pending entry?")
                        .setMessage("No message will be sent.")
                        .setNegativeButton("Keep", null)
                        .setPositiveButton("Cancel entry", (d,w) -> task("queue_cancel", entryId))
                        .show());
            } else if ("uncertain".equals(state)) {
                button(queueResults, "RESOLVE UNKNOWN DELIVERY: " + name, () ->
                    new AlertDialog.Builder(this)
                        .setTitle("Check Telegram group first")
                        .setMessage("A send was attempted but delivery is unknown. Check this group manually. "
                          + "This action never retries or sends a message.")
                        .setNegativeButton("Keep uncertain", null)
                        .setNeutralButton("Mark not delivered / cancel",
                            (d,w) -> task("queue_resolve", entryId, "cancelled"))
                        .setPositiveButton("Mark delivered",
                            (d,w) -> task("queue_resolve", entryId, "sent"))
                        .show());
            }
        }
    }

    private void task(String command, String... args) {
        if (busy) { status.setText("Another operation is running."); return; }
        busy = true;
        status.setText("Working...");
        worker.execute(() -> {
            JSONObject response;
            try {
                Object[] params = new Object[args.length + 1];
                params[0] = command;
                System.arraycopy(args,0,params,1,args.length);
                PyObject module = Python.getInstance().getModule("telegram_bridge");
                response = new JSONObject(module.callAttr("dispatch",params).toString());
            } catch(Exception exc) {
                response = new JSONObject();
                try {
                    response.put("status","error");
                    response.put("message","Application error: " + exc.getClass().getSimpleName());
                } catch(Exception ignored) {}
            }
            final JSONObject result = response;
            runOnUiThread(() -> {
                busy = false;
                String type = result.optString("status");
                status.setText(result.optString("message","Operation complete"));
                if ("proxy".equals(command) &&
                    ("proxy_saved".equals(type) || "proxy_applied".equals(type))) {
                    try {
                        ProxySettings.save(this, args[0]);
                        proxyInfo.setText(ProxySettings.summary(this));
                    } catch (Exception ignored) {
                        status.setText("Proxy applied, but could not save securely.");
                    }
                }
                JSONArray items = result.optJSONArray("items");
                if(items != null) {
                    if ("queue_list".equals(command)) renderQueue(items);
                    else render(items);
                }
                if("authorized".equals(type)) { code.setText(""); password.setText(""); }
                if("logged_out".equals(type)) {
                    targetId = ""; targetName = ""; selected.setText("No group selected.");
                    results.removeAllViews();
                    queueResults.removeAllViews();
                }
                if("sent".equals(type) && "send_group".equals(command)) {
                    targetId = ""; targetName = ""; selected.setText("No group selected.");
                }
                if(("error".equals(type) && "send_group".equals(command)) ||
                    ("uncertain".equals(type) && "queue_send".equals(command))) {
                    new AlertDialog.Builder(this)
                        .setTitle("Check Telegram before retrying")
                        .setMessage("A send may have succeeded. Check Telegram before retrying to avoid duplicates.")
                        .setPositiveButton("OK",null).show();
                }
            });
        });
    }

    private void render(JSONArray items) {
        results.removeAllViews();
        label(results, "Results: " + items.length());
        for(int i=0;i<items.length();i++) {
            JSONObject item = items.optJSONObject(i);
            if(item==null) continue;
            String title = item.optString("title");
            String uid = item.optString("id");
            String handle = item.optString("username");
            label(results, item.optString("kind") + ": " + title
                 + (handle.isEmpty() ? "" : " @" + handle));
            if(item.optBoolean("selectable")) {
                button(results, "SELECT GROUP: " + title, () -> {
                    targetId = uid; targetName = title;
                    selected.setText("Selected joined group: " + title);
                });
            }
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }
}
