namespace AirPodsBleTray;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        using var instanceLock = new Mutex(true, @"Local\AirPodsBleTray", out bool isFirstInstance);

        if (!isFirstInstance)
        {
            MessageBox.Show(
                "AirPods 电量工具已经在运行了，看任务栏托盘。",
                "AirPods 电量",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
            return;
        }

        ApplicationConfiguration.Initialize();
        Application.Run(new TrayApp());
    }
}
