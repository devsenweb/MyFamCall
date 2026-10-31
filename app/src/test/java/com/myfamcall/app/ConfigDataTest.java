package com.myfamcall.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class ConfigDataTest {
    static final String KEY="AIzaXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";
    static ConfigData config(String url){return new ConfigData(KEY,"1:123456:android:abcdef","demo-family-call",url,"family_test_01");}
    @Test public void canonicalHttpsDatabaseRoot(){assertEquals("https://demo-family-call-default-rtdb.firebaseio.com",config("https://demo-family-call-default-rtdb.firebaseio.com/").databaseUrl);}
    @Test public void regionalDatabaseAccepted(){assertNotNull(config("https://demo-family-call.asia-southeast1.firebasedatabase.app"));}
    @Test public void cannotImportArbitraryEndpoint(){String[] blocked={"http://demo.firebaseio.com","https://evil.test","https://firebaseio.com.evil.test","https://a.b.firebaseio.com","https://demo.firebaseio.com/path","https://demo.firebaseio.com?x=1","https://user:pass@demo.firebaseio.com","https://demo.firebaseio.com:444","https://demo.firebaseio.com#token"};for(String url:blocked){try{config(url);fail(url);}catch(IllegalArgumentException expected){}}}
    @Test public void familyPathInjectionRejected(){try{new ConfigData(KEY,"1:123:android:abc","demo-family-call","https://demo.firebaseio.com","family/other");fail();}catch(IllegalArgumentException expected){}}
    @Test public void nonAndroidAppIdRejected(){try{new ConfigData(KEY,"1:123:web:abc","demo-family-call","https://demo.firebaseio.com","family_test");fail();}catch(IllegalArgumentException expected){}}
}
