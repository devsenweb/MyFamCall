package com.myfamcall.app;

import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;

/** Bounded two-phone enrollment. Never grants access on a PIN match alone. */
public final class PairingClient implements AutoCloseable {
    public interface Listener {
        void role(boolean owner);
        void member(boolean approved);
        void invite(Invite invite);
        void pending(Pending pending);
        void error(String message);
    }
    public interface Completion {void complete(String error);}
    public static final class Invite {
        public final String nonce,code,state;public final long expiresAt;
        Invite(String nonce,String code,String state,long expiresAt){this.nonce=nonce;this.code=code;this.state=state;this.expiresAt=expiresAt;}
    }
    public static final class Pending {
        public final String uid,nonce,challenge;
        public Pending(String uid,String nonce,String challenge){this.uid=uid;this.nonce=nonce;this.challenge=challenge;}
    }
    private final DatabaseReference family,connectedRef,offsetRef;
    private final String uid;private final Listener listener;
    private boolean closed,connected,owner;private long offset;
    private Invite currentInvite;
    private Pending ownPending;
    private ValueEventListener ownerEnrollmentListener;
    private final ValueEventListener connectedListener=new ValueEventListener(){public void onDataChange(DataSnapshot s){connected=Boolean.TRUE.equals(s.getValue());}public void onCancelled(DatabaseError e){connected=false;}};
    private final ValueEventListener offsetListener=new ValueEventListener(){public void onDataChange(DataSnapshot s){offset=number(s);}public void onCancelled(DatabaseError e){offset=0;}};
    private final ValueEventListener memberListener=new ValueEventListener(){public void onDataChange(DataSnapshot s){if(!closed)listener.member(Boolean.TRUE.equals(s.getValue()));}public void onCancelled(DatabaseError e){if(!closed)listener.error("Could not check this phone's family access.");}};
    private final ValueEventListener infoListener=new ValueEventListener(){public void onDataChange(DataSnapshot s){if(closed||owner)return;currentInvite=s.exists()?new Invite(string(s.child("nonce")),"","open",number(s.child("expiresAt"))):null;if(ownPending!=null&&(currentInvite==null||!ownPending.nonce.equals(currentInvite.nonce)||currentInvite.expiresAt<=serverNow())){ownPending=null;listener.pending(null);}listener.invite(currentInvite);}public void onCancelled(DatabaseError e){if(!closed)listener.error("Could not read the active invitation. Reconnect and try again.");}};
    public PairingClient(FirebaseDatabase db,String familyId,String uid,Listener listener){
        this.uid=uid;this.listener=listener;family=db.getReference("families").child(familyId);
        connectedRef=db.getReference(".info/connected");offsetRef=db.getReference(".info/serverTimeOffset");
    }
    public void start(){
        connectedRef.addValueEventListener(connectedListener);offsetRef.addValueEventListener(offsetListener);
        family.child("ownerUid").get().addOnCompleteListener(task->{
            if(closed)return;
            if(!task.isSuccessful()||!task.getResult().exists()){listener.error("Family device enrollment is not ready yet. Try again after setup finishes.");return;}
            owner=uid.equals(string(task.getResult()));listener.role(owner);
            family.child("members").child(uid).addValueEventListener(memberListener);
            if(owner){
                ownerEnrollmentListener=new ValueEventListener(){public void onDataChange(DataSnapshot s){
                    if(closed)return;DataSnapshot invite=s.child("invite");currentInvite=invite.exists()?new Invite(string(invite.child("nonce")),string(invite.child("code")),string(invite.child("state")),number(invite.child("expiresAt"))):null;
                    listener.invite(currentInvite);DataSnapshot p=s.child("pending");listener.pending(p.exists()?new Pending(string(p.child("uid")),string(p.child("nonce")),string(p.child("challenge"))):null);
                }public void onCancelled(DatabaseError e){if(!closed)listener.error("Could not read family enrollment.");}};
                family.child("enrollment").addValueEventListener(ownerEnrollmentListener);
            }else {
                // A phone call/app restart during onboarding must not discard the matching phrase.
                family.child("enrollment/pending").get().addOnCompleteListener(pendingTask->{
                    if(closed)return;
                    if(pendingTask.isSuccessful()){DataSnapshot p=pendingTask.getResult();if(uid.equals(string(p.child("uid")))){ownPending=new Pending(uid,string(p.child("nonce")),string(p.child("challenge")));listener.pending(ownPending);}}
                    // Permission-denied here simply means this is not the requester; no data is exposed.
                    family.child("enrollment/info").addValueEventListener(infoListener);
                });
            }
        });
    }
    public long serverNow(){return System.currentTimeMillis()+offset;}
    public boolean isConnected(){return connected&&!closed;}
    public void newInvite(Completion completion){
        if(!ready(completion))return;if(!owner){completion.complete("Only the family owner can do this.");return;}
        String nonce=PairingProof.nonce(),code=PairingProof.code();long expires=serverNow()+295_000;
        Map<String,Object> invite=new HashMap<>();invite.put("nonce",nonce);invite.put("code",code);invite.put("state","open");invite.put("createdAt",ServerValue.TIMESTAMP);invite.put("expiresAt",expires);
        Map<String,Object> info=new HashMap<>();info.put("nonce",nonce);info.put("expiresAt",expires);
        Map<String,Object> updates=new HashMap<>();updates.put("enrollment/invite",invite);updates.put("enrollment/info",info);updates.put("enrollment/pending",null);updates.put("enrollment/approval",null);
        write(updates,completion,"Could not create a new invitation. Check the connection or existing family phone.");
    }
    public void join(String code,Completion completion){
        if(!ready(completion))return;
        if(!PairingProof.validCode(code)){completion.complete("Enter exactly six digits.");return;}
        Invite invite=currentInvite;
        if(invite==null||invite.expiresAt<=serverNow()){completion.complete("No current invite. Ask the family owner to create a fresh code.");return;}
        Pending pending=new Pending(uid,invite.nonce,PairingProof.nonce());
        Map<String,Object> data=new HashMap<>();data.put("uid",uid);data.put("nonce",pending.nonce);data.put("code",code);data.put("challenge",pending.challenge);data.put("createdAt",ServerValue.TIMESTAMP);
        family.child("enrollment/pending").setValue(data).addOnCompleteListener(task->{
            if(closed)return;
            if(task.isSuccessful()){ownPending=pending;listener.pending(pending);completion.complete(null);}
            else completion.complete("That invite is incorrect, expired or already waiting for approval. Ask the owner to check or generate a new code.");
        });
    }
    public void approve(Pending seen,Completion completion){
        if(!ready(completion))return;if(!owner||seen==null){completion.complete("No owner-approved request is selected.");return;}
        Map<String,Object> proof=new HashMap<>();proof.put("uid",seen.uid);proof.put("nonce",seen.nonce);proof.put("challenge",seen.challenge);proof.put("approvedAt",ServerValue.TIMESTAMP);
        Map<String,Object> updates=new HashMap<>();updates.put("partnerUid",seen.uid);updates.put("members/"+seen.uid,true);updates.put("enrollment/invite/state","used");updates.put("enrollment/info",null);updates.put("enrollment/pending",null);updates.put("enrollment/approval",proof);
        write(updates,completion,"The request changed or expired. Compare the new phrase before approving again.");
    }
    public void cancel(Completion completion){
        if(!ready(completion))return;if(!owner){completion.complete("Only the family owner can do this.");return;}
        family.child("enrollment").removeValue().addOnCompleteListener(task->{if(!closed)completion.complete(task.isSuccessful()?null:"Could not cancel the invite. Reconnect and try again.");});
    }
    private void write(Map<String,Object> updates,Completion completion,String error){family.updateChildren(updates).addOnCompleteListener(task->{if(!closed)completion.complete(task.isSuccessful()?null:error);});}
    private boolean ready(Completion completion){if(!isConnected()){completion.complete("Family connection is offline. Reconnect first.");return false;}return true;}
    private static String string(DataSnapshot s){Object value=s.getValue();return value instanceof String?(String)value:"";}
    private static long number(DataSnapshot s){Object value=s.getValue();return value instanceof Number?((Number)value).longValue():0;}
    public void close(){closed=true;connected=false;connectedRef.removeEventListener(connectedListener);offsetRef.removeEventListener(offsetListener);family.child("members").child(uid).removeEventListener(memberListener);family.child("enrollment/info").removeEventListener(infoListener);if(ownerEnrollmentListener!=null)family.child("enrollment").removeEventListener(ownerEnrollmentListener);}
}
