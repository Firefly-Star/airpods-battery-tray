using System.Globalization;

namespace AirPodsSpike.Interop;

internal static class BtAddress
{
    // SOCKADDR_BTH.btAddr is host-order with the leftmost octet most significant,
    // so "EC:73:79:5A:E3:AE" maps straight to 0xEC73795AE3AE.
    public static ulong Parse(string text)
    {
        string hex = text.Replace(":", "").Replace("-", "").Trim();
        if (hex.Length != 12)
        {
            throw new FormatException($"expected 12 hex digits, got '{text}'");
        }
        return ulong.Parse(hex, NumberStyles.HexNumber, CultureInfo.InvariantCulture);
    }

    public static string Format(ulong address)
    {
        string hex = address.ToString("X12", CultureInfo.InvariantCulture);
        return string.Join(":", Enumerable.Range(0, 6).Select(i => hex.Substring(i * 2, 2)));
    }
}
