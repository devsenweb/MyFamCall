package com.myfamcall.app;
import java.util.HashSet;
import java.util.Set;
/** Never label a candidate from old ICE credentials as the current restart. */
public final class IceGeneration {
    private IceGeneration() {}
    public static Set<String> ufrags(String sdp) {
        Set<String> result=new HashSet<>();
        for(String line:sdp.split("\\r?\\n")) if(line.startsWith("a=ice-ufrag:")) result.add(line.substring(12).trim());
        return result;
    }
    public static boolean matches(String candidate, Set<String> current, boolean firstNegotiation) {
        String[] parts=candidate.trim().split("\\s+");
        for(int i=0;i+1<parts.length;i++) if("ufrag".equals(parts[i])) return current.contains(parts[i+1]);
        // Legacy candidates lacking a ufrag are unambiguous only before any restart.
        return firstNegotiation && !current.isEmpty();
    }
}
