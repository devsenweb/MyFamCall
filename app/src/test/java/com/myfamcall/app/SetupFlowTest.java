package com.myfamcall.app;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import com.google.firebase.FirebaseApp;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class SetupFlowTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private ActivityController<MainActivity> controller;
    @Before public void before(){AppConfig.clear(context);for(FirebaseApp app:FirebaseApp.getApps(context))app.delete();}
    @After public void after(){if(controller!=null)controller.pause().stop().destroy();AppConfig.clear(context);for(FirebaseApp app:FirebaseApp.getApps(context))app.delete();}
    private MainActivity activity(){controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();return controller.get();}
    private ConfigData valid(){return ConfigDataTest.config("https://demo-family-call-default-rtdb.firebaseio.com");}
    private View find(MainActivity activity,String tag){return activity.getWindow().getDecorView().findViewWithTag(tag);}
    private View buttonNamed(View view,String label){
        if(view instanceof android.widget.Button && label.contentEquals(((android.widget.Button)view).getText()))return view;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=buttonNamed(group.getChildAt(i),label);if(found!=null)return found;}}return null;
    }
    private void openSetup(MainActivity activity){
        if(find(activity,"setup.panel").getVisibility()!=View.VISIBLE){find(activity,"pairing.recovery").performClick();buttonNamed(activity.getWindow().getDecorView(),"Change family connection").performClick();}
        assertEquals(View.VISIBLE,find(activity,"setup.panel").getVisibility());
    }
    @Test public void freshInstallHasSetupOrFamilyInviteNotSignup(){MainActivity activity=activity();boolean setupShown=find(activity,"setup.panel").getVisibility()==View.VISIBLE;assertEquals(setupShown?View.GONE:View.VISIBLE,find(activity,"pairing.panel").getVisibility());assertEquals(View.GONE,find(activity,"login.panel").getVisibility());openSetup(activity);assertNotNull(find(activity,"setup.family"));}
    @Test public void savedConfigShowsFamilyInviteWithoutRecompile() throws Exception {AppConfig.save(context,valid());MainActivity activity=activity();assertEquals(View.GONE,find(activity,"setup.panel").getVisibility());assertEquals(View.VISIBLE,find(activity,"pairing.panel").getVisibility());assertEquals(View.GONE,find(activity,"login.panel").getVisibility());assertNull(find(activity,"login.signup"));}
    @Test public void runtimeSetupPersistsAndChangesToFamilyInvite(){MainActivity activity=activity();openSetup(activity);((EditText)find(activity,"setup.family")).setText("family_test_01");((EditText)find(activity,"setup.project")).setText("demo-family-call");((EditText)find(activity,"setup.appId")).setText("1:123456:android:abcdef");((EditText)find(activity,"setup.apiKey")).setText(ConfigDataTest.KEY);((EditText)find(activity,"setup.database")).setText("https://demo-family-call-default-rtdb.firebaseio.com");find(activity,"setup.save").performClick();assertEquals(View.VISIBLE,find(activity,"pairing.panel").getVisibility());assertEquals(View.GONE,find(activity,"setup.panel").getVisibility());}
    @Test public void invalidSetupCannotAdvance(){MainActivity activity=activity();openSetup(activity);((EditText)find(activity,"setup.apiKey")).setText("");find(activity,"setup.save").performClick();assertEquals(View.VISIBLE,find(activity,"setup.panel").getVisibility());assertFalse(((TextView)find(activity,"setup.error")).getText().toString().isEmpty());}
    @Test public void ownerRecoveryCanBeDismissedWithoutCreatingAccount() throws Exception {AppConfig.save(context,valid());MainActivity activity=activity();find(activity,"pairing.recovery").performClick();assertEquals(View.VISIBLE,find(activity,"login.panel").getVisibility());assertNull(find(activity,"login.signup"));buttonNamed(activity.getWindow().getDecorView(),"Back to family invite").performClick();assertEquals(View.GONE,find(activity,"login.panel").getVisibility());assertEquals(View.VISIBLE,find(activity,"pairing.panel").getVisibility());}
    @Test public void malformedCodeDoesNotCreateAnonymousAccount() throws Exception {AppConfig.save(context,valid());MainActivity activity=activity();((EditText)find(activity,"pairing.code")).setText("123");find(activity,"pairing.join").performClick();assertTrue(((TextView)find(activity,"pairing.status")).getText().toString().contains("six digits"));assertNull(com.google.firebase.auth.FirebaseAuth.getInstance(AppConfig.load(context).firebase).getCurrentUser());}
    @Test public void standardGoogleConfigurationImportsWithoutCredentials() throws Exception {
        String json="{\"project_info\":{\"project_id\":\"demo-family-call\",\"firebase_url\":\"https://demo.firebaseio.com\"},\"client\":[{\"client_info\":{\"mobilesdk_app_id\":\"1:123:android:abc\",\"android_client_info\":{\"package_name\":\"com.myfamcall.app\"}},\"api_key\":[{\"current_key\":\""+ConfigDataTest.KEY+"\"}]}]}";
        ConfigData parsed=AppConfig.readImport(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),"family_test");assertEquals("family_test",parsed.familyId);assertEquals("demo-family-call",parsed.projectId);
    }
    @Test public void rejectsAdminCredentialFile() throws Exception {try{AppConfig.parse("{\"type\":\"service_account\",\"private_key\":\"DO_NOT_IMPORT\"}","family_test");fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Do not import"));}}
    @Test public void rejectsOversizeImport() throws Exception {try{AppConfig.readImport(new ByteArrayInputStream(new byte[AppConfig.MAX_IMPORT_BYTES+1]),"family_test");fail();}catch(IllegalArgumentException expected){}}
}
