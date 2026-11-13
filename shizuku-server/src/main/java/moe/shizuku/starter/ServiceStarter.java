package moe.shizuku.starter;

import android.content.IContentProvider;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;

import java.util.Locale;

import moe.shizuku.api.BinderContainer;
import moe.shizuku.server.IShizukuService;
import moe.shizuku.starter.util.IContentProviderCompat;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.ServerConstants;
import rikka.shizuku.server.UserService;

public class ServiceStarter {

    private static final String TAG = "ShizukuServiceStarter";

    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";

    public static final String DEBUG_ARGS;

    static {
        int sdk = Build.VERSION.SDK_INT;
        if (sdk >= 30) {
            DEBUG_ARGS = "-Xcompiler-option" + " --debuggable" +
                    " -XjdwpProvider:adbconnection" +
                    " -XjdwpOptions:suspend=n,server=y";
        } else if (sdk >= 28) {
            DEBUG_ARGS = "-Xcompiler-option" + " --debuggable" +
                    " -XjdwpProvider:internal" +
                    " -XjdwpOptions:transport=dt_android_adb,suspend=n,server=y";
        } else {
            DEBUG_ARGS = "-Xcompiler-option" + " --debuggable" +
                    " -agentlib:jdwp=transport=dt_android_adb,suspend=n,server=y";
        }
    }

    private static final String USER_SERVICE_CMD_FORMAT = "(CLASSPATH='%s' %s%s /system/bin " +
            "--nice-name='%s' moe.shizuku.starter.ServiceStarter " +
            "--token='%s' --package='%s' --class='%s' --uid=%d%s)&";

    // DeathRecipient will automatically be unlinked when all references to the
    // binder is dropped, so we hold the reference here.
    @SuppressWarnings("FieldCanBeLocal")
    private static IBinder shizukuBinder;

    public static String commandForUserService(String appProcess, String managerApkPath, String token, String packageName, String classname, String processNameSuffix, int callingUid, boolean debug) {
        String processName = String.format("%s:%s", packageName, processNameSuffix);
        return String.format(Locale.ENGLISH, USER_SERVICE_CMD_FORMAT,
                managerApkPath, appProcess, debug ? (" " + DEBUG_ARGS) : "",
                processName,
                token, packageName, classname, callingUid, debug ? (" " + "--debug-name=" + processName) : "");
    }

    public static void main(String[] args) {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }

        IBinder service;
        String token;

        UserService.setTag(TAG);
        Pair<IBinder, String> result = UserService.create(args);

        if (result == null) {
            System.exit(1);
            return;
        }

        service = result.first;
        token = result.second;

        if (!sendBinder(service, token)) {
            System.exit(1);
        }

        Looper.loop();
        System.exit(0);

        Log.i(TAG, "service exited");
    }

    private static boolean sendBinder(IBinder binder, String token) {
        return sendBinder(binder, token, true);
    }

    private static boolean sendBinder(IBinder binder, String token, boolean retry) {
        // MUSE: 内嵌架构下 Muse 自己就是 manager。库里 rikka.shizuku.ShizukuProvider 只处理
        // sendBinder / getBinder 两个 method——上游经 manager app 中转的 sendUserService 链路
        // 在 fork 里不存在（App 端没有对应实现，call 会得到空回复，starter 静默 exit(1)，
        // 表现为 app 侧 30s bind 超时）。因此 starter 直接经 getBinder 取回 server binder，
        // 再调 server.attachUserService 完成回传；token 匹配在 server 侧 UserServiceManager。
        String packageName = ServerConstants.MANAGER_APPLICATION_ID;
        String name = packageName + ".shizuku";
        int userId = 0;
        IContentProvider provider = null;

        try {
            provider = ActivityManagerApis.getContentProviderExternal(name, userId, null, name);
            if (provider == null) {
                Log.e(TAG, String.format("provider is null %s %d", name, userId));
                return false;
            }
            if (!provider.asBinder().pingBinder()) {
                Log.e(TAG, String.format("provider is dead %s %d", name, userId));

                if (retry) {
                    // For unknown reason, sometimes this could happens
                    // Kill Shizuku app and try again could work
                    ActivityManagerApis.forceStopPackageNoThrow(packageName, userId);
                    Log.e(TAG, String.format("kill %s in user %d and try again", packageName, userId));
                    Thread.sleep(1000);
                    return sendBinder(binder, token, false);
                }
                return false;
            }

            if (!retry) {
                Log.e(TAG, "retry works");
            }

            Bundle reply = IContentProviderCompat.call(provider, null, null, name, "getBinder", null, null);

            if (reply != null) {
                reply.setClassLoader(BinderContainer.class.getClassLoader());

                BinderContainer container = reply.getParcelable(EXTRA_BINDER);

                if (container != null && container.binder != null && container.binder.pingBinder()) {
                    shizukuBinder = container.binder;
                    shizukuBinder.linkToDeath(() -> {
                        Log.i(TAG, "exiting...");
                        System.exit(0);
                    }, 0);

                    Bundle options = new Bundle();
                    options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, token);
                    IShizukuService.Stub.asInterface(container.binder).attachUserService(binder, options);

                    Log.i(TAG, String.format("user service binder attached to server via %s in user %d", packageName, userId));
                    return true;
                } else {
                    Log.w(TAG, "server binder not received");
                }
            }

            return false;
        } catch (Throwable tr) {
            Log.e(TAG, String.format("failed send binder to %s in user %d", packageName, userId), tr);
            return false;
        } finally {
            if (provider != null) {
                try {
                    ActivityManagerApis.removeContentProviderExternal(name, null);
                } catch (Throwable tr) {
                    Log.w(TAG, "removeContentProviderExternal", tr);
                }
            }
        }
    }
}
