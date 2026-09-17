using System.Diagnostics;
using System.Windows.Forms;

namespace AirPodsBleTray;

internal sealed class TrayApp : ApplicationContext
{
    private readonly NotifyIcon _icon;
    private readonly AirPodsWatcher _watcher;
    private readonly System.Windows.Forms.Timer _refresh;
    private AirPodsSnapshot _latest;
    private int? _renderedLevel;

    public TrayApp()
    {
        var menu = new ContextMenuStrip();
        menu.Items.Add("查看原始数据…", null, (_, _) => ShowRaw());
        menu.Items.Add("打开日志目录", null, (_, _) => OpenLogFolder());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("退出", null, (_, _) => Shutdown());

        _icon = new NotifyIcon
        {
            Icon = BatteryIcon.Create(null, SystemInformation.SmallIconSize.Width),
            Text = "AirPods 电量：正在扫描…",
            Visible = true,
            ContextMenuStrip = menu,
        };

        _watcher = new AirPodsWatcher();
        _watcher.Start();
        _latest = _watcher.Snapshot();

        // Poll on the UI thread rather than marshalling from the watcher's threads.
        _refresh = new System.Windows.Forms.Timer { Interval = 1000 };
        _refresh.Tick += (_, _) => Refresh();
        _refresh.Start();
    }

    private void Refresh()
    {
        _latest = _watcher.Snapshot();
        _icon.Text = BuildTooltip(_latest);

        // Rebuilding the icon repaints the tray, so only do it when the level actually moved.
        int? level = PickLevel(_latest);
        if (level != _renderedLevel)
        {
            _renderedLevel = level;
            Icon previous = _icon.Icon;
            _icon.Icon = BatteryIcon.Create(level, SystemInformation.SmallIconSize.Width);
            previous.Dispose();
        }
    }

    // The lower earbud is what matters; the case is only a fallback.
    private static int? PickLevel(AirPodsSnapshot snapshot)
    {
        if (snapshot.Left is { } left && snapshot.Right is { } right)
        {
            return Math.Min(left, right);
        }

        return snapshot.Left ?? snapshot.Right ?? snapshot.Case;
    }

    private static string BuildTooltip(AirPodsSnapshot snapshot)
    {
        if (snapshot.Left is null && snapshot.Right is null && snapshot.Case is null)
        {
            return snapshot.LastSeenAt is { } lastSeen
                ? $"AirPods：暂无广播\n上次读数 {lastSeen:HH:mm:ss}"
                : "AirPods：暂无广播\n请确认耳机已连接本机";
        }

        return $"{snapshot.ModelName}\n" +
               $"左 {Show(snapshot.Left)}{Mark(snapshot.LeftCharging)}   " +
               $"右 {Show(snapshot.Right)}{Mark(snapshot.RightCharging)}   " +
               $"盒 {Show(snapshot.Case)}{Mark(snapshot.CaseCharging)}";
    }

    private static string Show(int? value) => value is null ? "--" : $"{value}%";

    private static string Mark(bool charging) => charging ? "⚡" : string.Empty;

    private void ShowRaw()
    {
        string text = _latest.Freshest is { } advertisement
            ? advertisement.Describe()
            : "还没有收到 AirPods 广播。\n\n请确认耳机已连接到本机。";

        MessageBox.Show(text, "AirPods 原始数据", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    private void OpenLogFolder()
    {
        string folder = Path.GetDirectoryName(_watcher.LogPath)!;
        Directory.CreateDirectory(folder);
        Process.Start(new ProcessStartInfo(folder) { UseShellExecute = true });
    }

    private void Shutdown()
    {
        // Disposing the icon matters: skipping it leaves a ghost in the tray until hovered.
        _refresh.Stop();
        _icon.Visible = false;
        _watcher.Dispose();
        _icon.Dispose();
        ExitThread();
    }
}
