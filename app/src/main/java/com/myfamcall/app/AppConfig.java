package com.myfamcall.app;
import android.content.Context;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class AppConfig {
    public final String familyId;
    public final FirebaseApp firebase;
    private AppConfig(String familyId, FirebaseApp app) { this.familyId = familyId; firebase = app; }
    public static AppConfig load(Context context) throws Exception {
        JSONObject json;
        try (InputStream in = context.getAssets().open("firebase-config.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            json = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
        }
        String family = json.getString("familyId");
        if (!family.matches("[A-Za-z0-9_-]{8,64}")) throw new IllegalArgumentException("Invalid family ID");
        URI uri = new URI(json.getString("databaseUrl"));
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null ||
                !(host.endsWith(".firebasedatabase.app") || host.endsWith(".firebaseio.com")) || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Use your HTTPS Firebase database URL");
        FirebaseOptions options = new FirebaseOptions.Builder().setApiKey(json.getString("apiKey"))
                .setApplicationId(json.getString("applicationId")).setProjectId(json.getString("projectId"))
                .setDatabaseUrl(uri.toString()).build();
        FirebaseApp app = FirebaseApp.getApps(context).isEmpty() ? FirebaseApp.initializeApp(context, options) : FirebaseApp.getInstance();
        return new AppConfig(family, app);
    }
}
