package com.myfamcall.app;
import android.view.View;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;
import java.time.Duration;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class PairingPanelTest {
    static final String NONCE="00112233445566778899aabbccddeeff",CHALLENGE="ffeeddccbbaa99887766554433221100";
    static class Actions implements PairingPanel.Actions {String code;PairingClient.Pending approved;int created,cancelled;public void join(String c){code=c;}public void create(){created++;}public void approve(PairingClient.Pending p){approved=p;}public void cancel(){cancelled++;}public void ownerRecovery(){}}
    private View v(PairingPanel p,String tag){return p.findViewWithTag("pairing."+tag);}
    @Test public void newPhoneUsesOneTimeCodeNotEmail(){Actions a=new Actions();PairingPanel p=new PairingPanel(RuntimeEnvironment.getApplication(),a);p.state(false,false,false);assertEquals(View.VISIBLE,v(p,"code").getVisibility());((EditText)v(p,"code")).setText("123456");v(p,"join").performClick();assertEquals("123456",a.code);assertEquals(View.GONE,v(p,"approve").getVisibility());}
    @Test public void ownerMustExplicitlyApproveExactVisibleRequest(){Actions a=new Actions();PairingPanel p=new PairingPanel(RuntimeEnvironment.getApplication(),a);p.state(true,true,false);p.invite(new PairingClient.Invite(NONCE,"123456","open",300000),0);assertEquals("123456",((TextView)v(p,"ownerCode")).getText().toString());PairingClient.Pending request=new PairingClient.Pending("joiner",NONCE,CHALLENGE);p.pending(request);assertNull(a.approved);assertEquals(6,((TextView)v(p,"phrase")).getText().toString().split(" ").length);v(p,"approve").performClick();assertSame(request,a.approved);}
    @Test public void regenerationClearsOldPhraseAndApproval(){PairingPanel p=new PairingPanel(RuntimeEnvironment.getApplication(),new Actions());p.state(true,true,false);p.invite(new PairingClient.Invite(NONCE,"123456","open",300000),0);p.pending(new PairingClient.Pending("joiner",NONCE,CHALLENGE));p.invite(new PairingClient.Invite(CHALLENGE,"654321","open",300000),0);assertEquals(View.GONE,v(p,"approve").getVisibility());assertEquals("",((TextView)v(p,"phrase")).getText().toString());}
    @Test public void expiryDoesNotLeaveActionableStaleCode(){PairingPanel p=new PairingPanel(RuntimeEnvironment.getApplication(),new Actions());p.state(true,true,false);p.invite(new PairingClient.Invite(NONCE,"123456","open",1000),0);p.pending(new PairingClient.Pending("joiner",NONCE,CHALLENGE));Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertEquals("",((TextView)v(p,"ownerCode")).getText().toString());assertEquals(View.GONE,v(p,"approve").getVisibility());}
    @Test public void expiredSnapshotCannotRestorePendingApproval(){PairingPanel p=new PairingPanel(RuntimeEnvironment.getApplication(),new Actions());p.state(true,true,false);p.invite(new PairingClient.Invite(NONCE,"123456","open",1000),2000);p.pending(new PairingClient.Pending("joiner",NONCE,CHALLENGE));assertEquals(View.GONE,v(p,"approve").getVisibility());}
    @Test public void approvedPhoneNeedsNoCodeAndNoSignout(){PairingPanel p=new PairingPanel(RuntimeEnvironment.getApplication(),new Actions());p.state(false,true,true);assertEquals(View.GONE,v(p,"code").getVisibility());assertEquals(View.GONE,v(p,"join").getVisibility());assertEquals(View.GONE,v(p,"recovery").getVisibility());}
    @Test public void retainedCredentialNeverOffersIdentityReplacement(){PairingPanel p=new PairingPanel(RuntimeEnvironment.getApplication(),new Actions());p.state(false,false,false);p.recoveryAllowed(false);assertEquals(View.GONE,v(p,"recovery").getVisibility());}
    @Test public void busyBlocksDuplicateJoinAndApprove(){PairingPanel p=new PairingPanel(RuntimeEnvironment.getApplication(),new Actions());p.busy(true);assertFalse(v(p,"join").isEnabled());assertFalse(v(p,"approve").isEnabled());p.busy(false);assertTrue(v(p,"join").isEnabled());}
}
