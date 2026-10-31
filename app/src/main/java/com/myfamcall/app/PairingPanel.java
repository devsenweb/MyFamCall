package com.myfamcall.app;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One-time family enrollment. No email requirement or PIN for subsequent calls. */
public final class PairingPanel extends LinearLayout {
    public interface Actions{void join(String code);void create();void approve(PairingClient.Pending seen);void cancel();void ownerRecovery();}
    private final Actions actions;private final List<String> words=new ArrayList<>();
    private final TextView title,help,codeText,phrase,status;
    private final EditText code;
    private final Button join,create,approve,cancel,recovery;
    private PairingClient.Pending pending;
    private boolean owner,member,busy,partnerReady;
    private boolean recoveryAllowed=true;
    private final android.os.Handler timer=new android.os.Handler(android.os.Looper.getMainLooper());
    private PairingClient.Invite activeInvite;
    private boolean inviteObserved,inviteLive;
    private final Runnable expiry=()->{if(activeInvite!=null)invite(activeInvite,activeInvite.expiresAt);};
    public PairingPanel(Context context,Actions actions){
        super(context);this.actions=actions;setOrientation(VERTICAL);setTag("pairing.panel");
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(getResources().openRawResource(R.raw.pairing_words),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)words.add(line.trim());}catch(Exception e){throw new IllegalStateException("Pairing dictionary unavailable",e);}
        title=text("Join your family",24);help=text("Enter the six-digit invitation shared by phone. You only do this once; this app remembers your phone.",15);
        code=new EditText(context);code.setTag("pairing.code");code.setHint("Six-digit invitation");code.setInputType(InputType.TYPE_CLASS_NUMBER);code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});addView(code);
        join=button("Join family",v->actions.join(code.getText().toString()));join.setTag("pairing.join");
        create=button("Invite family phone",v->actions.create());create.setTag("pairing.create");
        codeText=text("",30);codeText.setTag("pairing.ownerCode");codeText.setTypeface(android.graphics.Typeface.MONOSPACE);
        phrase=text("",21);phrase.setTag("pairing.phrase");phrase.setTextIsSelectable(true);
        approve=button("The phrases match — Approve phone",v->{if(pending!=null)actions.approve(pending);});approve.setTag("pairing.approve");
        cancel=button("Cancel invite / reject request",v->actions.cancel());
        status=text("",15);status.setTag("pairing.status");
        recovery=button("Owner recovery",v->actions.ownerRecovery());recovery.setTag("pairing.recovery");cancel.setTag("pairing.cancel");refresh();
    }
    private TextView text(String value,int size){TextView text=new TextView(getContext());text.setText(value);text.setTextSize(size);text.setPadding(0,12,0,12);addView(text);return text;}
    private Button button(String text,View.OnClickListener listener){Button button=new Button(getContext());button.setText(text);button.setAllCaps(false);button.setOnClickListener(listener);addView(button);return button;}
    public void state(boolean owner,boolean member,boolean partnerReady){this.owner=owner;this.member=member;this.partnerReady=partnerReady;refresh();}
    public void recoveryAllowed(boolean allowed){recoveryAllowed=allowed;refresh();}
    public void busy(boolean value){busy=value;refresh();}
    public void message(String message){status.setText(message);}
    public void invite(PairingClient.Invite invite,long now){
        timer.removeCallbacks(expiry);activeInvite=invite;
        boolean live=invite!=null&&"open".equals(invite.state)&&invite.expiresAt>now;inviteObserved=true;inviteLive=live;
        if(live)timer.postDelayed(expiry,invite.expiresAt-now);
        if(pending!=null&&(!live||!pending.nonce.equals(invite.nonce))){
            pending=null;phrase.setText("");
            if(!member)message("The invitation ended or changed. Ask the owner for a fresh code.");
        }
        if(owner){
            codeText.setText(live?invite.code:"");create.setText(live?"Generate a new code":"Invite family phone");
            cancel.setVisibility(live||pending!=null?VISIBLE:GONE);
            if(!live&&pending==null&&!partnerReady)message("Generate a temporary code and share it through your usual phone call.");
        }
        refresh();
    }
    @Override protected void onDetachedFromWindow(){timer.removeCallbacks(expiry);super.onDetachedFromWindow();}
    public void pending(PairingClient.Pending value){
        if(value!=null&&inviteObserved&&(!inviteLive||activeInvite==null||!value.nonce.equals(activeInvite.nonce))){value=null;}
        pending=value;if(value!=null)code.setText("");
        if(value==null){phrase.setText("");refresh();return;}
        try{phrase.setText(PairingProof.phrase(value.nonce,value.uid,value.challenge,words));}catch(IllegalArgumentException malformed){pending=null;phrase.setText("");message("Invalid phone request. Cancel this invite and create a new one.");refresh();return;}
        message(owner?"Ask the other person to read all six words from their phone. Approve only if every word matches. A correct invite code alone does not prove who is joining.":"Read these six words to the family owner through your usual phone call. Wait for them to compare and approve this phone.");
        refresh();
    }
    private void refresh(){
        title.setText(owner?(partnerReady?"Your family phones are connected":"Invite your family phone"):(member?"This phone is approved":"Join your family"));
        help.setText(partnerReady?"Both family phones are approved. No invite code is needed for calls.":(owner?"Create a temporary code, share it by phone, then compare the six words and approve once.":"One-time setup only. Your approved phone is remembered; no code is needed for calls."));
        code.setVisibility(!owner&&!member&&pending==null?VISIBLE:GONE);join.setVisibility(!owner&&!member&&pending==null?VISIBLE:GONE);
        create.setVisibility(owner&&!partnerReady?VISIBLE:GONE);codeText.setVisibility(owner&&!partnerReady?VISIBLE:GONE);
        approve.setVisibility(owner&&pending!=null&&!partnerReady?VISIBLE:GONE);
        if(!owner||partnerReady)cancel.setVisibility(GONE);
        phrase.setVisibility(pending!=null&&!member||owner&&pending!=null?VISIBLE:GONE);
        recovery.setVisibility(recoveryAllowed&&!owner&&!member?VISIBLE:GONE);
        join.setEnabled(!busy);create.setEnabled(!busy);approve.setEnabled(!busy);cancel.setEnabled(!busy);code.setEnabled(!busy);
    }
}
