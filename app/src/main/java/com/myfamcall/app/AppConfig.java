package com.myfamcall.app;

import android.content.Context;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;

/** Imports public configuration at runtime; no APK rebuild or admin secret needed. */
public final class AppConfig {
    private static final String PREFERENCES="private_family_setup", SAVED="configuration";
    static final int MAX_IMPORT_BYTES=32_768;
    public final String familyId;
    public final ConfigData data;
    public final FirebaseApp firebase;
    private AppConfig(ConfigData data,FirebaseApp app){this.data=data;familyId=data.familyId;firebase=app;}
    public static AppConfig load(Context context) throws Exception {
        String saved=context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE).getString(SAVED,null);
        if(saved!=null)return initialize(context,parse(saved,""));
        try(InputStream in=context.getAssets().open("firebase-config.json")) { return initialize(context,readImport(in,"")); }
        catch(FileNotFoundException missing){return null;}
    }
    public static AppConfig save(Context context,ConfigData data) throws Exception {
        AppConfig config=initialize(context,data);
        if(!context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE).edit().putString(SAVED,toJson(data).toString()).commit())
            throw new IllegalStateException("Could not save setup on this phone. Try again.");
        return config;
    }
    public static void clear(Context context){context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE).edit().clear().apply();}
    public static ConfigData readImport(InputStream in,String familyOverride) throws Exception {
        if(in==null)throw new IllegalArgumentException("The selected file could not be opened.");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
        while((count=in.read(buffer))!=-1){if(bytes.size()+count>MAX_IMPORT_BYTES)throw new IllegalArgumentException("Choose the small Firebase client configuration file, not a backup or credential file.");bytes.write(buffer,0,count);}
        return parse(bytes.toString(StandardCharsets.UTF_8.name()),familyOverride);
    }
    static ConfigData parse(String input,String familyOverride) throws Exception {
        if(input.length()>MAX_IMPORT_BYTES)throw new IllegalArgumentException("Configuration is too large.");
        JSONObject json=new JSONObject(input);
        if(json.has("private_key") || json.has("private_key_id") || json.has("client_email") || "service_account".equals(json.optString("type")))
            throw new IllegalArgumentException("Do not import service-account/admin credentials. Choose the Android google-services.json file instead.");
        String family=familyOverride==null||familyOverride.trim().isEmpty()?json.optString("familyId"):familyOverride;
        if(json.has("project_info")){
            JSONObject project=json.getJSONObject("project_info");JSONArray clients=json.getJSONArray("client");JSONObject selected=null;
            for(int i=0;i<clients.length();i++){
                JSONObject client=clients.getJSONObject(i),info=client.getJSONObject("client_info");
                if("com.myfamcall.app".equals(info.getJSONObject("android_client_info").optString("package_name"))){
                    if(selected!=null)throw new IllegalArgumentException("The configuration has duplicate Android entries for this app.");selected=client;
                }
            }
            if(selected==null)throw new IllegalArgumentException("Register Android package com.myfamcall.app in Firebase, then download its google-services.json.");
            if(!project.has("firebase_url"))throw new IllegalArgumentException("Create the Realtime Database in locked mode first, then download google-services.json again.");
            return new ConfigData(selected.getJSONArray("api_key").getJSONObject(0).getString("current_key"),
                    selected.getJSONObject("client_info").getString("mobilesdk_app_id"),project.getString("project_id"),project.getString("firebase_url"),family);
        }
        if(json.has("password") || json.has("token"))throw new IllegalArgumentException("Setup must not contain a password or token.");
        return new ConfigData(json.optString("apiKey"),json.optString("applicationId"),json.optString("projectId"),json.optString("databaseUrl"),family);
    }
    static JSONObject toJson(ConfigData data) throws Exception {
        JSONObject json=new JSONObject();json.put("apiKey",data.apiKey);json.put("applicationId",data.applicationId);
        json.put("projectId",data.projectId);json.put("databaseUrl",data.databaseUrl);json.put("familyId",data.familyId);return json;
    }
    private static AppConfig initialize(Context context,ConfigData data){
        FirebaseOptions options=new FirebaseOptions.Builder().setApiKey(data.apiKey).setApplicationId(data.applicationId)
                .setProjectId(data.projectId).setDatabaseUrl(data.databaseUrl).build();
        FirebaseApp app;
        try{app=FirebaseApp.getInstance();if(!app.getOptions().equals(options)){app.delete();app=FirebaseApp.initializeApp(context,options);}}
        catch(IllegalStateException absent){app=FirebaseApp.initializeApp(context,options);}
        return new AppConfig(data,app);
    }
}
