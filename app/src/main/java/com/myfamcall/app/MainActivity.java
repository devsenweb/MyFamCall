package com.myfamcall.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
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
    private Button mute, camera, loginButton, audioCall, videoCall,signupButton,changeConnection,retryApproval,copyUid,verifyEmail,checkEmail;
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
        status.setText(config==null?"Connect once, then call your family.":"Sign in to your private family.");
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
        login = column(root); login.setTag("login.panel");projectInfo=text("",13);login.addView(projectInfo); email = new EditText(this); email.setHint("Family account email");email.setTag("login.email"); email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS); login.addView(email);
        password = new EditText(this); password.setHint("Password");password.setTag("login.password"); password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); login.addView(password);
        loginButton=button(login,"Sign in",v -> signIn());loginButton.setTag("login.submit");
        signupButton=button(login,"Create my account",v->showSignup());signupButton.setTag("login.signup");
        button(login,"Forgot password",v->resetPassword());
        changeConnection=button(login,"Change family connection",v->{if(!authBusy){editingSetup=true;setup.populate(config==null?null:config.data);refresh();}});
        home=column(root);home.setTag("home.panel");accountInfo=text("",14);accountInfo.setTag("home.account");home.addView(accountInfo);
        verifyEmail=button(home,"Send verification email",v->sendVerification());
        checkEmail=button(home,"I verified my email",v->checkVerification());
        copyUid=button(home,"Copy my account ID for family approval",v->{if(auth!=null&&auth.getCurrentUser()!=null){ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("My Fam Call account ID",auth.getCurrentUser().getUid()));Toast.makeText(this,"Account ID copied. Never share your password.",Toast.LENGTH_LONG).show();}});
        retryApproval=button(home,"Check family approval",v->connectSignal());
        audioCall=button(home,"Call family • audio",v -> permission(false,()->begin(false)));
        videoCall=button(home,"Call family • video",v -> permission(true,()->begin(true)));
        button(home,"Sign out",v -> { end("Signed out."); disconnectSignal(); authEpoch++;if(auth!=null)auth.signOut(); ownUid=null; peerUid=null; refresh(); });
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
        login.setVisibility(config!=null&&!logged&&!editingSetup?View.VISIBLE:View.GONE);
        projectInfo.setText(config==null?"":"Private project: "+config.data.projectId);
        loginButton.setEnabled(!authBusy);signupButton.setEnabled(!authBusy);changeConnection.setEnabled(!authBusy);
        boolean active=call!=null||outgoingPending;
        home.setVisibility(logged&&!active&&!editingSetup?View.VISIBLE:View.GONE);
        boolean verified=logged&&auth.getCurrentUser().isEmailVerified();
        accountInfo.setText(logged?"Signed in as "+auth.getCurrentUser().getEmail()+(!verified?"\nVerify your email first. Then copy your account ID for family approval.":(peerUid==null?"\nWaiting for family approval. Your account does not yet have calling access.":"\nPrivate family access approved.")):"");
        verifyEmail.setVisibility(logged&&!verified?View.VISIBLE:View.GONE);checkEmail.setVisibility(logged&&!verified?View.VISIBLE:View.GONE);
        copyUid.setVisibility(verified&&peerUid==null?View.VISIBLE:View.GONE);retryApproval.setVisibility(verified&&peerUid==null?View.VISIBLE:View.GONE);
        audioCall.setEnabled(peerUid!=null);videoCall.setEnabled(peerUid!=null);
        incoming.setVisibility(call!=null&&!call.isCaller(ownUid)&&"ringing".equals(call.state)?View.VISIBLE:View.GONE);
        controls.setVisibility(active?View.VISIBLE:View.GONE);videos.setVisibility(rtc!=null?View.VISIBLE:View.GONE);
        mute.setEnabled(rtc!=null);camera.setEnabled(rtc!=null);
    }
    private void configure(ConfigData data){
        if(authBusy||call!=null)return;
        try{authEpoch++;disconnectSignal();if(auth!=null)auth.signOut();config=AppConfig.save(this,data);auth=FirebaseAuth.getInstance(config.firebase);editingSetup=false;peerUid=null;ownUid=null;status.setText("Family connection saved. Sign in or create your own account.");refresh();}
        catch(Exception error){config=null;auth=null;editingSetup=true;refresh();setup.showError(error instanceof IllegalArgumentException?error.getMessage():"Could not save this configuration. Check the values and try again.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=41||result!=RESULT_OK||data==null||data.getData()==null)return;
        final android.net.Uri uri=data.getData();final int attempt=++importEpoch;final String family=importFamily;setup.setImportBusy(true);
        io.execute(()->{ConfigData parsed=null;String error=null;try(InputStream in=getContentResolver().openInputStream(uri)){parsed=AppConfig.readImport(in,family);}catch(Exception failure){error=failure instanceof IllegalArgumentException?failure.getMessage():"Could not read this file. Choose the Android google-services.json or the family configuration.";}
            final ConfigData imported=parsed;final String message=error;main.post(()->{if(isDestroyed()||attempt!=importEpoch)return;setup.setImportBusy(false);if(message!=null)setup.showError(message);else configure(imported);});});
    }
    private void signIn(){
        if(auth==null||authBusy)return;
        String enteredEmail=email.getText().toString().trim(),enteredPassword=password.getText().toString();
        if(!Patterns.EMAIL_ADDRESS.matcher(enteredEmail).matches()||enteredPassword.isEmpty()){status.setText("Enter your email address and password.");return;}
        authBusy=true;refresh();status.setText("Signing in securely…");password.setText("");final FirebaseAuth expected=auth;final int attempt=++authEpoch;
        expected.signInWithEmailAndPassword(enteredEmail,enteredPassword).addOnCompleteListener(task->{
            if(isDestroyed()||auth!=expected||attempt!=authEpoch)return;authBusy=false;
            if(!task.isSuccessful()){status.setText(authError(task.getException(),false));refresh();return;}
            if(foreground)connectSignal();refresh();
        });
    }
    private void showSignup(){
        if(auth==null||authBusy)return;
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(20),0,dp(20),0);
        TextView info=text("Create your own login. Calling stays locked until the family owner approves your account ID. Enter your password only on this phone.",14);fields.addView(info);
        EditText address=new EditText(this);address.setHint("Your email");address.setTag("signup.email");address.setText(email.getText());address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);fields.addView(address);
        EditText secret=new EditText(this);secret.setHint("Password (at least 12 characters)");secret.setTag("signup.password");secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);fields.addView(secret);
        EditText confirm=new EditText(this);confirm.setHint("Confirm password");confirm.setTag("signup.confirm");confirm.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);fields.addView(confirm);
        TextView error=text("",14);error.setTextColor(0xffa02727);error.setTag("signup.error");fields.addView(error);
        ScrollView signupScroll=new ScrollView(this);signupScroll.addView(fields);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Create my private account").setView(signupScroll).setNegativeButton("Cancel",(d,w)->{secret.setText("");confirm.setText("");}).setPositiveButton("Create account",null).create();
        dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String name=address.getText().toString().trim(),pass=secret.getText().toString();
            if(!Patterns.EMAIL_ADDRESS.matcher(name).matches()){error.setText("Enter a valid email address.");return;}
            if(pass.length()<12){error.setText("Use a unique password with at least 12 characters.");return;}
            if(!pass.equals(confirm.getText().toString())){error.setText("The passwords do not match.");return;}
            final FirebaseAuth expected=auth;final int attempt=++authEpoch;authBusy=true;refresh();status.setText("Creating your sign-in account…");secret.setText("");confirm.setText("");dialog.dismiss();
            expected.createUserWithEmailAndPassword(name,pass).addOnCompleteListener(task->{
                if(isDestroyed()||auth!=expected||attempt!=authEpoch)return;authBusy=false;
                if(!task.isSuccessful()){status.setText(authError(task.getException(),true));refresh();return;}
                email.setText(name);sendVerification();if(foreground)connectSignal();refresh();
            });
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
    private void sendVerification(){
        if(auth==null||auth.getCurrentUser()==null||auth.getCurrentUser().isEmailVerified())return;
        final FirebaseAuth expected=auth;verifyEmail.setEnabled(false);
        auth.getCurrentUser().sendEmailVerification().addOnCompleteListener(task->{
            if(isDestroyed()||auth!=expected)return;verifyEmail.setEnabled(true);
            status.setText(task.isSuccessful()?"Verification email sent. Open its link, then tap I verified my email.":"Could not send verification yet. Check your connection or wait before trying again.");
        });
    }
    private void checkVerification(){
        if(auth==null||auth.getCurrentUser()==null)return;final FirebaseAuth expected=auth;final String uid=auth.getCurrentUser().getUid();checkEmail.setEnabled(false);
        auth.getCurrentUser().reload().addOnCompleteListener(task->{
            if(isDestroyed()||auth!=expected||auth.getCurrentUser()==null||!uid.equals(auth.getCurrentUser().getUid()))return;checkEmail.setEnabled(true);
            if(!task.isSuccessful()){status.setText("Could not check verification. Reconnect and try again.");return;}
            if(!auth.getCurrentUser().isEmailVerified()){status.setText("Email is not verified yet. Open the verification link in your inbox, then try again.");refresh();return;}
            auth.getCurrentUser().getIdToken(true).addOnCompleteListener(token->{if(isDestroyed()||auth!=expected)return;if(token.isSuccessful()&&foreground)connectSignal();else status.setText("Email verified. Reconnect to check family access.");refresh();});
        });
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
        if(auth!=null&&auth.getCurrentUser()!=null)connectSignal();
    }
    private void connectSignal(){
        disconnectSignal(); if(auth==null||auth.getCurrentUser()==null)return;
        ownUid=auth.getCurrentUser().getUid();peerUid=null;
        if(!auth.getCurrentUser().isEmailVerified()){status.setText("Check your inbox to verify your email before calling.");refresh();return;}
        status.setText("Checking private family membership…");
        signal=new SignalClient(FirebaseDatabase.getInstance(config.firebase),config.familyId,ownUid,signals);
        final SignalClient expected=signal;
        signal.loadPeer((peer,error)->{
            if(signal!=expected||!foreground)return;
            if(error!=null){status.setText("Account signed in. Family calling is still locked until the owner approves both account IDs.");refresh();return;}
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
    @Override protected void onStop(){foreground=false;permissionAction=null;if(call!=null||outgoingPending||rtc!=null)end("Call stopped because the app left the foreground. Reopen on both phones to call again.");disconnectSignal();
        try{connectivity.unregisterNetworkCallback(networkCallback);}catch(IllegalArgumentException ignored){}super.onStop();
    }
    @Override protected void onDestroy(){importEpoch++;io.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
