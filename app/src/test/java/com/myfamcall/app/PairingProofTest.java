package com.myfamcall.app;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class PairingProofTest {
    private static final String NONCE="00112233445566778899aabbccddeeff", CHALLENGE="ffeeddccbbaa99887766554433221100";
    private List<String> words(){List<String> result=new ArrayList<>();for(int i=0;i<2048;i++)result.add("word"+i);return result;}
    @Test public void randomValuesHaveRequiredFormat(){Set<String> seen=new HashSet<>();for(int i=0;i<100;i++){assertTrue(PairingProof.validCode(PairingProof.code()));String n=PairingProof.nonce();assertTrue(n.matches("[0-9a-f]{32}"));assertTrue(seen.add(n));}}
    @Test public void acceptsExactlySixAsciiDigits(){for(String invalid:new String[]{null,"12345","1234567","12 456","abcdef","１２３４５６"})assertFalse(PairingProof.validCode(invalid));assertTrue(PairingProof.validCode("000001"));}
    @Test public void phraseIsDeterministicAndBindsAllInputs(){String p=PairingProof.phrase(NONCE,"joiner",CHALLENGE,words());assertEquals(6,p.split(" ").length);assertEquals(p,PairingProof.phrase(NONCE,"joiner",CHALLENGE,words()));assertNotEquals(p,PairingProof.phrase(NONCE,"other",CHALLENGE,words()));assertNotEquals(p,PairingProof.phrase(CHALLENGE,"joiner",CHALLENGE,words()));assertNotEquals(p,PairingProof.phrase(NONCE,"joiner",NONCE,words()));}
    @Test public void malformedProofFailsClosed(){try{PairingProof.phrase("bad","joiner",CHALLENGE,words());fail();}catch(IllegalArgumentException expected){}try{PairingProof.phrase(NONCE,"joiner",CHALLENGE,List.of("one"));fail();}catch(IllegalArgumentException expected){}}
}
