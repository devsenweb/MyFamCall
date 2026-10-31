package com.myfamcall.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.util.Patterns;
import java.io.InputStream;
import android.graphics.Color;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.FirebaseDatabase;
import org.webrtc.SurfaceViewRenderer;
import java.util.ArrayList;
import java.util.List;

/** Deliberately foreground-only first milestone: leaving the app ends a call. */
public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private AppConfig config;
    private PairingClient pairing;
    private PairingPanel pairingPanel;
    private boolean ownerDevice,familyApproved,ownerRecovery;
    private String pendingJoinCode;
    private SetupPanel setup;
    private boolean editingSetup,authBusy;
    private int authEpoch,importEpoch;
    private final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    private String importFamily="";
    private FirebaseAuth auth;
    private SignalClient signal;
    private SignalClient.Call call;
    private String peerUid, ownUid, localConsentId;
    private final java.util.LinkedHashSet<String> dismissedIds=new java.util.LinkedHashSet<>();
    private RtcEngine rtc;
    private AudioRoute route;
    private Ringtone ring;
    private boolean foreground, outgoingPending, cancelledPending, mediaVideo, muted, negotiating;
    private boolean connectedMedia, sdpPublished, recoveryPending;
    private long lastRestart = -CallPolicy.RESTART_COOLDOWN_MS;
    private int revision, epoch, operationEpoch;
    private Runnable permissionAction;
    private ConnectivityManager connectivity;
    private Network currentNetwork;
    private LinearLayout login, controls, home, incoming, videos;
    private TextView status, networkStatus, accountInfo, projectInfo;
    private EditText email, password;
    private Button mute, camera, loginButton, audioCall, videoCall,changeConnection;
    private SurfaceViewRenderer localView, remoteView;
    private final List<Ice> localIce = new ArrayList<>();
    private final List<Ice> futureIce = new ArrayList<>();
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { main.post(() -> {
            if (!foreground) return;
            boolean changed = currentNetwork != null && !currentNetwork.equals(network);
            currentNetwork = network; networkStatus.setText("Internet available • direct connection only");
            if (changed && rtc != null) recover("Network changed. Reconnecting…");
        }); }
        @Override public void onLost(Network network) { main.post(() -> {
            if (network.equals(currentNetwork)) {
                currentNetwork = null; networkStatus.setText("Offline • waiting for a network");
                if (rtc != null) recover("Connection interrupted. Trying to recover…");
            }
        }); }
    };
    private static final class Ice {
        final int revision, line; final String mid, sdp;
        Ice(int revision, String mid, int line, String sdp) { this.revision=revision; this.mid=mid; this.line=line; this.sdp=sdp; }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); buildUi();
        try { config = AppConfig.load(this); if(config!=null)auth = FirebaseAuth.getInstance(config.firebase); }
        catch (Exception e) { setup.showError("Saved setup could not be read. Import the family configuration again."); }
        status.setText(config==null?"Connect once, then call your family.":"One-time family setup. Your phone is remembered afterward.");
        connectivity = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        refresh();
    }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(36),dp(20),dp(24)); root.setBackgroundColor(Color.rgb(246,243,235));
        scroll.addView(root); setContentView(scroll);
        root.setOnApplyWindowInsetsListener((v, insets) -> { v.setPadding(dp(20), insets.getSystemWindowInsetTop()+dp(20),dp(20),insets.getSystemWindowInsetBottom()+dp(20)); return insets; });
        TextView title = text("My Fam Call", 30); title.setTextColor(Color.rgb(30,80,68)); root.addView(title);
        root.addView(text("Just your family. No feeds.",16));
        status = text("Welcome home",20); status.setPadding(0,dp(24),0,dp(12)); root.addView(status);
        networkStatus = text("Checking network…",13); root.addView(networkStatus);
        TextView notice = text("Prototype: both phones must keep this app open. Leaving the app or locking the phone ends the call. Some networks cannot connect without a relay; no paid fallback is used.",13);
        notice.setPadding(0,dp(12),0,dp(16)); root.addView(notice);
        setup=new SetupPanel(this,new SetupPanel.Listener(){
            public void save(ConfigData data){configure(data);}
            public void importFile(String family){importFamily=family;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("application/json");intent.addCategory(Intent.CATEGORY_OPENABLE);intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","application/octet-stream"});try{startActivityForResult(intent,41);}catch(android.content.ActivityNotFoundException missing){setup.showError("No file picker available. Enter connection details instead.");}}
            public void cancel(){importEpoch++;editingSetup=false;refresh();}
        });root.addView(setup);
        pairingPanel=new PairingPanel(this,new PairingPanel.Actions(){
            public void join(String code){joinFamily(code);}
            public void create(){if(pairing!=null){pairingPanel.busy(true);pairing.newInvite(error->{pairingPanel.busy(false);if(error!=null)pairingPanel.message(error);});}}
            public void approve(PairingClient.Pending seen){
                new AlertDialog.Builder(MainActivity.this).setTitle("Approve this phone?").setMessage("Approve only after the other person reads every matching word to you through your usual phone call. This permanently links that app installation to your family.").setNegativeButton("Not yet",null).setPositiveButton("Approve phone",(d,w)->{if(pairing!=null){pairingPanel.busy(true);pairing.approve(seen,error->{pairingPanel.busy(false);if(error!=null)pairingPanel.message(error);else {status.setText("Family phone approved. No invite code is needed for calls.");connectSignal();}});}}).show();
            }
            public void cancel(){if(pairing!=null)pairing.cancel(error->{if(error!=null)pairingPanel.message(error);else pairingPanel.message("Invitation cancelled. Generate a fresh code when ready.");});}
            public void ownerRecovery(){if(auth!=null&&auth.getCurrentUser()==null){ownerRecovery=true;refresh();}}
        });root.addView(pairingPanel);
        login = column(root); login.setTag("login.panel");projectInfo=text("",13);login.addView(projectInfo); email = new EditText(this); email.setHint("Family account email");email.setTag("login.email"); email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS); login.addView(email);
        password = new EditText(this); password.setHint("Password");password.setTag("login.password"); password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); login.addView(password);
        loginButton=button(login,"Sign in",v -> signIn());loginButton.setTag("login.submit");
        button(login,"Forgot password",v->resetPassword());
        changeConnection=button(login,"Change family connection",v->{if(!authBusy){editingSetup=true;setup.populate(config==null?null:config.data);refresh();}});
        button(login,"Back to family invite",v->{ownerRecovery=false;refresh();});
        home=column(root);home.setTag("home.panel");accountInfo=text("",14);accountInfo.setTag("home.account");home.addView(accountInfo);
        audioCall=button(home,"Call family • audio",v -> permission(false,()->begin(false)));
        videoCall=button(home,"Call family • video",v -> permission(true,()->begin(true)));
        incoming=column(root); button(incoming,"Answer • audio only",v -> permission(false,()->answer(false)));
        button(incoming,"Answer • with video",v -> permission(true,()->answer(true)));
        button(incoming,"Decline",v -> end("Call declined."));
        videos=column(root); remoteView=new SurfaceViewRenderer(this); remoteView.setContentDescription("Family video"); videos.addView(remoteView,new LinearLayout.LayoutParams(-1,dp(220)));
        localView=new SurfaceViewRenderer(this); localView.setContentDescription("Your camera preview"); videos.addView(localView,new LinearLayout.LayoutParams(-1,dp(110)));
        controls=column(root); mute=button(controls,"Mute microphone",v -> { if(rtc!=null){ muted=!muted; rtc.setMuted(muted); mute.setText(muted?"Unmute microphone":"Mute microphone"); } });
        camera=button(controls,"Turn camera on",v -> { if(rtc!=null) { if(mediaVideo) setVideo(false); else permission(true,()->setVideo(true)); } });
        button(controls,"Switch camera",v -> { if(rtc!=null)rtc.switchCamera(); });
        button(controls,"Audio output",v -> { if(route!=null) new AlertDialog.Builder(this).setTitle("Audio output").setItems(route.labels(),(dialog,which)->{ if(route!=null&&!route.select(which))status.setText("Audio route unavailable. Check the connected headset."); }).show(); });
        button(controls,"Hang up / cancel",v -> end("Call ended."));
        refresh();
    }
    private LinearLayout column(LinearLayout root) { LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);root.addView(l);return l; }
    private TextView text(String text,int size){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);return v;}
    private Button button(LinearLayout parent,String label,View.OnClickListener action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setMinHeight(dp(48));b.setOnClickListener(action);parent.addView(b);return b;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void refresh(){
        boolean logged=auth!=null&&auth.getCurrentUser()!=null;
        setup.setVisibility(config==null||editingSetup?View.VISIBLE:View.GONE);
        login.setVisibility(config!=null&&ownerRecovery&&!editingSetup?View.VISIBLE:View.GONE);
        pairingPanel.setVisibility(config!=null&&!editingSetup&&!ownerRecovery&&call==null&&!outgoingPending?View.VISIBLE:View.GONE);
        pairingPanel.state(ownerDevice,familyApproved,peerUid!=null);pairingPanel.recoveryAllowed(!logged);
        projectInfo.setText(config==null?"":"Private project: "+config.data.projectId);
        loginButton.setEnabled(!authBusy);changeConnection.setEnabled(!authBusy);changeConnection.setVisibility(logged?View.GONE:View.VISIBLE);
        boolean active=call!=null||outgoingPending;
        home.setVisibility(logged&&familyApproved&&!active&&!editingSetup&&!ownerRecovery?View.VISIBLE:View.GONE);
        accountInfo.setText(familyApproved?(peerUid==null?"This phone is approved. Invite your family phone to start calling.":"Your phone is remembered. Call your family anytime both apps are open."):"");
        audioCall.setEnabled(peerUid!=null);videoCall.setEnabled(peerUid!=null);
        incoming.setVisibility(call!=null&&!call.isCaller(ownUid)&&"ringing".equals(call.state)?View.VISIBLE:View.GONE);
        controls.setVisibility(active?View.VISIBLE:View.GONE);videos.setVisibility(rtc!=null?View.VISIBLE:View.GONE);
        mute.setEnabled(rtc!=null);camera.setEnabled(rtc!=null);
    }
    private void configure(ConfigData data){
        if(authBusy||call!=null||(auth!=null&&auth.getCurrentUser()!=null))return;
        try{authEpoch++;disconnectSignal();if(pairing!=null){pairing.close();pairing=null;}config=AppConfig.save(this,data);auth=FirebaseAuth.getInstance(config.firebase);editingSetup=false;ownerRecovery=false;peerUid=null;ownUid=null;ownerDevice=false;familyApproved=false;pendingJoinCode=null;status.setText("Family connection saved. Enter your one-time family invitation.");refresh();}
        catch(Exception error){config=null;auth=null;editingSetup=true;refresh();setup.showError(error instanceof IllegalArgumentException?error.getMessage():"Could not save this configuration. Check the values and try again.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=41||result!=RESULT_OK||data==null||data.getData()==null)return;
        final android.net.Uri uri=data.getData();final int attempt=++importEpoch;final String family=importFamily;setup.setImportBusy(true);
        io.execute(()->{ConfigData parsed=null;String error=null;try(InputStream in=getContentResolver().openInputStream(uri)){parsed=AppConfig.readImport(in,family);}catch(Exception failure){error=failure instanceof IllegalArgumentException?failure.getMessage():"Could not read this file. Choose the Android google-services.json or the family configuration.";}
            final ConfigData imported=parsed;final String message=error;main.post(()->{if(isDestroyed()||attempt!=importEpoch)return;setup.setImportBusy(false);if(message!=null)setup.showError(message);else configure(imported);});});
    }
    private void signIn(){
        if(auth==null||authBusy||auth.getCurrentUser()!=null)return;
        String enteredEmail=email.getText().toString().trim(),enteredPassword=password.getText().toString();
        if(!Patterns.EMAIL_ADDRESS.matcher(enteredEmail).matches()||enteredPassword.isEmpty()){status.setText("Enter your email address and password.");return;}
        authBusy=true;refresh();status.setText("Signing in securely…");password.setText("");final FirebaseAuth expected=auth;final int attempt=++authEpoch;
        expected.signInWithEmailAndPassword(enteredEmail,enteredPassword).addOnCompleteListener(task->{
            if(isDestroyed()||auth!=expected||attempt!=authEpoch)return;authBusy=false;
            if(!task.isSuccessful()){status.setText(authError(task.getException(),false));refresh();return;}
            if(foreground)startPairing();refresh();
        });
    }
    private String authError(Exception failure,boolean creating){
        if(failure instanceof com.google.firebase.FirebaseNetworkException)return "No connection to family sign-in. Check Wi-Fi or mobile data and try again.";
        if(failure instanceof com.google.firebase.FirebaseTooManyRequestsException)return "Too many attempts. Wait a little before trying again.";
        if(failure instanceof com.google.firebase.auth.FirebaseAuthException){
            String code=((com.google.firebase.auth.FirebaseAuthException)failure).getErrorCode();
            if("ERROR_OPERATION_NOT_ALLOWED".equals(code))return "Email/password sign-in is not enabled for the family project yet.";
            if("ERROR_EMAIL_ALREADY_IN_USE".equals(code))return "This email already has an account. Sign in or use Forgot password.";
            if("ERROR_WEAK_PASSWORD".equals(code))return "Choose a stronger, unique password and try again.";
            if("ERROR_INVALID_API_KEY".equals(code)||"ERROR_APP_NOT_AUTHORIZED".equals(code))return "Family sign-in configuration was rejected. Check the project connection.";
        }
        return creating?"Account could not be created. Check the email/password and try again.":"Sign-in failed. Check your email and password, or use Forgot password.";
    }
    private void resetPassword(){
        if(auth==null||authBusy)return;String address=email.getText().toString().trim();
        if(!Patterns.EMAIL_ADDRESS.matcher(address).matches()){status.setText("Enter your email address first, then tap Forgot password.");return;}
        new AlertDialog.Builder(this).setTitle("Reset password?").setMessage("Send a password-reset email to "+address+"?").setNegativeButton("Cancel",null).setPositiveButton("Send email",(d,w)->{
            FirebaseAuth expected=auth;expected.sendPasswordResetEmail(address).addOnCompleteListener(task->{if(!isDestroyed()&&auth==expected)status.setText(task.isSuccessful()?"If this address is registered, check its inbox for the reset link.":"Could not request a reset. Check your connection and try again.");});
        }).show();
    }
    @Override protected void onStart(){super.onStart();foreground=true;
        connectivity.registerDefaultNetworkCallback(networkCallback);
        if(auth!=null&&auth.getCurrentUser()!=null)startPairing();
    }
    private void joinFamily(String code){
        if(auth==null||!PairingProof.validCode(code)){pairingPanel.message("Enter exactly six digits from the family owner.");return;}
        if(authBusy)return;
        if(auth.getCurrentUser()==null){
            authBusy=true;pairingPanel.busy(true);pendingJoinCode=code;final FirebaseAuth expected=auth;
            auth.signInAnonymously().addOnCompleteListener(task->{if(isDestroyed()||auth!=expected)return;authBusy=false;if(!task.isSuccessful()){pendingJoinCode=null;pairingPanel.busy(false);pairingPanel.message("Could not securely identify this app installation. Check your connection and try again.");return;}startPairing();});
        }else if(pairing==null){pendingJoinCode=code;pairingPanel.busy(true);startPairing();}
        else submitJoin(code);
    }
    private void submitJoin(String code){if(pairing==null)return;pairingPanel.busy(true);pairing.join(code,error->{pairingPanel.busy(false);if(error!=null)pairingPanel.message(error);});}
    private void startPairing(){
        if(auth==null||auth.getCurrentUser()==null||!foreground)return;
        if(pairing!=null)pairing.close();disconnectSignal();pairingPanel.busy(false);pairingPanel.pending(null);ownerRecovery=false;ownerDevice=false;familyApproved=false;peerUid=null;final String uid=auth.getCurrentUser().getUid();
        final PairingClient[] expected=new PairingClient[1];
        pairing=new PairingClient(FirebaseDatabase.getInstance(config.firebase),config.familyId,uid,new PairingClient.Listener(){
            private boolean valid(){return !isDestroyed()&&foreground&&pairing==expected[0]&&auth.getCurrentUser()!=null&&uid.equals(auth.getCurrentUser().getUid());}
            public void role(boolean owner){if(valid()){ownerDevice=owner;refresh();}}
            public void member(boolean approved){if(!valid())return;familyApproved=approved;if(!approved){peerUid=null;disconnectSignal();if(call!=null||outgoingPending)end("This phone is no longer approved.");}if(approved){pendingJoinCode=null;pairingPanel.busy(false);pairingPanel.pending(null);connectSignal();}else refresh();}
            public void invite(PairingClient.Invite invite){if(!valid())return;pairingPanel.invite(invite,pairing.serverNow());if(!ownerDevice&&pendingJoinCode!=null){String code=pendingJoinCode;pendingJoinCode=null;if(invite!=null)submitJoin(code);else{pairingPanel.busy(false);pairingPanel.message("No active invitation. Ask the owner to generate a fresh code.");}}}
            public void pending(PairingClient.Pending pending){if(valid()){if(pending!=null&&!ownerDevice){pendingJoinCode=null;pairingPanel.busy(false);}pairingPanel.pending(pending);}}
            public void error(String message){if(valid()){pairingPanel.busy(false);pendingJoinCode=null;pairingPanel.message(message);}}
        });expected[0]=pairing;pairing.start();refresh();
    }
    private void connectSignal(){
        disconnectSignal(); if(auth==null||auth.getCurrentUser()==null)return;
        ownUid=auth.getCurrentUser().getUid();peerUid=null;
        if(!familyApproved){refresh();return;}
        status.setText("Checking private family membership…");
        signal=new SignalClient(FirebaseDatabase.getInstance(config.firebase),config.familyId,ownUid,signals);
        final SignalClient expected=signal;
        signal.loadPeer((peer,error)->{
            if(signal!=expected||!foreground)return;
            if(error!=null){status.setText(ownerDevice?"This phone is approved. Invite your family phone to start calling.":"Family partner is not ready yet.");refresh();return;}
            peerUid=peer;status.setText("Ready when you both are. Open this app on both phones.");signal.listen();refresh();
        });
    }
    private void disconnectSignal(){if(signal!=null){signal.close();signal=null;}}
    private void permission(boolean video,Runnable action){
        List<String> missing=new ArrayList<>();
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.RECORD_AUDIO);
        if(video&&checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.CAMERA);
        if(missing.isEmpty()){action.run();return;}
        permissionAction=action;requestPermissions(missing.toArray(new String[0]),40);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);
        if(request!=40)return;Runnable action=permissionAction;permissionAction=null;boolean ok=grants.length>0;
        for(int grant:grants)ok&=grant==PackageManager.PERMISSION_GRANTED;
        if(ok&&foreground&&action!=null)action.run();else status.setText("Permission not granted. Enable it in Android settings, or choose audio-only if camera access is not wanted.");
    }
    private void begin(boolean video){
        if(!foreground||signal==null||peerUid==null||call!=null||outgoingPending)return;
        if(!signal.isConnected()){status.setText("Signaling is offline. Reconnect before calling.");return;}
        outgoingPending=true;cancelledPending=false;mediaVideo=video;status.setText("Calling your family…");refresh();
        final SignalClient expected=signal; final int operation=++operationEpoch;
        signal.begin(peerUid,video,(claimed,error)->{
            if(operation!=operationEpoch){ if(claimed!=null) expected.end(claimed.id,(c,e)->{}); return; }
            outgoingPending=false;
            if(error!=null){if(foreground)status.setText(error);refresh();return;}
            if(cancelledPending||!foreground||signal!=expected){expected.end(claimed.id,(c,e)->{});return;}
            if(call==null || !"accepted".equals(call.state)) { call=claimed; status.setText("Ringing… both phones need the app open."); armRing(); }
            refresh();
        });
    }
    private void answer(boolean video){
        if(signal==null||call==null||!"ringing".equals(call.state))return;
        final String id=call.id;localConsentId=id;mediaVideo=video;stopRing();
        signal.accept(id,(accepted,error)->{if(call==null||!id.equals(call.id))return;if(error!=null){end(error);return;}if("accepted".equals(call.state))startMedia();});
    }
    private final SignalClient.Listener signals=new SignalClient.Listener(){
        public void onCall(SignalClient.Call next){
            if(!foreground)return;
            if(next==null){if(call!=null)endLocal("Call ended.");return;}
            if(dismissedIds.contains(next.id))return;
            if(call!=null&&!call.id.equals(next.id))endLocal("Previous call replaced.");
            if(!next.isLive(signal.serverNow())){if(call!=null&&call.id.equals(next.id))endLocal("Call ended or expired.");return;}
            if("ringing".equals(next.state)){
                if(next.isCaller(ownUid)){if(!outgoingPending&&call==null){signal.end(next.id,(c,e)->{});return;}}
                else if(call==null){status.setText("Incoming family "+(next.video?"video":"audio")+" call");playRing();}
                call=next;armRing();refresh();
            }else if("accepted".equals(next.state)){
                if(call==null){signal.end(next.id,(c,e)->{});return;} // Never resume hidden stale media automatically.
                call=next;startMedia();
            }
        }
        public void onOffer(int rev,String sdp){
            if(call==null||call.isCaller(ownUid)||rtc==null||!CallPolicy.validRemoteRevision(rev,revision,true))return;
            revision=rev;negotiating=true;sdpPublished=false;localIce.clear();rtc.acceptOffer(sdp);flushFutureIce();
        }
        public void onAnswer(int rev,String sdp){
            if(call==null||!call.isCaller(ownUid)||rtc==null||!CallPolicy.validRemoteRevision(rev,revision,false))return;
            rtc.acceptAnswer(sdp);flushFutureIce();
        }
        public void onCandidate(int rev,String mid,int line,String sdp){
            if(rtc==null)return;
            if(rev==revision)rtc.addRemoteIce(mid,line,sdp);
            else if(rev>revision&&futureIce.size()<128)futureIce.add(new Ice(rev,mid,line,sdp));
        }
        public void onRestartRequested(int rev){if(call!=null&&call.isCaller(ownUid)&&rev==revision)recover("Family connection changed. Reconnecting…");}
        public void onError(String message){if(call!=null||outgoingPending)end(message);else status.setText(message);}
    };
    private void startMedia(){
        if(rtc!=null||call==null||!"accepted".equals(call.state)||!foreground)return;
        if(!call.isCaller(ownUid)&&!call.id.equals(localConsentId))return;
        main.removeCallbacks(ringTimeout);stopRing();final int generation=++epoch;
        try{
            route=new AudioRoute(this,()->main.post(()->end("Another call or app interrupted the microphone.")));
            rtc=new RtcEngine(this,localView,remoteView,mediaVideo,new RtcEngine.Listener(){
                private boolean valid(){return epoch==generation&&call!=null&&foreground;}
                public void onLocalDescription(String type,String sdp){
                    if(!valid()||signal==null)return;final String id=call.id;final int rev=revision;
                    SignalClient.Completion sent=(c,error)->{if(!valid()||call==null||!id.equals(call.id)||revision!=rev)return;
                        if(error!=null){end(error);return;}sdpPublished=true;
                        flushLocalIce.run();
                        if("answer".equals(type))negotiating=false;
                    };
                    if("offer".equals(type))signal.offer(id,rev,sdp,sent);else signal.answer(id,rev,sdp,sent);
                }
                public void onLocalIce(String mid,int line,String candidate){if(!valid()||revision<1)return;
                    if(sdpPublished&&signal!=null&&signal.isConnected())signal.candidate(call.id,revision,mid,line,candidate);
                    else if(localIce.size()<64){localIce.add(new Ice(revision,mid,line,candidate));main.removeCallbacks(flushLocalIce);main.postDelayed(flushLocalIce,1000);}
                }
                public void onRemoteAnswerApplied(){if(valid())negotiating=false;}
                public void onState(String state){if(!valid())return;
                    if("CONNECTED".equals(state)){boolean announce=!connectedMedia||recoveryPending;connectedMedia=true;recoveryPending=false;main.removeCallbacks(connectTimeout);main.removeCallbacks(recoveryTimeout);if(announce)status.setText("Connected • "+(mediaVideo?"video":"audio only"));}
                    else if("DISCONNECTED".equals(state)||"FAILED".equals(state))recover("Direct connection interrupted. Reconnecting…");
                }
                public void onWeakNetwork(){if(valid()&&mediaVideo){setVideo(false);status.setText("Weak connection: your camera is off to protect audio. You can turn it on again.");}}
                public void onError(String message){if(valid())end(message);}
            });
            muted=false;mute.setText("Mute microphone");camera.setText(mediaVideo?"Turn camera off":"Turn camera on");
            status.setText("Connecting directly…");getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            main.postDelayed(connectTimeout,CallPolicy.CONNECT_MS);
            main.postDelayed(sessionTimeout,Math.max(1,call.expiresAt-signal.serverNow()));
            if(call.isCaller(ownUid)){revision=1;negotiating=true;sdpPublished=false;rtc.createOffer(false);}
            refresh();
        }catch(RuntimeException e){end("Could not start call media. Check microphone/camera availability.");}
    }
    private void setVideo(boolean enabled){if(rtc!=null&&rtc.setVideoEnabled(enabled)){mediaVideo=enabled;camera.setText(enabled?"Turn camera off":"Turn camera on");}}
    private void flushFutureIce(){if(rtc==null)return;for(Ice ice:futureIce)if(ice.revision==revision)rtc.addRemoteIce(ice.mid,ice.line,ice.sdp);futureIce.removeIf(ice->ice.revision<=revision);}
    private void recover(String message){
        if(rtc==null||call==null)return;status.setText(message);
        if(!recoveryPending){recoveryPending=true;main.postDelayed(recoveryTimeout,CallPolicy.RECOVER_MS);}
        main.removeCallbacks(restart);main.postDelayed(restart,1500);
    }
    private final Runnable restart=new Runnable(){public void run(){
        if(rtc==null||call==null||signal==null||!recoveryPending)return;
        long now=SystemClock.elapsedRealtime();
        if(!signal.isConnected()||!CallPolicy.shouldRestart(true,negotiating,revision,now,lastRestart)){main.postDelayed(this,1500);return;}
        lastRestart=now;
        if(call.isCaller(ownUid)){revision++;negotiating=true;sdpPublished=false;localIce.clear();rtc.createOffer(true);}
        else signal.requestRestart(call.id,revision);
    }};
    private final Runnable flushLocalIce=new Runnable(){public void run(){
        if(rtc==null||call==null||signal==null)return;
        if(sdpPublished&&signal.isConnected()){
            List<Ice> queued=new ArrayList<>(localIce);localIce.clear();
            for(Ice ice:queued)if(ice.revision==revision)signal.candidate(call.id,revision,ice.mid,ice.line,ice.sdp);
        }
        if(!localIce.isEmpty())main.postDelayed(this,1000);
    }};
    private final Runnable ringTimeout=()->end("No answer. Ask your family to open the app, then try again.");
    private final Runnable connectTimeout=()->end("Direct connection failed. Try another Wi-Fi/mobile network. No paid relay is used.");
    private final Runnable recoveryTimeout=()->end("Could not recover the direct connection. Try calling again on another network. No paid fallback was used.");
    private final Runnable sessionTimeout=()->end("Two-hour safety limit reached. Start a new call to continue.");
    private void armRing(){main.removeCallbacks(ringTimeout);if(call!=null)main.postDelayed(ringTimeout,Math.max(1,call.createdAt+CallPolicy.RING_MS-signal.serverNow()));}
    private void playRing(){try{ring=RingtoneManager.getRingtone(this,RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));if(ring!=null)ring.play();}catch(RuntimeException ignored){}}
    private void stopRing(){if(ring!=null){ring.stop();ring=null;}}
    private void end(String message){cancelledPending=true;String id=call==null?null:call.id;endLocal(message);if(signal!=null&&id!=null)signal.end(id,(c,e)->{});}
    private void endLocal(String message){
        epoch++;operationEpoch++;localConsentId=null;if(call!=null){dismissedIds.add(call.id);if(dismissedIds.size()>16)dismissedIds.remove(dismissedIds.iterator().next());}main.removeCallbacks(ringTimeout);main.removeCallbacks(connectTimeout);main.removeCallbacks(recoveryTimeout);main.removeCallbacks(sessionTimeout);main.removeCallbacks(restart);main.removeCallbacks(flushLocalIce);
        stopRing();if(rtc!=null){rtc.close();rtc=null;}if(route!=null){route.close();route=null;}
        call=null;outgoingPending=false;revision=0;negotiating=false;sdpPublished=false;connectedMedia=false;recoveryPending=false;localIce.clear();futureIce.clear();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);status.setText(message);refresh();
    }
    @Override protected void onStop(){foreground=false;permissionAction=null;if(call!=null||outgoingPending||rtc!=null)end("Call stopped because the app left the foreground. Reopen on both phones to call again.");disconnectSignal();if(pairing!=null){pairing.close();pairing=null;}
        try{connectivity.unregisterNetworkCallback(networkCallback);}catch(IllegalArgumentException ignored){}super.onStop();
    }
    @Override protected void onDestroy(){importEpoch++;io.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
