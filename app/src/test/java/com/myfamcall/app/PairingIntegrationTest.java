package com.myfamcall.app;

import android.os.Looper;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Real Android Firebase SDK -> local Auth + Database emulators; no WebRTC/native media. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class PairingIntegrationTest {
    private static final String PROJECT="demo-my-fam-call-pairing";
    private static final String FAMILY="pairing_family";
    private static final String PASSWORD="EmulatorOnly-NotARealPassword!1";
    private static final long WAIT_MS=15_000;
    private int authPort, databasePort;
    private final List<FirebaseApp> apps=new ArrayList<>();
    private final List<FirebaseDatabase> databases=new ArrayList<>();
    private final List<SignalClient> clients=new ArrayList<>();
    private final List<PairingClient> pairingClients=new ArrayList<>();

    @Before public void before() throws Exception {
        Assume.assumeTrue("Run scripts/test-pairing-integration.sh for local emulator integration",Boolean.getBoolean("myfam.pairingIntegration"));
        authPort=Integer.getInteger("myfam.authPort",19099);
        databasePort=Integer.getInteger("myfam.databasePort",19000);
        http(authPort,"DELETE","/emulator/v1/projects/"+PROJECT+"/accounts",null,false);
        http(databasePort,"PUT","/.json?ns="+PROJECT,"null",true);
    }
    @After public void after() {
        for(PairingClient client:pairingClients) client.close();
        for(SignalClient client:clients) client.close();
        for(FirebaseDatabase db:databases) db.goOffline();
        for(FirebaseApp app:apps) app.delete();
    }
    private FirebaseApp app(String name) {
        FirebaseOptions options=new FirebaseOptions.Builder().setProjectId(PROJECT)
                .setApplicationId("1:123456789:android:integration")
                .setApiKey("AIzaSyEmulatorOnlyFakeKeyForLocalTests123")
                .setDatabaseUrl("https://"+PROJECT+".firebaseio.com").build();
        FirebaseApp app=FirebaseApp.initializeApp(RuntimeEnvironment.getApplication(),options,"integration-"+name);
        apps.add(app); return app;
    }
    private FirebaseAuth auth(FirebaseApp app,String email) throws Exception {
        FirebaseAuth auth=FirebaseAuth.getInstance(app);auth.useEmulator("127.0.0.1",authPort);
        await(auth.createUserWithEmailAndPassword(email,PASSWORD),"create emulator-only user");
        assertNotNull(auth.getCurrentUser());assertFalse(auth.getCurrentUser().isEmailVerified());
        return auth;
    }
    private FirebaseDatabase database(FirebaseApp app) {
        FirebaseDatabase db=FirebaseDatabase.getInstance(app);
        db.useEmulator("127.0.0.1",databasePort);databases.add(db);return db;
    }
    private void verifyEmail(FirebaseAuth auth) throws Exception {
        FirebaseUser user=auth.getCurrentUser();
        await(user.sendEmailVerification(),"request emulated verification email");
        JSONArray codes=new JSONObject(http(authPort,"GET","/emulator/v1/projects/"+PROJECT+"/oobCodes",null,false)).getJSONArray("oobCodes");
        String code=null;
        for(int i=0;i<codes.length();i++){
            JSONObject item=codes.getJSONObject(i);
            if(user.getEmail().equals(item.getString("email"))&&"VERIFY_EMAIL".equals(item.getString("requestType")))code=item.getString("oobCode");
        }
        assertNotNull("Auth emulator must expose its local verification code",code);
        await(auth.applyActionCode(code),"apply emulated verification code");
        await(user.reload(),"reload verified user");
        assertTrue(auth.getCurrentUser().isEmailVerified());
        assertEquals(Boolean.TRUE,await(auth.getCurrentUser().getIdToken(true),"refresh verified ID token").getClaims().get("email_verified"));
    }
    private static final class Trace implements SignalClient.Listener {
        SignalClient.Call call;
        int offer,answer,restart,candidates;
        final List<String> errors=new ArrayList<>();
        public void onCall(SignalClient.Call c){call=c;}
        public void onOffer(int revision,String sdp){offer=revision;}
        public void onAnswer(int revision,String sdp){answer=revision;}
        public void onCandidate(int revision,String mid,int line,String sdp){candidates++;}
        public void onRestartRequested(int revision){restart=revision;}
        public void onError(String error){errors.add(error);}
        boolean state(String id,String state){return call!=null&&id.equals(call.id)&&state.equals(call.state);}
    }
    private static final class Result implements SignalClient.Completion {
        boolean done; SignalClient.Call call; String error;
        public void complete(SignalClient.Call call,String error){this.call=call;this.error=error;done=true;}
        void succeeded() throws Exception {until(()->done,"signaling write completion");assertNull(error);}
    }
    private SignalClient client(FirebaseDatabase db,String uid,Trace trace) throws Exception {
        SignalClient client=new SignalClient(db,FAMILY,uid,trace);clients.add(client);
        until(client::isConnected,"database websocket connection");return client;
    }
    private static void peer(SignalClient client,String expectedUid) throws Exception {
        String[] result=new String[2];boolean[] done={false};
        client.loadPeer((uid,error)->{result[0]=uid;result[1]=error;done[0]=true;});
        until(()->done[0],"private family membership lookup");assertNull(result[1]);assertEquals(expectedUid,result[0]);
    }

    private static final class PairTrace implements PairingClient.Listener {
        Boolean owner,member; int inviteEvents;
        PairingClient.Invite invite;PairingClient.Pending pending;
        final List<String> errors=new ArrayList<>();
        public void role(boolean value){owner=value;}
        public void member(boolean value){member=value;}
        public void invite(PairingClient.Invite value){invite=value;inviteEvents++;}
        public void pending(PairingClient.Pending value){pending=value;}
        public void error(String value){errors.add(value);}
    }
    private static final class PairResult implements PairingClient.Completion {
        boolean done;String error;
        public void complete(String value){error=value;done=true;}
        void succeeded() throws Exception {until(()->done,"pairing write completion");assertNull(error);}
        void rejected() throws Exception {until(()->done,"pairing write rejection");assertNotNull("Expected pairing rejection",error);}
    }
    private PairingClient pairing(FirebaseDatabase db,String uid,PairTrace trace) throws Exception {
        PairingClient client=new PairingClient(db,FAMILY,uid,trace);pairingClients.add(client);client.start();
        until(()->client.isConnected()&&trace.owner!=null&&trace.member!=null&&trace.inviteEvents>0,"pairing bootstrap and initial metadata listener");return client;
    }
    private static void denied(Task<?> task,String description) throws Exception {
        until(task::isComplete,description);assertFalse(description,task.isSuccessful());
    }
    private static List<String> words() throws Exception {
        List<String> words=new ArrayList<>();
        try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(
                RuntimeEnvironment.getApplication().getResources().openRawResource(R.raw.pairing_words),StandardCharsets.UTF_8))){
            String line;while((line=reader.readLine())!=null)words.add(line.trim());
        }
        assertEquals(2048,words.size());return words;
    }
    private static String phrase(PairingClient.Pending pending,List<String> words){
        return PairingProof.phrase(pending.nonce,pending.uid,pending.challenge,words);
    }

    @Test public void anonymousPhonePairsOnlyAfterMatchingSnapshotApprovalThenCanSignal() throws Exception {
        FirebaseApp ownerApp=app("owner"),joinerApp=app("joiner");
        FirebaseAuth ownerAuth=auth(ownerApp,"owner@example.test");verifyEmail(ownerAuth);
        String ownerUid=ownerAuth.getCurrentUser().getUid();
        ownerAuth.signOut();await(ownerAuth.signInWithEmailAndPassword("owner@example.test",PASSWORD),"existing owner sign-in");
        assertEquals("Owner recovery must retain the original UID",ownerUid,ownerAuth.getCurrentUser().getUid());
        FirebaseAuth joinerAuth=FirebaseAuth.getInstance(joinerApp);joinerAuth.useEmulator("127.0.0.1",authPort);
        await(joinerAuth.signInAnonymously(),"anonymous joiner sign-in");
        assertTrue(joinerAuth.getCurrentUser().isAnonymous());
        String joinerUid=joinerAuth.getCurrentUser().getUid();
        await(joinerAuth.signInAnonymously(),"reuse anonymous identity");
        assertEquals("An existing anonymous identity must not rotate",joinerUid,joinerAuth.getCurrentUser().getUid());
        FirebaseDatabase ownerDb=database(ownerApp),joinerDb=database(joinerApp);
        JSONObject family=new JSONObject().put("ownerUid",ownerUid).put("members",new JSONObject().put(ownerUid,true));
        http(databasePort,"PUT","/families/"+FAMILY+".json?ns="+PROJECT,family.toString(),true);
        PairTrace ownerTrace=new PairTrace(),joinerTrace=new PairTrace();
        PairingClient owner=pairing(ownerDb,ownerUid,ownerTrace),joiner=pairing(joinerDb,joinerUid,joinerTrace);
        assertEquals(Boolean.TRUE,ownerTrace.owner);assertEquals(Boolean.TRUE,ownerTrace.member);
        assertEquals(Boolean.FALSE,joinerTrace.owner);assertEquals(Boolean.FALSE,joinerTrace.member);
        assertNull("Joiner subscribes before an invite exists",joinerTrace.invite);
        denied(joinerDb.getReference("families/"+FAMILY+"/members").get(),"unapproved anonymous phone cannot list members");
        denied(joinerDb.getReference("families/"+FAMILY+"/call").get(),"unapproved anonymous phone cannot read calls");
        PairResult absent=new PairResult();joiner.join("000000",absent);absent.rejected();
        PairResult created=new PairResult();owner.newInvite(created);created.succeeded();
        until(()->ownerTrace.invite!=null&&joinerTrace.invite!=null&&ownerTrace.invite.nonce.equals(joinerTrace.invite.nonce),"new invite arrives on previously empty metadata listener");
        assertEquals("Joiner must never receive the secret PIN in metadata","",joinerTrace.invite.code);
        String wrong="000000".equals(ownerTrace.invite.code)?"000001":"000000";
        PairResult badPin=new PairResult();joiner.join(wrong,badPin);badPin.rejected();
        assertNull(joinerTrace.pending);
        PairResult joined=new PairResult();joiner.join(ownerTrace.invite.code,joined);joined.succeeded();
        until(()->ownerTrace.pending!=null&&joinerTrace.pending!=null,"pending request on both phones");
        assertEquals(joinerUid,ownerTrace.pending.uid);
        assertEquals(Boolean.FALSE,joinerTrace.member);
        denied(joinerDb.getReference("families/"+FAMILY+"/call").get(),"a correct PIN alone must not grant call access");
        List<String> dictionary=words();
        String originalPhrase=phrase(joinerTrace.pending,dictionary);
        assertEquals(6,originalPhrase.split(" ").length);
        assertEquals(originalPhrase,phrase(ownerTrace.pending,dictionary));
        PairingClient.Pending staleSeen=ownerTrace.pending;

        joiner.close();PairTrace restoredTrace=new PairTrace();
        PairingClient restored=pairing(joinerDb,joinerUid,restoredTrace);
        until(()->restoredTrace.pending!=null,"requester's pending proof restored after closing/recreating client");
        assertEquals(joinerUid,joinerAuth.getCurrentUser().getUid());
        assertEquals(originalPhrase,phrase(restoredTrace.pending,dictionary));
        assertEquals(staleSeen.challenge,restoredTrace.pending.challenge);

        PairResult regenerated=new PairResult();owner.newInvite(regenerated);regenerated.succeeded();
        until(()->ownerTrace.pending==null&&restoredTrace.invite!=null&&!staleSeen.nonce.equals(restoredTrace.invite.nonce),"regenerated invite replaces metadata and old pending request");
        until(()->restoredTrace.pending==null,"joiner must discard stale proof when invitation nonce changes");
        PairResult staleApproval=new PairResult();owner.approve(staleSeen,staleApproval);staleApproval.rejected();
        assertEquals(Boolean.FALSE,restoredTrace.member);
        PairResult rejoined=new PairResult();restored.join(ownerTrace.invite.code,rejoined);rejoined.succeeded();
        until(()->ownerTrace.pending!=null&&restoredTrace.pending!=null,"fresh pairing request");
        assertNotEquals(staleSeen.nonce,ownerTrace.pending.nonce);
        assertNotEquals(originalPhrase,phrase(ownerTrace.pending,dictionary));
        assertEquals(phrase(ownerTrace.pending,dictionary),phrase(restoredTrace.pending,dictionary));
        PairingClient.Pending seen=ownerTrace.pending;
        PairResult staleAgainstFresh=new PairResult();owner.approve(staleSeen,staleAgainstFresh);staleAgainstFresh.rejected();
        assertEquals("An older snapshot must not approve the newly pending phone",Boolean.FALSE,restoredTrace.member);
        until(()->ownerTrace.pending!=null&&seen.challenge.equals(ownerTrace.pending.challenge),"rejected stale write restores the current owner snapshot");
        PairResult approved=new PairResult();owner.approve(seen,approved);approved.succeeded();
        until(()->Boolean.TRUE.equals(restoredTrace.member),"joiner's existing own-membership listener receives approval");
        JSONObject stored=new JSONObject(http(databasePort,"GET","/families/"+FAMILY+".json?ns="+PROJECT,null,true));
        assertEquals(ownerUid,stored.getString("ownerUid"));assertEquals(joinerUid,stored.getString("partnerUid"));
        assertEquals(2,stored.getJSONObject("members").length());
        JSONObject enrollment=stored.getJSONObject("enrollment");
        assertEquals("used",enrollment.getJSONObject("invite").getString("state"));
        assertFalse(enrollment.has("info"));assertFalse(enrollment.has("pending"));
        JSONObject proof=enrollment.getJSONObject("approval");
        assertEquals(seen.uid,proof.getString("uid"));assertEquals(seen.nonce,proof.getString("nonce"));assertEquals(seen.challenge,proof.getString("challenge"));
        assertTrue(proof.getLong("approvedAt")>0);
        PairResult reused=new PairResult();owner.newInvite(reused);reused.rejected();

        Trace callerTrace=new Trace(),calleeTrace=new Trace();
        SignalClient caller=client(ownerDb,ownerUid,callerTrace),callee=client(joinerDb,joinerUid,calleeTrace);
        peer(caller,joinerUid);peer(callee,ownerUid);caller.listen();callee.listen();
        Result begun=new Result();caller.begin(joinerUid,true,begun);begun.succeeded();String id=begun.call.id;
        until(()->callerTrace.state(id,"ringing")&&calleeTrace.state(id,"ringing"),"paired phones receive ring");
        Result accepted=new Result();callee.accept(id,accepted);accepted.succeeded();
        until(()->callerTrace.state(id,"accepted")&&calleeTrace.state(id,"accepted"),"paired anonymous callee accepts");
        String sdp="v=0\r\ns=Demo pairing integration\r\n";
        Result offered=new Result();caller.offer(id,1,sdp,offered);offered.succeeded();until(()->calleeTrace.offer==1,"paired offer delivery");
        Result answered=new Result();callee.answer(id,1,sdp,answered);answered.succeeded();until(()->callerTrace.answer==1,"paired answer delivery");
        caller.candidate(id,1,"0",0,"candidate:1 1 udp 1 192.0.2.1 12345 typ host");
        callee.candidate(id,1,"0",0,"candidate:2 1 udp 1 192.0.2.2 12345 typ host");
        until(()->callerTrace.candidates==1&&calleeTrace.candidates==1,"paired bidirectional ICE");
        Result ended=new Result();callee.end(id,ended);ended.succeeded();until(()->callerTrace.state(id,"ended"),"paired hangup");
        assertTrue("Owner pairing errors: "+ownerTrace.errors,ownerTrace.errors.isEmpty());
        assertTrue("Joiner pairing errors: "+joinerTrace.errors,joinerTrace.errors.isEmpty());
        assertTrue("Restored pairing errors: "+restoredTrace.errors,restoredTrace.errors.isEmpty());
        assertTrue("Caller errors: "+callerTrace.errors,callerTrace.errors.isEmpty());
        assertTrue("Callee errors: "+calleeTrace.errors,calleeTrace.errors.isEmpty());
    }
    private static <T> T await(Task<T> task,String description) throws Exception {
        until(task::isComplete,description);
        if(!task.isSuccessful())throw new AssertionError(description,task.getException());
        return task.getResult();
    }
    private static void until(BooleanSupplier condition,String description) throws Exception {
        long deadline=System.nanoTime()+WAIT_MS*1_000_000;
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(20);}
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Timed out: "+description,condition.getAsBoolean());
    }
    private static String http(int port,String method,String path,String body,boolean admin) throws Exception {
        // Fixed loopback prevents accidentally administering a real Firebase project.
        URL url=new URL("http://127.0.0.1:"+port+path);
        HttpURLConnection connection=(HttpURLConnection)url.openConnection(Proxy.NO_PROXY);
        connection.setRequestMethod(method);connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
        if(admin)connection.setRequestProperty("Authorization","Bearer owner");
        if(body!=null){connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));}
        int status=connection.getResponseCode();
        try(InputStream input=status<400?connection.getInputStream():connection.getErrorStream()){
            String response=input==null?"":new String(input.readAllBytes(),StandardCharsets.UTF_8);
            assertTrue("Local emulator HTTP "+method+" returned "+status+": "+response,status>=200&&status<300);
            return response;
        }finally{connection.disconnect();}
    }
}
