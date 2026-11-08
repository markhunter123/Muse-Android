# app_process 按类名反射入口，AIDL parcel 按类名还原：入口类与容器必须原样保留。
# 当前 app 的 R8 是关闭的（isMinifyEnabled=false），这组规则防未来开启时踩坑。
-keep class rikka.shizuku.server.ShizukuService { public static void main(java.lang.String[]); }
-keep class moe.shizuku.starter.ServiceStarter { public static void main(java.lang.String[]); }
-keep class moe.shizuku.api.BinderContainer { *; }
