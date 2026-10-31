package com.myfamcall.app;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
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
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Optional packaging smoke check: uses a private local asset but never signs up or sends credentials. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class ConfiguredApkSmokeTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private ActivityController<MainActivity> controller;
    @Before public void before() throws Exception {
        AppConfig.clear(context);for(FirebaseApp app:FirebaseApp.getApps(context))app.delete();
        assumeNotNull(AppConfig.load(context));
    }
    @After public void after(){if(controller!=null)controller.pause().stop().destroy();AppConfig.clear(context);for(FirebaseApp app:FirebaseApp.getApps(context))app.delete();}
    @Test public void configuredApkOpensOneTimeFamilyInviteNotEmail(){
        controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();MainActivity activity=controller.get();
        View decor=activity.getWindow().getDecorView();
        assertEquals(View.GONE,decor.findViewWithTag("login.panel").getVisibility());
        assertEquals(View.GONE,decor.findViewWithTag("setup.panel").getVisibility());
        assertEquals(View.VISIBLE,decor.findViewWithTag("pairing.panel").getVisibility());
        assertTrue(decor.findViewWithTag("pairing.join").isEnabled());
        assertNotNull(decor.findViewWithTag("pairing.code"));
        assertNull(decor.findViewWithTag("login.signup"));
    }
}
