package com.accessibilitymanager;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActionBar;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityManager;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private SettingsValueChangeContentObserver mContentOb;
    List<AccessibilityServiceInfo> l, tmp;
    ListView listView;
    SharedPreferences sp;
    String settingValue, tmpSettingValue, daemon, top;
    boolean night = true;
    PackageManager pm;
    boolean perm = false;
    private boolean listenerAdded = false;

    //自定义一个内容监视器
    class SettingsValueChangeContentObserver extends ContentObserver {
        public SettingsValueChangeContentObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            //更新settingValue，并与APP内的tmpsettingValue作比对。如果不同，则说明本次设置项改变来自APP外部，于是刷新一下主界面的列表。相同则说明这次改变就是本APP改的，无需处理。
            settingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue == null) settingValue = "";
            if (!settingValue.equals(tmpSettingValue))
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        int firstPosition = listView.getFirstVisiblePosition();
                        int lastPosition = listView.getLastVisiblePosition();
                        for (int i = firstPosition; i <= lastPosition; i++) {
                            View view = listView.getChildAt(i - firstPosition);
                            String[] packageName = Pattern.compile("/").split(tmp.get(i).getId());
                            boolean isChecked = settingValue.contains(packageName[0] + "/" + packageName[1]) || settingValue.contains(packageName[0] + "/" + packageName[0] + packageName[1]);
                            (view.findViewById(R.id.ib)).setVisibility(isChecked ? View.VISIBLE : View.INVISIBLE);
                            ((Switch) view.findViewById(R.id.s)).setChecked(isChecked);
                        }
                    }
                });
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //根据系统深色模式自动切换主题，同时存储下来深色模式的状态
        if ((getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_NO) == Configuration.UI_MODE_NIGHT_NO) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            night = false;
        }

        setContentView(R.layout.activity_main);

        //设置导航栏透明，UI会好看些
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            window.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        setTitle("无障碍管理");
        //注册shizuku授权结果监听器
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkPermission()) {
            listenerAdded = true;
            Shizuku.addRequestPermissionResultListener(RL);
        }


        pm = getPackageManager();
        //注册设置项改变的监听器，用于实时更新APP内显示的各个无障碍服务的状态
        mContentOb = new SettingsValueChangeContentObserver();
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), true, mContentOb);

        //获取本机安装的无障碍服务列表，包括开启的和未开启的都有
        l = ((AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE)).getInstalledAccessibilityServiceList();
        sp = getSharedPreferences("data", 0);

        //读取用户设置“是否隐藏后台”，并进行隐藏后台
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            ((ActivityManager) getSystemService(Service.ACTIVITY_SERVICE)).getAppTasks().get(0).setExcludeFromRecents(sp.getBoolean("hide", true));

        daemon = sp.getString("daemon", "");
        top = sp.getString("top", "");
        Sort();


        listView = findViewById(R.id.list);


        //获得当前开启的无障碍服务列表
        settingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (settingValue == null) settingValue = "";
        tmpSettingValue = settingValue;


        //初次使用触发


        if (sp.getBoolean("first", true)) {
            new AlertDialog.Builder(this)
                    .setTitle("隐私政策")
                    .setMessage("本应用不会收集或记录您的任何信息，也不包含任何联网功能。继续使用则代表您同意上述隐私政策。")
                    .setPositiveButton("OK", null).create().show();
            sp.edit().putBoolean("first", false).apply();
        }


        //如果设备一次都没打开过无障碍设置界面，则下面这个设置项值不存在，同时本APP是无法获取到无障碍设置列表的。所以要在这里加个判断，如果从来没开启过，则需要本APP来给这个设置项写入1来开启。
        if (Settings.Secure.getString(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED) != null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    listView.setAdapter(new adapter(tmp));
                }
            });
            for (int i = 0; i < l.size(); i++) {
                AccessibilityServiceInfo info = l.get(i);
                if (daemon.contains(info.getId())) {
                    StartForeGroundDaemon();
                }
            }

        } else {
            new AlertDialog.Builder(this).setMessage("您的设备尚未启用无障碍服务功能。您可以选择在系统设置-无障碍-打开或关闭任意服务项来激活系统的无障碍服务功能，也可以授权本APP安全设置写入权限以解决.")
                    .setNegativeButton("root激活", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            Process p;
                            try {
                                p = Runtime.getRuntime().exec("su");
                                DataOutputStream o = new DataOutputStream(p.getOutputStream());
                                o.writeBytes("pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS\nexit\n");
                                o.flush();
                                o.close();
                                p.waitFor();
                                if (p.exitValue() == 0) {
                                    Toast.makeText(MainActivity.this, "成功激活", Toast.LENGTH_SHORT).show();
                                }
                            } catch (IOException | InterruptedException ignored) {
                                Toast.makeText(MainActivity.this, "激活失败", Toast.LENGTH_SHORT).show();
                            }
                        }
                    })
                    .setPositiveButton("复制命令", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("c", "adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS"));
                            Toast.makeText(MainActivity.this, "命令已复制到剪切板", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNeutralButton("Shizuku激活", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) check();
                        }
                    })
                    .create().show();
            try {
                Settings.Secure.putString(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            } catch (Exception ignored) {
            }
        }
    }

    private void Sort() {
        tmp = new ArrayList<>(l);
        Collections.sort(tmp, new Comparator<AccessibilityServiceInfo>() {
            @Override
            public int compare(AccessibilityServiceInfo info1, AccessibilityServiceInfo info2) {
                String id = info1.getId();
                String id2 = info2.getId();
                if (top.contains(id2)) {
                    return (!top.contains(id) || top.indexOf(id) <= top.indexOf(id2)) ? 1 : -1;
                } else if (top.contains(id)) {
                    return -1;
                }
                return 0;
            }
        });
    }


    //返回键退出APP，用于适配安卓12和高于12的系统上返回键默认仅把APP放后台的问题。
    @Override
    public void onBackPressed() {
        finish();
        super.onBackPressed();
    }

    private final Shizuku.OnRequestPermissionResultListener RL = (requestCode, grantResult) -> check();

    //检查Shizuku权限，申请Shizuku权限的函数
    private void check() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
            return;
        boolean b = true, c = false;
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                Shizuku.requestPermission(0);
            else c = true;
        } catch (Exception e) {
            if (checkSelfPermission("moe.shizuku.manager.permission.API_V23") == PackageManager.PERMISSION_GRANTED)
                c = true;
            if (e.getClass() == IllegalStateException.class) {
                b = false;
                Toast.makeText(this, "shizuku未运行", Toast.LENGTH_SHORT).show();
            }

        }
        if (b && c) {
            try {
                Process p = Shizuku.newProcess(new String[]{"sh"}, null, null);
                OutputStream out = p.getOutputStream();
                out.write(("pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS\nexit\n").getBytes());
                out.flush();
                out.close();
                p.waitFor();
                if (p.exitValue() == 0) {
                    Toast.makeText(this, "成功激活", Toast.LENGTH_SHORT).show();
                }
            } catch (IOException | InterruptedException ioException) {
                Toast.makeText(this, "激活失败", Toast.LENGTH_SHORT).show();
            }
        }

    }


    //一些收尾工作，取消注册监听器什么的
    @Override
    protected void onDestroy() {
        if (listenerAdded) Shizuku.removeRequestPermissionResultListener(RL);

        getContentResolver().unregisterContentObserver(mContentOb);
        super.onDestroy();
    }


    @Override // android.app.Activity
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.boot).setChecked(sp.getBoolean("boot", true));
        menu.findItem(R.id.toast).setChecked(sp.getBoolean("toast", true));
        menu.findItem(R.id.hide).setChecked(sp.getBoolean("hide", true));
        return super.onPrepareOptionsMenu(menu);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.arrange, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onMenuItemSelected(int i, MenuItem menuItem) {
        int itemId = menuItem.getItemId();
        if (itemId == R.id.boot) {
            sp.edit().putBoolean("boot", !menuItem.isChecked()).apply();
            menuItem.setChecked(!menuItem.isChecked());
        } else if (itemId == R.id.toast) {
            sp.edit().putBoolean("toast", !menuItem.isChecked()).apply();
            menuItem.setChecked(!menuItem.isChecked());
        } else if (itemId == R.id.hide) {
            sp.edit().putBoolean("hide", !menuItem.isChecked()).apply();
            menuItem.setChecked(!menuItem.isChecked());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ((ActivityManager) getSystemService(Service.ACTIVITY_SERVICE)).getAppTasks().get(0).setExcludeFromRecents(sp.getBoolean("hide", true));
            }
        }
        return super.onMenuItemSelected(i, menuItem);
    }

    //这个是用于适配列表中的每一项设置项的显示
    public class adapter extends BaseAdapter {
        private final List<AccessibilityServiceInfo> list;


        public adapter(List<AccessibilityServiceInfo> list) {
            super();
            this.list = list;
        }

        public int getCount() {
            return list.size();
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item, null);
            holder = new ViewHolder();
            holder.texta = convertView.findViewById(R.id.a);
            holder.textb = convertView.findViewById(R.id.b);
            holder.imageView = convertView.findViewById(R.id.c);
            holder.sw = convertView.findViewById(R.id.s);
            holder.ib = convertView.findViewById(R.id.ib);
            convertView.setTag(holder);
            AccessibilityServiceInfo info = list.get(position);
            String serviceName = info.getId();
            String[] packageName = Pattern.compile("/").split(serviceName);
            Drawable icon = null;
            String Packagelabel = null;
            String ServiceLabel = null;
            String Description = null;
            try {
                icon = pm.getApplicationIcon(packageName[0]);
                Packagelabel = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(packageName[0], PackageManager.GET_META_DATA)));
                ServiceLabel = pm.getServiceInfo(new ComponentName(packageName[0], packageName[0] + packageName[1]), PackageManager.MATCH_DEFAULT_ONLY).loadLabel(pm).toString();
                Description = info.loadDescription(pm);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            if (ServiceLabel == null) ServiceLabel = Packagelabel;
            holder.imageView.setImageDrawable(icon);
            holder.textb.setText(Packagelabel.equals(ServiceLabel) ? ServiceLabel : String.format("%s/%s", Packagelabel, ServiceLabel));
            holder.texta.setText(Description == null || Description.length() == 0 ? "该服务没有描述" : Description);


            holder.ib.setImageResource(daemon.contains(serviceName) ? R.drawable.lock1 : R.drawable.lock);
