package com.myfamcall.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

/** PIN is only rendezvous. Owner must compare the full UID/invite-bound phrase before approval. */
public final class PairingProof {
    private static final SecureRandom RANDOM=new SecureRandom();
    private PairingProof(){}
    public static String code(){return String.format(Locale.ROOT,"%06d",RANDOM.nextInt(1_000_000));}
    public static String nonce(){byte[] bytes=new byte[16];RANDOM.nextBytes(bytes);StringBuilder text=new StringBuilder();for(byte value:bytes)text.append(String.format(Locale.ROOT,"%02x",value&255));return text.toString();}
    public static boolean validCode(String code){return code!=null&&code.matches("[0-9]{6}");}
    public static String phrase(String inviteNonce,String uid,String challenge,List<String> words){
        if(words.size()!=2048)throw new IllegalArgumentException("Pairing dictionary must have 2048 words");
        if(inviteNonce==null||!inviteNonce.matches("[0-9a-f]{32}")||challenge==null||!challenge.matches("[0-9a-f]{32}")||uid==null||uid.isEmpty())throw new IllegalArgumentException("Invalid pairing proof");
        try{
            byte[] hash=MessageDigest.getInstance("SHA-256").digest((inviteNonce+"\u0000"+uid+"\u0000"+challenge).getBytes(StandardCharsets.UTF_8));
            StringBuilder phrase=new StringBuilder();
            for(int i=0;i<6;i++){int index=0;for(int bit=i*11;bit<(i+1)*11;bit++)index=(index<<1)|((hash[bit/8]>>(7-bit%8))&1);if(i>0)phrase.append(' ');phrase.append(words.get(index));}
            return phrase.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
