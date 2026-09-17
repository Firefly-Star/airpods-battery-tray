using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace AirPodsBleTray;

/// <summary>
/// The tray identity icon: an AirPods charging case. Static on purpose -- it only has to say
/// "this is the AirPods battery app", and the level is in the tooltip.
///
/// Drawn at eight times the target size and resampled, because the shell asks for 24px on a
/// 150% display and arcs and round corners drawn directly at that size come out visibly lumpy.
/// Handing the shell a larger icon would not help: it scales to the same tray cell anyway, and
/// its scaler is no better than resampling here.
/// </summary>
internal static class CaseIcon
{
    private const int Supersample = 8;

    private static readonly Color Body = Color.FromArgb(235, 235, 235);
    private static readonly Color Rim = Color.FromArgb(110, 0, 0, 0);
    private static readonly Color Detail = Color.FromArgb(150, 0, 0, 0);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyIcon(IntPtr handle);

    public static Icon Create(int size)
    {
        int hi = size * Supersample;

        using var large = new Bitmap(hi, hi, PixelFormat.Format32bppArgb);
        using (Graphics g = Graphics.FromImage(large))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);

            using GraphicsPath body = RoundedRect(hi * 0.12f, hi * 0.22f, hi * 0.76f, hi * 0.58f, hi * 0.17f);
            using (var fill = new SolidBrush(Body))
            {
                g.FillPath(fill, body);
            }

            using (var rim = new Pen(Rim, Math.Max(1f, hi / 42f)))
            {
                g.DrawPath(rim, body);
            }

            // Lid seam, then the status light that makes it read as an AirPods case rather than
            // a generic box.
            using (var seam = new Pen(Detail, Math.Max(1.5f, hi / 26f)))
            {
                g.DrawLine(seam, hi * 0.18f, hi * 0.41f, hi * 0.82f, hi * 0.41f);
            }

            using var led = new SolidBrush(Detail);
            g.FillEllipse(led, hi * 0.45f, hi * 0.50f, hi * 0.10f, hi * 0.10f);
        }

        using var bitmap = new Bitmap(size, size, PixelFormat.Format32bppArgb);
        using (Graphics g = Graphics.FromImage(bitmap))
        {
            g.CompositingMode = CompositingMode.SourceCopy;
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.PixelOffsetMode = PixelOffsetMode.HighQuality;
            g.Clear(Color.Transparent);
            g.DrawImage(large, new Rectangle(0, 0, size, size));
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

    private static GraphicsPath RoundedRect(float x, float y, float w, float h, float r)
    {
        var path = new GraphicsPath();
        path.AddArc(x, y, r * 2, r * 2, 180, 90);
        path.AddArc(x + w - r * 2, y, r * 2, r * 2, 270, 90);
        path.AddArc(x + w - r * 2, y + h - r * 2, r * 2, r * 2, 0, 90);
        path.AddArc(x, y + h - r * 2, r * 2, r * 2, 90, 90);
        path.CloseFigure();
        return path;
    }
}
