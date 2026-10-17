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
    private TextView status, selected;
    private LinearLayout results;
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
        id = field(root, "API ID", InputType.TYPE_CLASS_NUMBER);
        hash = field(root, "API Hash", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        phone = field(root, "Phone (+country code)", InputType.TYPE_CLASS_PHONE);
        button(root, "1. CONNECT / REQUEST CODE", () -> task("connect",
            getFilesDir().getAbsolutePath(), value(id), value(hash), value(phone)));
        code = field(root, "Telegram verification code", InputType.TYPE_CLASS_NUMBER);
        button(root, "VERIFY CODE", () -> task("code", value(code)));
        password = field(root, "Two-step verification password",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button(root, "VERIFY PASSWORD", () -> task("password", value(password)));
        status = label(root, "Not connected.");
        heading(root, "SEARCH & CONVERSATIONS");
        query = field(root, "Search public Telegram", InputType.TYPE_CLASS_TEXT);
        button(root, "SEARCH PUBLIC", () -> task("search", value(query)));
        button(root, "MY GROUPS & CHATS", () -> task("dialogs"));
        button(root, "MY CONTACTS", () -> task("contacts"));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results);
        heading(root, "MANUAL MESSAGE");
        message = field(root, "Message text", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        message.setMinLines(2);
        selected = label(root, "No group selected.");
        button(root, "TEST: SEND TO SAVED MESSAGES", this::confirmSaved);
        button(root, "REVIEW & SEND TO SELECTED GROUP", this::confirmGroup);
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
                JSONArray items = result.optJSONArray("items");
                if(items != null) render(items);
                if("authorized".equals(type)) { code.setText(""); password.setText(""); }
                if("logged_out".equals(type)) {
                    targetId = ""; targetName = ""; selected.setText("No group selected.");
                    results.removeAllViews();
                }
                if("sent".equals(type) && "send_group".equals(command)) {
                    targetId = ""; targetName = ""; selected.setText("No group selected.");
                }
                if("error".equals(type) && "send_group".equals(command)) {
                    new AlertDialog.Builder(this)
                        .setTitle("Check Telegram before retrying")
                        .setMessage("The send result may be uncertain. Check the group for a duplicate first.")
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
