package com.myfamcall.app;

import android.os.Looper;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.FirebaseDatabase;
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
public class SignalingIntegrationTest {
    private static final String PROJECT="demo-my-fam-call-integration";
    private static final String FAMILY="integration_family";
    private static final String PASSWORD="EmulatorOnly-NotARealPassword!1";
    private static final long WAIT_MS=15_000;
    private int authPort, databasePort;
    private final List<FirebaseApp> apps=new ArrayList<>();
    private final List<FirebaseDatabase> databases=new ArrayList<>();
    private final List<SignalClient> clients=new ArrayList<>();

    @Before public void before() throws Exception {
        Assume.assumeTrue("Run scripts/test-android-integration.sh for local emulator integration",Boolean.getBoolean("myfam.integration"));
        authPort=Integer.getInteger("myfam.authPort",19099);
        databasePort=Integer.getInteger("myfam.databasePort",19000);
        http(authPort,"DELETE","/emulator/v1/projects/"+PROJECT+"/accounts",null,false);
        http(databasePort,"PUT","/.json?ns="+PROJECT,"null",true);
    }
    @After public void after() {
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

    @Test public void approvedAndroidAccountsCanSignalAndStaleCancellationCannotEndNextCall() throws Exception {
        FirebaseApp firstApp=app("caller"),secondApp=app("callee");
        FirebaseAuth firstAuth=auth(firstApp,"caller@example.test"),secondAuth=auth(secondApp,"callee@example.test");
        String firstUid=firstAuth.getCurrentUser().getUid(),secondUid=secondAuth.getCurrentUser().getUid();
        FirebaseDatabase firstDb=database(firstApp),secondDb=database(secondApp);
        JSONObject members=new JSONObject().put(firstUid,true).put(secondUid,true);
        http(databasePort,"PUT","/families/"+FAMILY+"/members.json?ns="+PROJECT,members.toString(),true);
        Task<?> legacyOnlyRead=firstDb.getReference("families/"+FAMILY+"/members").get();
        until(legacyOnlyRead::isComplete,"legacy membership without roles is rejected");
        assertFalse("Legacy member flags alone must not grant family access",legacyOnlyRead.isSuccessful());
        http(databasePort,"PUT","/families/"+FAMILY+"/ownerUid.json?ns="+PROJECT,JSONObject.quote(firstUid),true);
        http(databasePort,"PUT","/families/"+FAMILY+"/partnerUid.json?ns="+PROJECT,JSONObject.quote(secondUid),true);
        // A fresh sign-in also demonstrates account credentials work independently of signup state.
        firstAuth.signOut();await(firstAuth.signInWithEmailAndPassword("caller@example.test",PASSWORD),"owner recovery sign-in");
        await(firstAuth.getCurrentUser().getIdToken(true),"signed-in token refresh");
        Trace callerTrace=new Trace(),calleeTrace=new Trace();
        SignalClient caller=client(firstDb,firstUid,callerTrace),callee=client(secondDb,secondUid,calleeTrace);
        peer(caller,secondUid);peer(callee,firstUid);caller.listen();callee.listen();

        Result begun=new Result();caller.begin(secondUid,true,begun);begun.succeeded();
        String id=begun.call.id;
        until(()->callerTrace.state(id,"ringing")&&calleeTrace.state(id,"ringing"),"incoming ring on both clients");
        Result accepted=new Result();callee.accept(id,accepted);accepted.succeeded();
        until(()->callerTrace.state(id,"accepted")&&calleeTrace.state(id,"accepted"),"acceptance on both clients");
        String offer="v=0\r\ns=Local emulator signaling test\r\n";
        Result offered=new Result();caller.offer(id,1,offer,offered);offered.succeeded();
        until(()->calleeTrace.offer==1,"peer offer delivery");assertEquals("No self offer callback",0,callerTrace.offer);
        Result answered=new Result();callee.answer(id,1,offer,answered);answered.succeeded();
        until(()->callerTrace.answer==1,"peer answer delivery");assertEquals("No self answer callback",0,calleeTrace.answer);
        caller.candidate(id,1,"0",0,"candidate:1 1 udp 1 192.0.2.1 12345 typ host");
        callee.candidate(id,1,"0",0,"candidate:2 1 udp 1 192.0.2.2 12345 typ host");
        until(()->callerTrace.candidates==1&&calleeTrace.candidates==1,"bidirectional peer ICE delivery");
        callee.requestRestart(id,1);until(()->callerTrace.restart==1,"callee restart request");
        Result restarted=new Result();caller.offer(id,2,offer,restarted);restarted.succeeded();
        until(()->calleeTrace.offer==2,"second offer revision");
        Result reanswered=new Result();callee.answer(id,2,offer,reanswered);reanswered.succeeded();
        until(()->callerTrace.answer==2,"second answer revision");
        Result ended=new Result();caller.end(id,ended);ended.succeeded();
        until(()->calleeTrace.state(id,"ended"),"remote hangup");

        Result replacement=new Result();caller.begin(secondUid,false,replacement);replacement.succeeded();
        String newId=replacement.call.id;assertNotEquals(id,newId);
        until(()->callerTrace.state(newId,"ringing")&&calleeTrace.state(newId,"ringing"),"replacement call");
        Result stale=new Result();caller.end(id,stale);until(()->stale.done,"stale cancel rejection");
        assertNotNull("Old call cancellation must be rejected",stale.error);
        assertTrue(calleeTrace.state(newId,"ringing"));
        Result cancelled=new Result();caller.end(newId,cancelled);cancelled.succeeded();
        until(()->calleeTrace.state(newId,"ended"),"cancelled ring");
        Result lateAccept=new Result();callee.accept(newId,lateAccept);until(()->lateAccept.done,"late acceptance rejection");
        assertNotNull("Cancelled call cannot be accepted",lateAccept.error);
        assertTrue(calleeTrace.state(newId,"ended"));
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
