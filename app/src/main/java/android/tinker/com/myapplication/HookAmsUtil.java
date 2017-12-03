package android.tinker.com.myapplication;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;


/**
 * Created by Regina on 2017-12-02.
 */

public class HookAmsUtil {

    private final String TAG = this.getClass().getSimpleName();
    private final String OLD_INTENT = "oldIntent";

    private Class<?> proxyActivity;
    private Context context;
    private Object activityThreadValue;  // system activity thread object

    public HookAmsUtil(Class<?> proxyActivity, Context context){
        this.proxyActivity = proxyActivity;
        this.context = context;

    }

    public void hookAms() {
        Log.e(TAG, "start hook ams");
        try {
            Class<?> clazz = Class.forName("android.app.ActivityManager");
            Field seviceField = clazz.getDeclaredField("IActivityManagerSingleton");
            seviceField.setAccessible(true);
            Object serviceValue = seviceField.get(null);
//            int result = ActivityManager.getService()
//                    .startActivity(whoThread, who.getBasePackageName(), intent,
//                            intent.resolveTypeIfNeeded(who.getContentResolver()),
//                            token, target != null ? target.mEmbeddedID : null,
//                            requestCode, 0, null, options);
            //reflect singleton

            Class<?> singletonClazz = Class.forName("android.util.Singleton");
            Field mInstanceField = singletonClazz.getDeclaredField("mInstance");
            mInstanceField.setAccessible(true);
            //get system activity manager object
            Object iActivityManagerObject = mInstanceField.get(serviceValue);

//            // create my own activity manager object
            Class<?> iActivityManagerIntercept = Class.forName("android.app.IActivityManager");
            AmsInvocationHandler handler = new AmsInvocationHandler(iActivityManagerObject);

            Object proxy = Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class<?>[]{iActivityManagerIntercept}, handler);

            mInstanceField.set(serviceValue, proxy);


        } catch (Exception e) {
            e.printStackTrace();
        }

    }



    class AmsInvocationHandler implements InvocationHandler {
        private Object iActivityManagerObject;

        public AmsInvocationHandler(Object iActivityManagerObject) {
            this.iActivityManagerObject = iActivityManagerObject;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

            Log.e(TAG, "AmsInvocationHandler method " + method.getName());
            Log.e(TAG, "AmsInvocationHandler args " + args);
            if ("startActivity".contains(method.getName())) {
                // change the activity
                Intent intent = null;
                int index = 0;
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof Intent){
                        // find start activity intent
                        intent = (Intent) args[i];
                        index = i;
                        break;
                    }
                }

                //            Intent proxxyIntent = new Intent(context, proxyActivity.getClass());
                Intent proxxyIntent = new Intent();
                ComponentName componentName = new ComponentName(context, proxyActivity);
                proxxyIntent.setComponent(componentName);
                proxxyIntent.putExtra(OLD_INTENT, intent);
                args[index] = proxxyIntent;
                return method.invoke(iActivityManagerObject, args);

            }
            return method.invoke(iActivityManagerObject, args);
        }
    }



    // fix package manager crash for AppCompatActivity
    // because in AppCompatActivity, it will check the activity again in PackageManger
    public void onHookIPackageManager() {
        try {

            Class<?> activityThreadClazz = Class.forName("android.app.ActivityThread");
            Field currentActivityThreadField = activityThreadClazz.getDeclaredField("sCurrentActivityThread");
            currentActivityThreadField.setAccessible(true);
            activityThreadValue = currentActivityThreadField.get(null);

            Method getPackageManager = activityThreadValue.getClass().getDeclaredMethod("getPackageManager");
            Object iPackageManager = getPackageManager.invoke(activityThreadValue);

            PackageManagerHandler handler = new PackageManagerHandler(iPackageManager);
            Class<?> iPackageManagerIntercept = Class.forName("android.content.pm.IPackageManager");
            Object proxy = Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                    new Class<?>[]{iPackageManagerIntercept}, handler);

            // replace the package manager
            Field iPackageManagerField = activityThreadValue.getClass().getDeclaredField("sPackageManager");
            iPackageManagerField.setAccessible(true);
            iPackageManagerField.set(activityThreadValue, proxy);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public class PackageManagerHandler implements InvocationHandler {
        public Object iPackageManager;
        public PackageManagerHandler(Object iPackageManager) {
            this.iPackageManager = iPackageManager;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("getActivityInfo".equals(method.getName())){
                for (int i = 0; i < args.length; i++){
                    if (args[i] instanceof ComponentName){
                        ComponentName componentName = new ComponentName(context.getApplicationContext().getPackageName(), ProxyActivity.class.getName());
                        args[i] = componentName;
                    }
                }
            }
            return method.invoke(iPackageManager,args);
        }
    }

    public void hookSystemHandler() {
        try {
            Class<?> activityThreadClazz = Class.forName("android.app.ActivityThread");
            Field currentActivityThreadField = activityThreadClazz.getDeclaredField("sCurrentActivityThread");
            currentActivityThreadField.setAccessible(true);
            activityThreadValue = currentActivityThreadField.get(null);

            Field mHField = activityThreadClazz.getDeclaredField("mH");
            mHField.setAccessible(true);
            // mH object in activity thread
            Handler handler = (Handler) mHField.get(activityThreadValue);

            Field mCallbackField = Handler.class.getDeclaredField("mCallback");
            mCallbackField.setAccessible(true);
            mCallbackField.set(handler, new ActivityThreadHandlerCallBack(handler));


        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    class ActivityThreadHandlerCallBack implements Handler.Callback {
        Handler handler;

        public ActivityThreadHandlerCallBack(Handler handler) {
            this.handler = handler;
        }

        @Override
        public boolean handleMessage(Message msg) {
            // intercept activity thread handle message
            Log.e(TAG, "ActivityThreadHandlerCallBack  handleMessage " + msg.what);

            // change the intent
            if (msg.what == 100) {
                Log.e(TAG, "ActivityThreadHandlerCallBack  LAUNCH_ACTIVITY ");
                handleLaunchActivity(msg);
            }
            handler.handleMessage(msg);
            return true;
        }

        private void handleLaunchActivity(Message msg) {
//            final ActivityClientRecord r = (ActivityClientRecord) msg.obj;
            Object obj = msg.obj;
            try {
                Field intentField = obj.getClass().getDeclaredField("intent");
                intentField.setAccessible(true);
                Intent proxyIntent = (Intent) intentField.get(obj);
                Intent realIntent = proxyIntent.getParcelableExtra(OLD_INTENT);
                if (realIntent != null) {
//                    intentField.set(obj, realIntent);
                    Log.e(TAG, "handleLaunchActivity real intent is not null, so this is an intent that we want to replace, now we should change it back to the old intent");

                    proxyIntent.setComponent(realIntent.getComponent());
                    Log.e(TAG, "after set component");

                }

            } catch (Exception e) {
                e.printStackTrace();
            }


        }
    }









}
