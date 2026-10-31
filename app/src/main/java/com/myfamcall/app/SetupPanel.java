package com.myfamcall.app;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.*;

/** A real configuration entry/import screen, not a simulated calling mode. */
public final class SetupPanel extends LinearLayout {
    public interface Listener { void save(ConfigData data); void importFile(String familyId); void cancel(); }
    private final EditText family,project,appId,key,database;
    private final TextView error;
    private final LinearLayout manual;
    private final Button cancel;
    public SetupPanel(Context context,Listener listener){
        super(context);setOrientation(VERTICAL);setTag("setup.panel");
        TextView title=new TextView(context);title.setText("Connect your private family");title.setTextSize(22);addView(title);
        TextView help=new TextView(context);help.setText("Use only your family's Firebase Spark project. This app never asks for administrator keys or passwords in a setup file.");addView(help);
        family=input("Family ID","setup.family");
        button("Import Firebase configuration",v->listener.importFile(family.getText().toString()));
        button("Enter connection details instead",v->manualVisibility());
        manual=new LinearLayout(context);manual.setOrientation(VERTICAL);addView(manual);
        project=input(manual,"Firebase project ID","setup.project");appId=input(manual,"Android Firebase App ID","setup.appId");
        key=input(manual,"Firebase Web API key","setup.apiKey");database=input(manual,"Realtime Database HTTPS URL","setup.database");
        Button save=new Button(context);save.setText("Save connection");save.setAllCaps(false);save.setTag("setup.save");manual.addView(save);
        save.setOnClickListener(v->{try{listener.save(new ConfigData(key.getText().toString(),appId.getText().toString(),project.getText().toString(),database.getText().toString(),family.getText().toString()));}catch(IllegalArgumentException invalid){showError(invalid.getMessage());}});
        manual.setVisibility(GONE);
        error=new TextView(context);error.setTextColor(0xffa02727);error.setTag("setup.error");addView(error);
        cancel=button("Cancel",v->listener.cancel());cancel.setVisibility(GONE);
    }
    private void manualVisibility(){manual.setVisibility(manual.getVisibility()==VISIBLE?GONE:VISIBLE);}
    private EditText input(String hint,String tag){return input(this,hint,tag);}
    private EditText input(LinearLayout parent,String hint,String tag){EditText e=new EditText(getContext());e.setSingleLine(true);e.setHint(hint);e.setTag(tag);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);parent.addView(e);return e;}
    private Button button(String label,View.OnClickListener action){Button b=new Button(getContext());b.setText(label);b.setAllCaps(false);b.setOnClickListener(action);addView(b);return b;}
    public void showError(String message){error.setText(message);}
    public void populate(ConfigData data){
        cancel.setVisibility(data==null?GONE:VISIBLE);error.setText("");
        if(data==null)return;family.setText(data.familyId);project.setText(data.projectId);appId.setText(data.applicationId);key.setText(data.apiKey);database.setText(data.databaseUrl);
    }
    public void setImportBusy(boolean busy){enableTree(this,!busy);showError(busy?"Reading configuration…":"");}
    private void enableTree(View view,boolean enabled){view.setEnabled(enabled);if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)enableTree(group.getChildAt(i),enabled);}}
}