//            holder.sw.setEnabled(!daemon.contains(serviceName));
            holder.ib.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (checkPermission()) {
                        createPermissionDialog();
                        return;
                    }
                    daemon = daemon.contains(serviceName) ? daemon.replace(serviceName + ":", "") : serviceName + ":" + daemon;
                    sp.edit().putString("daemon", daemon).apply();
                    holder.ib.setImageResource(daemon.contains(serviceName) ? R.drawable.lock1 : R.drawable.lock);
//                    holder.sw.setEnabled(!daemon.contains(serviceName));
                    StartForeGroundDaemon();
                }
            });
            holder.sw.setChecked(settingValue.contains(packageName[0] + "/" + packageName[1]) || settingValue.contains(packageName[0] + "/" + packageName[0] + packageName[1]));
            holder.ib.setVisibility(holder.sw.isChecked() ? View.VISIBLE : View.INVISIBLE);
            holder.sw.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (checkPermission()) {
                        createPermissionDialog();
                        holder.sw.setChecked(!holder.sw.isChecked());
                    } else {

                        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                        if (s == null) s = "";

                        if (holder.sw.isChecked())
                            tmpSettingValue = serviceName + ":" + s;
                        else
                            tmpSettingValue = s.replace(serviceName + ":", "").replace(packageName[0] + "/" + packageName[0] + packageName[1] + ":", "").replace(serviceName, "").replace(packageName[0] + "/" + packageName[0] + packageName[1], "").replace(serviceName, "");

                        Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, tmpSettingValue);
                        holder.ib.setVisibility(holder.sw.isChecked() ? View.VISIBLE : View.INVISIBLE);

                    }
                }
            });


            //点击某个项目的空白处将展示该服务的详细信息，下面的代码是解析各类FLAG的，挺麻烦，不过没别的方法。
            convertView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                    int fb = info.feedbackType;
                    String feedback = "";
                    if ((fb & 32) != 0) feedback += "盲文反馈\n";
                    if ((fb & 16) != 0) feedback += "通用反馈\n";
                    if ((fb & 8) != 0) feedback += "视觉反馈\n";
                    if ((fb & 4) != 0) feedback += "可听（未说出）反馈\n";
                    if ((fb & 2) != 0) feedback += "触觉反馈\n";
                    if ((fb & 1) != 0) feedback += "口头反馈\n";
                    if (feedback.equals("")) feedback = "无\n";


                    int cap = info.getCapabilities();
                    String capa = "";
                    if ((cap & 32) != 0) capa += "执行手势\n";
                    if ((cap & 16) != 0) capa += "控制显示器放大率\n";
                    if ((cap & 8) != 0) capa += "监听和拦截按键事件\n";
                    if ((cap & 4) != 0) capa += "请求增强的Web辅助功能增强功能。 例如，安装脚本以使网页内容更易于访问\n";
                    if ((cap & 2) != 0) capa += "请求触摸探索模式，使触屏操作变成鼠标操作\n";
                    if ((cap & 1) != 0) capa += "读取屏幕内容\n";
                    if (capa.equals("")) capa = "无\n";

                    int eve = info.eventTypes;
                    String event = "";
                    if ((eve & 33554432) != 0) event += "当前正在阅读用户屏幕上下文的助理事件\n";
                    if ((eve & 16777216) != 0) event += "点击控件上下文的事件\n";
                    if ((eve & 8388608) != 0) event += "窗口更改的事件\n";
                    if ((eve & 4194304) != 0) event += "用户结束触摸屏幕的事件\n";
                    if ((eve & 2097152) != 0) event += "用户开始触摸屏幕的事件\n";
                    if ((eve & 1048576) != 0) event += "结束手势检测的事件\n";
                    if ((eve & 524288) != 0) event += "开始手势检测事件\n";
                    if ((eve & 262144) != 0) event += "遍历视图文本事件\n";
                    if ((eve & 131072) != 0) event += "清除可访问性焦点事件\n";
                    if ((eve & 65536) != 0) event += "获得可访问性焦点的事件\n";
                    if ((eve & 32768) != 0) event += "发布公告的应用程序的事件\n";
                    if ((eve & 16384) != 0) event += "更改选中文本的事件\n";
                    if ((eve & 8192) != 0) event += "滚动视图的事件\n";
                    if ((eve & 4096) != 0) event += "窗口内容更改的事件\n";
                    if ((eve & 2048) != 0) event += "结束触摸探索手势的事件\n";
                    if ((eve & 1024) != 0) event += "开始触摸探索手势的事件\n";
                    if ((eve & 512) != 0) event += "控件结束文字输入事件\n";
                    if ((eve & 256) != 0) event += "控件接受文字输入事件\n";
                    if ((eve & 128) != 0) event += "通知状态改变的事件\n";
                    if ((eve & 64) != 0) event += "窗口状态更改的事件\n";
                    if ((eve & 32) != 0) event += "文本框的文字改变事件\n";
                    if ((eve & 16) != 0) event += "控件获得焦点的事件\n";
                    if ((eve & 8) != 0) event += "控件被选取的事件\n";
                    if ((eve & 4) != 0) event += "长按控件的事件\n";
                    if ((eve & 2) != 0) event += "点击控件的事件\n";
                    if (event.equals("")) event = "无\n";


                    String range = info.packageNames == null ? "全局生效" : Arrays.toString(info.packageNames).replace("[", "").replace("]", "").replace(", ", "\n").replace(",", "\n");

                    int fg = info.flags;
                    String flag = "";
                    if ((fg & 64) != 0) flag += "访问所有交互式窗口的内容\n";
                    if ((fg & 32) != 0) flag += "监听和拦截按键事件\n";
                    if ((fg & 16) != 0) flag += "获取屏幕视图上所有控件的ID\n";
                    if ((fg & 8) != 0) flag += "启用Web可访问性增强扩展\n";
                    if ((fg & 4) != 0) flag += "要求系统进入触摸探索模式\n";
                    if ((fg & 2) != 0) flag += "查询窗口中的不重要内容\n";
                    if ((fg & 1) != 0) flag += "默认\n";
                    if (flag.equals("")) flag = "无\n";


                    try {
                        final ScrollView scrollView = new ScrollView(MainActivity.this);
                        final TextView textView = new TextView(MainActivity.this);
                        textView.setTextIsSelectable(true);
                        textView.setPadding(40, 20, 40, 20);
                        textView.setTextSize(18f);
                        textView.setAlpha(0.8f);
                        textView.setTextColor(night ? Color.WHITE : Color.BLACK);
                        textView.setText(String.format("服务类名：\n%s\n\n特殊能力：\n%s\n生效范围：\n%s\n\n反馈方式：\n%s\n捕获事件类型：\n%s\n特殊标志：\n%s", serviceName, capa, range, feedback, event, flag));
                        scrollView.addView(textView);
                        if (info.getSettingsActivityName() != null && info.getSettingsActivityName().length() > 0)
                            builder.setNegativeButton("设置", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    try {
                                        startActivity(new Intent().setComponent(new ComponentName(packageName[0], info.getSettingsActivityName())));
                                    } catch (Exception ignored) {
                                    }
                                }
                            });

                        builder
                                .setIcon(pm.getApplicationIcon(packageName[0]))
                                .setView(scrollView).setTitle("服务详细信息")
                                .setPositiveButton("知道了", null)
                                .create().show();
                    } catch (Exception ignored) {
                    }
                }
            });
            String finalServiceLabel = ServiceLabel;
            convertView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    if (!top.contains(serviceName)) {
                        top = serviceName + ":" + top;
                        Toast.makeText(MainActivity.this, "已将" + finalServiceLabel + "置顶", Toast.LENGTH_SHORT).show();
                    } else {
                        top = top.replace(serviceName + ":", "");
                        Toast.makeText(MainActivity.this, "已将" + finalServiceLabel + "取消置顶", Toast.LENGTH_SHORT).show();
                    }
                    sp.edit().putString("top", top).apply();
                    Sort();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            listView.setAdapter(new adapter(tmp));
                        }
                    });
                    return true;
                }
            });
            if (top.contains(serviceName))
                convertView.setBackgroundColor(night ? Color.DKGRAY : Color.LTGRAY);
            return convertView;
        }

        private void createPermissionDialog() {
            String cmd = "pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage("安卓5.1和更低版本的设备，需将本APP转换为系统应用。\n\n安卓6.0及更高版本的设备，在下面三个方法中任选一个均可：\n1.连接电脑USB调试后在电脑CMD执行以下命令：\nadb shell " + cmd + "\n\n2.root激活。\n\n3.Shizuku激活。")
                    .setTitle("需要安全设置写入权限")
                    .setPositiveButton("复制命令", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("c", "adb shell " + cmd));
                            Toast.makeText(MainActivity.this, "命令已复制到剪切板", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("root激活", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialoginterface, int i) {
                            Process p;
                            try {
                                p = Runtime.getRuntime().exec("su");
                                DataOutputStream o = new DataOutputStream(p.getOutputStream());
                                o.writeBytes(cmd + "\nexit\n");
                                o.flush();
                                o.close();
                                p.waitFor();
                                if (p.exitValue() == 0) {
                                    Toast.makeText(MainActivity.this, "成功激活", Toast.LENGTH_SHORT).show();
                                }
                            } catch (IOException | InterruptedException ignored) {
                                Toast.makeText(MainActivity.this, "激活失败", Toast.LENGTH_SHORT).show();
                            }
                        }
                    })
                    .setNeutralButton("Shizuku激活", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) check();
                        }
                    })
                    .create().show();
        }

        class ViewHolder {

            TextView texta;
            TextView textb;
            ImageView imageView;
            Switch sw;
            ImageButton ib;
        }


    }

    //查看APP是否可以写入安全设置
    boolean checkPermission() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            perm = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        else {
            PackageInfo packageInfo = new PackageInfo();
            try {
                packageInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_CONFIGURATIONS);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            perm = (packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        }
        return !perm;
    }

    //启动前台服务，进行保活!
    void StartForeGroundDaemon() {

        if (checkPermission()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).areNotificationsEnabled()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
            Toast.makeText(this, "请授予通知权限", Toast.LENGTH_SHORT).show();
            return;
        }

        //申请取消电池优化
        if (Build.VERSION.SDK_INT >= 23 && !((PowerManager) getSystemService(Service.POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName()))
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(new Intent(this, daemonService.class));
        else
            startService(new Intent(this, daemonService.class));

    }


}