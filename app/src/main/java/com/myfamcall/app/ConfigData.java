package com.myfamcall.app;

import java.net.URI;
import java.net.URISyntaxException;

/** Public Firebase routing identifiers only; never administrative credentials or passwords. */
public final class ConfigData {
    public final String apiKey, applicationId, projectId, databaseUrl, familyId;
    public ConfigData(String apiKey, String applicationId, String projectId, String databaseUrl, String familyId) {
        this.apiKey=clean(apiKey); this.applicationId=clean(applicationId); this.projectId=clean(projectId);
        this.familyId=clean(familyId);
        if(!this.apiKey.matches("AIza[A-Za-z0-9_-]{35}"))throw new IllegalArgumentException("Enter the Firebase Web API key from Project settings, not an admin key.");
        if(!this.applicationId.matches("1:[0-9]+:android:[A-Fa-f0-9]+"))throw new IllegalArgumentException("Enter the Android Firebase App ID for com.myfamcall.app.");
        if(!this.projectId.matches("[a-z][a-z0-9-]{4,28}[a-z0-9]"))throw new IllegalArgumentException("Enter the Firebase project ID, not its display name.");
        if(!this.familyId.matches("[A-Za-z0-9_-]{8,64}"))throw new IllegalArgumentException("Family ID must have 8–64 letters, numbers, underscores or hyphens.");
        try {
            URI uri=new URI(clean(databaseUrl)); String host=uri.getHost(),path=uri.getPath();
            if(!"https".equals(uri.getScheme()) || host==null || uri.getUserInfo()!=null || uri.getPort()!=-1 || uri.getQuery()!=null || uri.getFragment()!=null ||
                    !(path==null || path.isEmpty() || "/".equals(path)) ||
                    !(host.matches("[a-z0-9-]+\\.firebaseio\\.com") || host.matches("[a-z0-9-]+\\.[a-z0-9-]+\\.firebasedatabase\\.app")))
                throw new IllegalArgumentException("Use the HTTPS Realtime Database root URL from your Firebase console.");
            this.databaseUrl="https://"+host;
        } catch(URISyntaxException error) { throw new IllegalArgumentException("The database URL is not valid."); }
    }
    private static String clean(String value){return value==null?"":value.trim();}
}
