package rikka.shizuku.server;

public class ServerConstants {

    public static final int MANAGER_APP_NOT_FOUND = 50;

    public static final String PERMISSION = "moe.shizuku.manager.permission.API_V23";
    // MUSE: manager identity points at Muse itself (embedded server serves only Muse).
    // starter 的 --package 参数会设成 MUSE_MANAGER_PACKAGE 环境变量（debug 构建 .dev 后缀、
    // 未来 fork 都不用改这里）；没设就退回正式包名。
    private static final String ENV_MANAGER_PACKAGE = System.getenv("MUSE_MANAGER_PACKAGE");
    public static final String MANAGER_APPLICATION_ID = (ENV_MANAGER_PACKAGE == null || ENV_MANAGER_PACKAGE.isEmpty())
            ? "io.github.markhunter123.muse" : ENV_MANAGER_PACKAGE;
    public static final String REQUEST_PERMISSION_ACTION = MANAGER_APPLICATION_ID + ".intent.action.REQUEST_PERMISSION";

    public static final int BINDER_TRANSACTION_getApplications = 10001;
}
