using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace AirPodsBleTray;

/// <summary>
/// Draws the tray icon: a ring filled clockwise to the battery level.
///
/// Drawing circular arcs with GDI+ directly at 16x16 comes out visibly lumpy, because there are
/// barely enough pixels for the anti-aliasing to work with. Everything here is therefore drawn
/// at several times the target size and then resampled down, which is what actually makes the
/// ring look round.
///
/// Two other details matter. The HICON from Bitmap.GetHicon() is unmanaged and is NOT released
/// by the managed Icon wrapper, so creating one per update leaks a GDI handle every second until
/// the process dies. And the caller must only rebuild when the level changes, since the tray
/// repaints on every assignment.
/// </summary>
internal static class BatteryIcon
{
    private const int Supersample = 4;

    private static readonly Color HighColor = Color.FromArgb(0x3C, 0xC8, 0x50);
    private static readonly Color MidColor = Color.FromArgb(0xF0, 0xB4, 0x28);
    private static readonly Color LowColor = Color.FromArgb(0xE6, 0x46, 0x46);

    // Neutral mid grey reads acceptably on both the dark and the light taskbar, unlike a
    // white or black ring which disappears on one of them.
    private static readonly Color TrackColor = Color.FromArgb(150, 128, 128, 128);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyIcon(IntPtr handle);

    public static Icon Create(int? level, int size)
    {
        int hi = size * Supersample;

        using var large = new Bitmap(hi, hi, PixelFormat.Format32bppArgb);
        using (Graphics graphics = Graphics.FromImage(large))
        {
            graphics.SmoothingMode = SmoothingMode.AntiAlias;
            graphics.Clear(Color.Transparent);

            float thickness = Math.Max(Supersample, hi / 7f);
            float inset = thickness / 2f;
            var bounds = new RectangleF(inset, inset, hi - thickness, hi - thickness);

            using var track = new Pen(TrackColor, thickness);
            graphics.DrawEllipse(track, bounds);

            if (level is { } percent and > 0)
            {
                using var arc = new Pen(ColorFor(percent), thickness)
                {
                    StartCap = LineCap.Round,
                    EndCap = LineCap.Round,
                };

                // Start at 12 o'clock and sweep clockwise.
                graphics.DrawArc(arc, bounds, -90f, 360f * percent / 100f);
            }
        }

        using var bitmap = new Bitmap(size, size, PixelFormat.Format32bppArgb);
        using (Graphics graphics = Graphics.FromImage(bitmap))
        {
            graphics.CompositingMode = CompositingMode.SourceCopy;
            graphics.InterpolationMode = InterpolationMode.HighQualityBicubic;
            graphics.PixelOffsetMode = PixelOffsetMode.HighQuality;
            graphics.Clear(Color.Transparent);
            graphics.DrawImage(large, new Rectangle(0, 0, size, size));
        }

        IntPtr handle = bitmap.GetHicon();
        try
        {
            // Clone so the returned Icon owns managed memory, then release the unmanaged handle.
            using var temporary = Icon.FromHandle(handle);
            return (Icon)temporary.Clone();
        }
        finally
        {
            DestroyIcon(handle);
        }
    }

    private static Color ColorFor(int percent) => percent switch
    {
        < 20 => LowColor,
        < 50 => MidColor,
        _ => HighColor,
    };
}
