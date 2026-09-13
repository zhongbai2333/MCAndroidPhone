package com.zhongbai233.mcphone.armprobe;
import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.widget.TextView;
/** No x86 native library or Java fallback: PASS requires executing the AArch64 JNI payload. */
public final class MainActivity extends Activity {
    private static native int nativeProbe();
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);String result;
        try {System.loadLibrary("mcphone_arm64_probe");int value=nativeProbe();if(value!=42)throw new AssertionError("Unexpected native result: "+value);result="ARM64 JNI PASS: 42";}
        catch(Throwable error){result="ARM64 JNI FAIL: "+error;}
        android.util.Log.i("MCPhoneArmProbe",result);
        TextView text=new TextView(this);text.setTextSize(20);text.setPadding(24,48,24,24);
        text.setText(result+"\n\nABIs: "+String.join(", ",Build.SUPPORTED_ABIS)+"\n\nThis APK contains only arm64-v8a native code.");setContentView(text);
    }
}
