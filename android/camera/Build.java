import java.io.*;
import java.nio.file.*;
import java.util.*;
/** Local development APK builder. Supply official SDK paths; no downloads or end-user tooling. */
class Build {
    static void run(String... args)throws Exception{var p=new ProcessBuilder(args).inheritIO().start();if(p.waitFor()!=0)throw new IOException("Build failed: "+args[0]);}
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("java android/camera/Build.java android.jar build-tools-directory");
        Path api=Path.of(args[0]).toRealPath(),tools=Path.of(args[1]).toRealPath(),root=Path.of("android/camera").toAbsolutePath(),out=Files.createDirectories(root.resolve("build"));
        Path classes=Files.createDirectories(out.resolve("classes")),dex=Files.createDirectories(out.resolve("dex"));String bin=Path.of(System.getProperty("java.home"),"bin").toString()+File.separator;
        run(bin+"javac","--release","8","-encoding","UTF-8","-cp",api.toString(),"-d",classes.toString(),root.resolve("src/com/zhongbai233/mcandroidphone/camera/CameraActivity.java").toString());
        run(bin+"jar","cf",out.resolve("classes.jar").toString(),"-C",classes.toString(),".");
        run(bin+"java","-cp",tools.resolve("lib/d8.jar").toString(),"com.android.tools.r8.D8","--min-api","29","--lib",api.toString(),"--output",dex.toString(),out.resolve("classes.jar").toString());
        Path unsigned=out.resolve("camera-unsigned.apk"),aligned=out.resolve("camera-aligned.apk"),apk=out.resolve("mcphone-camera.apk");
        run(tools.resolve("aapt2").toString(),"link","-o",unsigned.toString(),"--manifest",root.resolve("AndroidManifest.xml").toString(),"-I",api.toString(),"--min-sdk-version","29","--target-sdk-version","36");
        run(bin+"jar","uf",unsigned.toString(),"-C",dex.toString(),"classes.dex");
        run(tools.resolve("zipalign").toString(),"-f","4",unsigned.toString(),aligned.toString());
        Path key=out.resolve("development.p12");if(!Files.exists(key))run(bin+"keytool","-genkeypair","-keystore",key.toString(),"-storepass","android","-keypass","android","-alias","development","-dname","CN=MCAndroidPhone Local Development","-keyalg","RSA","-keysize","2048","-validity","3650");
        run(bin+"java","-jar",tools.resolve("lib/apksigner.jar").toString(),"sign","--ks",key.toString(),"--ks-pass","pass:android","--out",apk.toString(),aligned.toString());
        run(bin+"java","-jar",tools.resolve("lib/apksigner.jar").toString(),"verify",apk.toString());System.out.println("CAMERA_APK_OK "+apk+" bytes="+Files.size(apk));
    }
}
