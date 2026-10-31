package com.myfamcall.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class CallPolicyTest {
    @Test public void oldIceGenerationIsNeverRelabeled(){
        java.util.Set<String> fragments=IceGeneration.ufrags("a=ice-ufrag:new\r\n");
        assertTrue(IceGeneration.matches("candidate:0 ufrag new",fragments,false));
        assertFalse(IceGeneration.matches("candidate:0 ufrag old",fragments,false));
        assertFalse(IceGeneration.matches("candidate:0",fragments,false));
    }
    @Test public void ringingExpiresAtBoundary() {
        assertTrue(CallPolicy.incomingFresh(54_999,10_000,100_000));
        assertFalse(CallPolicy.incomingFresh(55_000,10_000,100_000));
    }
    @Test public void noRestartDuringNegotiationOrCooldown() {
        assertFalse(CallPolicy.shouldRestart(true,true,1,10_000,0));
        assertFalse(CallPolicy.shouldRestart(true,false,1,4_999,0));
        assertTrue(CallPolicy.shouldRestart(true,false,1,5_000,0));
    }
    @Test public void restartBudgetIsBounded() {
        assertFalse(CallPolicy.shouldRestart(true,false,32,10_000,0));
    }
    @Test public void staleSdpIsRejected() {
        assertFalse(CallPolicy.validRemoteRevision(1,2,false));
        assertFalse(CallPolicy.validRemoteRevision(2,2,true));
        assertTrue(CallPolicy.validRemoteRevision(3,2,true));
    }
    @Test public void conservativeWeakNetworkFallback() {
        assertTrue(CallPolicy.weakNetwork(0.9,500_000));
        assertTrue(CallPolicy.weakNetwork(0.1,100_000));
        assertFalse(CallPolicy.weakNetwork(0.1,0));
    }
}
