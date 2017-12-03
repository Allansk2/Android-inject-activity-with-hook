package android.tinker.com.myapplication;

import android.app.Application;

/**
 * Created by Regina on 2017-12-02.
 */

public class MyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        HookAmsUtil amsUtil = new HookAmsUtil(ProxyActivity.class, this);
        amsUtil.hookAms();
        amsUtil.hookSystemHandler();
        amsUtil.onHookIPackageManager();

    }
}
