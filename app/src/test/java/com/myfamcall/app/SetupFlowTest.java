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
    @Test public void freshInstallHasActualSetupControls(){MainActivity activity=activity();assertEquals(View.VISIBLE,find(activity,"setup.panel").getVisibility());assertNotNull(find(activity,"setup.family"));assertEquals(View.GONE,find(activity,"login.panel").getVisibility());}
    @Test public void savedConfigShowsRealLoginWithoutRecompile() throws Exception {AppConfig.save(context,valid());MainActivity activity=activity();assertEquals(View.GONE,find(activity,"setup.panel").getVisibility());assertEquals(View.VISIBLE,find(activity,"login.panel").getVisibility());assertNotNull(find(activity,"login.signup"));}
    @Test public void runtimeSetupPersistsAndChangesToLogin(){MainActivity activity=activity();((EditText)find(activity,"setup.family")).setText("family_test_01");((EditText)find(activity,"setup.project")).setText("demo-family-call");((EditText)find(activity,"setup.appId")).setText("1:123456:android:abcdef");((EditText)find(activity,"setup.apiKey")).setText(ConfigDataTest.KEY);((EditText)find(activity,"setup.database")).setText("https://demo-family-call-default-rtdb.firebaseio.com");find(activity,"setup.save").performClick();assertEquals(View.VISIBLE,find(activity,"login.panel").getVisibility());assertEquals(View.GONE,find(activity,"setup.panel").getVisibility());}
    @Test public void invalidSetupCannotAdvance(){MainActivity activity=activity();find(activity,"setup.save").performClick();assertEquals(View.VISIBLE,find(activity,"setup.panel").getVisibility());assertFalse(((TextView)find(activity,"setup.error")).getText().toString().isEmpty());}
    @Test public void signupDialogRejectsPasswordMismatchWithoutNetwork() throws Exception {AppConfig.save(context,valid());MainActivity activity=activity();find(activity,"login.signup").performClick();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);((EditText)dialog.findViewById(android.R.id.content).findViewWithTag("signup.email")).setText("person@example.com");((EditText)dialog.findViewById(android.R.id.content).findViewWithTag("signup.password")).setText("LongUniquePassword");((EditText)dialog.findViewById(android.R.id.content).findViewWithTag("signup.confirm")).setText("DifferentPassword");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue("Invalid signup must leave the dialog open",dialog.isShowing());TextView error=dialog.findViewById(android.R.id.content).findViewWithTag("signup.error");assertTrue("Expected mismatch message, got: "+error.getText(),error.getText().toString().contains("do not match"));dialog.dismiss();}
    @Test public void standardGoogleConfigurationImportsWithoutCredentials() throws Exception {
        String json="{\"project_info\":{\"project_id\":\"demo-family-call\",\"firebase_url\":\"https://demo.firebaseio.com\"},\"client\":[{\"client_info\":{\"mobilesdk_app_id\":\"1:123:android:abc\",\"android_client_info\":{\"package_name\":\"com.myfamcall.app\"}},\"api_key\":[{\"current_key\":\""+ConfigDataTest.KEY+"\"}]}]}";
        ConfigData parsed=AppConfig.readImport(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),"family_test");assertEquals("family_test",parsed.familyId);assertEquals("demo-family-call",parsed.projectId);
    }
    @Test public void rejectsAdminCredentialFile() throws Exception {try{AppConfig.parse("{\"type\":\"service_account\",\"private_key\":\"DO_NOT_IMPORT\"}","family_test");fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Do not import"));}}
    @Test public void rejectsOversizeImport() throws Exception {try{AppConfig.readImport(new ByteArrayInputStream(new byte[AppConfig.MAX_IMPORT_BYTES+1]),"family_test");fail();}catch(IllegalArgumentException expected){}}
}
